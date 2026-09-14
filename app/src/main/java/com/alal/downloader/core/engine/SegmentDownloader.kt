package com.alal.downloader.core.engine

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Streams a segment directly to its file offsets with bounded, cancellable retries. */
class SegmentDownloader(client: OkHttpClient, private val limiter: TransferLimiter = TransferLimiter()) {
    private val http = DownloadHttp(client)

    suspend fun download(
        request: DownloadRequest,
        file: File,
        segment: Segment,
        ranged: Boolean,
        totalBytes: Long,
        validator: String?,
        onProgress: suspend (Long) -> Unit,
    ): Long = download(request, { FileDownloadOutput(file) }, segment, ranged, totalBytes, validator, onProgress)

    suspend fun download(
        request: DownloadRequest,
        openOutput: () -> DownloadOutput,
        segment: Segment,
        ranged: Boolean,
        totalBytes: Long,
        validator: String?,
        onProgress: suspend (Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        var downloaded = segment.downloaded
        var lastReport = System.nanoTime()
        for (attempt in 0 until 5) {
            currentCoroutineContext().ensureActive()
            if (ranged && segment.length >= 0 && downloaded == segment.length) return@withContext downloaded
            if (!ranged && downloaded > 0) {
                downloaded = 0
                onProgress(0)
                openOutput().use {
                    it.resize(0)
                    if (totalBytes >= 0) it.resize(totalBytes)
                    it.sync()
                }
            }
            try {
                val offset = segment.start + downloaded
                val range = if (ranged) "bytes=$offset-${segment.end}" else null
                http.execute(request, "GET", range, if (ranged) validator else null).use { response ->
                    HtmlGuard.check(request.url, response.header("Content-Type"), response.header("Content-Disposition"))
                    if (ranged && response.code == 200) throw RangeFallback()
                    checkHttp(response.code)
                    if (ranged) {
                        val returned = RangeProbe.parseContentRange(response.header("Content-Range"))
                        if (response.code != 206 || returned == null || returned.start != offset ||
                            returned.end != segment.end || returned.total != totalBytes
                        ) throw DownloadError.Unknown("Mismatched Content-Range")
                    } else if (response.code != 200) throw DownloadError.Unknown("Expected a full HTTP 200 response")
                    if (response.header("Content-Encoding")?.let { !it.equals("identity", true) } == true) {
                        throw DownloadError.Unknown("Encoded response cannot be safely written as byte ranges")
                    }
                    val body = response.body ?: throw IOException("Missing response body")
                    val expectedBody = if (segment.length >= 0) segment.length - downloaded else -1
                    val advertised = response.header("Content-Length")?.toLongOrNull()
                    if (expectedBody >= 0 && advertised != null && advertised != expectedBody) {
                        throw DownloadError.Unknown("Size mismatch: Content-Length differs from expected transfer length")
                    }
                    coroutineScope {
                        val closer: Job = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                            try { awaitCancellation() } finally { response.close() }
                        }
                        try {
                            openOutput().use { output ->
                                try {
                                body.byteStream().use { input ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val allowance = if (segment.length >= 0)
                                            minOf(limiter.readSize().toLong(), (segment.length - downloaded).coerceAtLeast(1)).toInt()
                                            else limiter.readSize()
                                        limiter.acquire(allowance)
                                        val read = try {
                                            currentCoroutineContext().ensureActive()
                                            input.read(buffer, 0, allowance)
                                        } catch (failure: Exception) {
                                            limiter.refund(allowance)
                                            throw failure
                                        }
                                        limiter.refund(allowance - read.coerceAtLeast(0))
                                        if (read == -1) break
                                        if (segment.length >= 0 && read.toLong() > segment.length - downloaded) {
                                            throw DownloadError.Unknown("Size mismatch: response exceeds advertised length; file is not complete")
                                        }
                                        try {
                                            output.write(segment.start + downloaded, buffer, read)
                                        } catch (error: IOException) {
                                            throw if (error.asDownloadError() is DownloadError.DiskFull) DownloadError.DiskFull()
                                            else DownloadError.Unknown("File write failed: ${error.message}")
                                        }
                                        downloaded += read
                                        if (System.nanoTime() - lastReport >= 200_000_000) {
                                            output.sync()
                                            onProgress(downloaded)
                                            lastReport = System.nanoTime()
                                        }
                                    }
                                    output.sync()
                                    onProgress(downloaded)
                                    if (segment.length >= 0 && downloaded != segment.length) throw IOException("Size mismatch: premature end of response")
                                }
                                } finally {
                                    withContext(NonCancellable + Dispatchers.IO) {
                                        try {
                                            output.sync()
                                            onProgress(downloaded)
                                        } catch (failure: IOException) {
                                            throw if (failure.asDownloadError() is DownloadError.DiskFull) DownloadError.DiskFull()
                                            else DownloadError.Unknown("File sync failed: ${failure.message}")
                                        }
                                    }
                                }
                            }
                        } finally {
                            closer.cancel()
                        }
                    }
                }
                return@withContext downloaded
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                if (error.asDownloadError() is DownloadError.DiskFull) throw DownloadError.DiskFull()
                if (attempt == 4) throw DownloadError.Network(error.message ?: "Transfer failed")
                delay(1_000L shl attempt)
            }
        }
        throw DownloadError.Unknown("Retry limit exhausted")
    }
}