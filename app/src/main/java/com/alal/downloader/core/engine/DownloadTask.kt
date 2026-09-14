package com.alal.downloader.core.engine

import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Executes one persisted transfer and checkpoints durable segment offsets. */
class DownloadTask(
    initial: DownloadState,
    client: OkHttpClient,
    private val store: DownloadStore,
    private val segmentCount: Int = 8,
    private val connections: Int = 8,
    private val publish: (DownloadState) -> Unit,
    private val storage: DownloadStorage = FileDownloadStorage(),
    limiter: TransferLimiter = TransferLimiter(),
) {
    var state: DownloadState = initial
        private set
    private val mutex = Mutex()
    private var slots = mutableSetOf<Int>()
    private val slotMutex = Mutex()
    private val probe = RangeProbe(client)
    private val downloader = SegmentDownloader(client, limiter)
    private val samples = ArrayDeque<Pair<Long, Long>>()

    init {
        require(segmentCount in 1..32 && connections in 1..32)
    }

    private suspend fun update(next: DownloadState) {
        try {
            store.save(next)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw if (failure.asDownloadError() is DownloadError.DiskFull) DownloadError.DiskFull()
            else DownloadError.Unknown("Checkpoint failed: ${failure.message}")
        }
        state = next
        publish(next)
    }

    suspend fun run() = withContext(Dispatchers.IO) {
        try {
            update(state.copy(status = DownloadStatus.RUNNING, error = null))
            val metadata = probe.probe(state.request)
            var name = FileNames.sanitize(if (!state.request.preserveFileName && state.segments.isEmpty() && state.destinationUri == null) metadata.fileName ?: state.fileName else state.fileName)
            update(storage.prepare(state.copy(fileName = name)))
            name = state.fileName
            val old = state
            val existingLength = storage.length(old)
            val sameValidator = when {
                old.eTag != null && !old.eTag.startsWith("W/") -> old.eTag == metadata.eTag
                old.lastModified != null -> old.lastModified == metadata.lastModified
                else -> false
            }
            val resume = old.segments.isNotEmpty() && old.acceptsRanges && metadata.acceptsRanges &&
                old.totalBytes >= 0 && old.totalBytes == metadata.totalBytes && sameValidator &&
                (old.lastModified == null || old.lastModified == metadata.lastModified) &&
                existingLength != null && existingLength <= old.totalBytes
            val segments = if (resume) recoverSegments(old.segments, existingLength) else SegmentPlanner().plan(metadata.totalBytes, metadata.acceptsRanges, segmentCount)
            if (!resume) {
                update(old.copy(segments = emptyList(), acceptsRanges = false))
                storage.open(state).use {
                    it.resize(0)
                    if (metadata.totalBytes >= 0) it.resize(metadata.totalBytes)
                    it.sync()
                }
            }
            update(old.copy(
                fileName = name, totalBytes = metadata.totalBytes, segments = segments,
                eTag = metadata.eTag, lastModified = metadata.lastModified, finalUrl = metadata.finalUrl,
                acceptsRanges = metadata.acceptsRanges, status = DownloadStatus.RUNNING, error = null,
            ))
            samples.clear()
            samples.add(System.nanoTime() to state.downloadedBytes)
            try {
                transfer()
            } catch (_: RangeFallback) {
                update(state.copy(segments = emptyList(), acceptsRanges = false, speedBytesPerSecond = 0))
                val refreshed = probe.probe(state.request)
                update(state.copy(
                    totalBytes = refreshed.totalBytes, eTag = refreshed.eTag,
                    lastModified = refreshed.lastModified, finalUrl = refreshed.finalUrl,
                    segments = emptyList(),
                ))
                storage.open(state).use {
                    it.resize(0)
                    if (state.totalBytes >= 0) it.resize(state.totalBytes)
                    it.sync()
                }
                update(state.copy(acceptsRanges = false, segments = SegmentPlanner().plan(state.totalBytes, false)))
                samples.clear()
                transfer()
            }
            val expected = if (state.totalBytes >= 0) state.totalBytes else state.downloadedBytes
            if (storage.length(state) != expected || state.downloadedBytes != expected ||
                state.segments.any { it.length >= 0 && it.downloaded != it.length }
            ) throw DownloadError.Unknown("Final file size or segment coverage mismatch")
            storage.complete(state)
            update(state.copy(totalBytes = expected, status = DownloadStatus.COMPLETED, speedBytesPerSecond = 0))
        } catch (page: NeedsBrowser) {
            update(state.copy(status = DownloadStatus.NEEDS_BROWSER, finalUrl = page.url, error = null, speedBytesPerSecond = 0))
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                update(state.copy(status = DownloadStatus.PAUSED, speedBytesPerSecond = 0))
            }
            throw cancelled
        } catch (error: Exception) {
            val failed = state.copy(status = DownloadStatus.FAILED, error = error.asDownloadError(), speedBytesPerSecond = 0)
            state = failed
            publish(failed)
            try { store.save(failed) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
        }
    }

    private suspend fun transfer() = coroutineScope {
        val permits = Semaphore(connections)
        val snapshot = state
        val validator = snapshot.eTag?.takeUnless { it.startsWith("W/") } ?: snapshot.lastModified
        snapshot.segments.forEach { segment ->
            launch {
                permits.withPermit {
                    downloader.download(
                        snapshot.request, { storage.open(snapshot) }, segment, snapshot.acceptsRanges && snapshot.totalBytes >= 0,
                        snapshot.totalBytes, validator,
                    ) { bytes ->
                        mutex.withLock {
                            val updated = state.segments.map { if (it.index == segment.index) it.copy(downloaded = bytes) else it }
                            val total = updated.sumOf { it.downloaded }
                            val now = System.nanoTime()
                            if (samples.isNotEmpty() && total < samples.last.second) samples.clear()
                            samples.add(now to total)
                            while (samples.size > 1 && now - samples.first.first > 3_000_000_000L) samples.removeFirst()
                            val elapsed = now - samples.first.first
                            val speed = if (elapsed > 0) ((total - samples.first.second).toDouble() * 1e9 / elapsed).toLong() else 0
                            update(state.copy(segments = updated, speedBytesPerSecond = speed.coerceAtLeast(0)))
                        }
                    }
                }
            }
        }
    }

}