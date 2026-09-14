package com.alal.downloader.core.engine

import java.io.IOException
import java.net.URI
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Response

/** Discovers remote size, validators, filename and range capability without reading the file. */
class RangeProbe(client: OkHttpClient) {
    private val http = DownloadHttp(client)

    /**
     * Interactive callers (the Connect button) pass retryOnFailure=false and get exactly one attempt so the real
     * failure reason is visible immediately; the transfer engine keeps the five-attempt exponential backoff.
     */
    suspend fun probe(request: DownloadRequest): ProbeResult = withContext(Dispatchers.IO) {
        val attempts = if (request.retryOnFailure) 5 else 1
        for (attempt in 0 until attempts) {
            try {
                return@withContext probeOnce(request)
            } catch (failure: IOException) {
                if (attempt == attempts - 1) throw DownloadError.Network(describe(failure, request.url)).apply { initCause(failure) }
                delay(1_000L shl attempt)
            }
        }
        throw DownloadError.Unknown("Probe retry limit exhausted")
    }

    private suspend fun probeOnce(request: DownloadRequest): ProbeResult {
        try {
            http.execute(request, "HEAD").use { head ->
                if (head.isSuccessful) HtmlGuard.check(head.request.url.toString(), head.header("Content-Type"), head.header("Content-Disposition"))
                if (!head.isSuccessful && head.code !in listOf(403, 405, 501)) checkHttp(head.code)
            }
        } catch (_: IOException) {
            // Some servers drop, reset or time out HEAD requests; the ranged GET below is authoritative.
        }
        return http.execute(request, "GET", "bytes=0-511").use { response ->
            HtmlGuard.check(response.request.url.toString(), response.header("Content-Type"), response.header("Content-Disposition"))
            HtmlGuard.checkPrefix(response.request.url.toString(), response.peekBody(512).bytes(), response.header("Content-Disposition"))
            if (response.code == 416 && response.header("Content-Range")?.trim() == "bytes */0") {
                return metadata(response).copy(totalBytes = 0, acceptsRanges = true)
            }
            checkHttp(response.code)
            if (response.code != 200 && response.code != 206) throw DownloadError.Unknown("Unexpected probe response")
            val result = metadata(response)
            if (response.code == 206) {
                val range = parseContentRange(response.header("Content-Range"))
                    ?: throw DownloadError.Unknown("Invalid probe Content-Range")
                if (range.start != 0L || range.end > 511L) throw DownloadError.Unknown("Unexpected probe range")
            }
            result.copy(acceptsRanges = response.code == 206)
        }
    }

    private fun metadata(response: Response): ProbeResult {
        val range = parseContentRange(response.header("Content-Range"))
        val length = if (response.code == 206) range?.total ?: -1 else
            response.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 } ?: -1
        return ProbeResult(
            length,
            response.code == 206 || response.header("Accept-Ranges").equals("bytes", true),
            response.header("ETag"), response.header("Last-Modified"),
            response.request.url.toString(), FilenameResolver.resolve(response.request.url.toString(), response.header("Content-Disposition"), response.header("Content-Type")),
        )
    }

    /** Parsed inclusive response range; total is -1 when the server uses an asterisk. */
    data class ContentRange(val start: Long, val end: Long, val total: Long)

    companion object {
        /** Human-readable failure reason built from the root cause, e.g. "SSLHandshakeException: … (host)". */
        fun describe(failure: Throwable, url: String): String {
            val root = generateSequence(failure) { it.cause }.last()
            val host = runCatching { URI(url).host }.getOrNull() ?: url
            val detail = root.message?.takeIf { it.isNotBlank() } ?: "no detail"
            return "${root.javaClass.simpleName}: $detail ($host)"
        }

        fun parseContentRange(value: String?): ContentRange? {
            val match = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
                .matchEntire(value?.trim() ?: return null) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].toLongOrNull() ?: return null
            val total = if (match.groupValues[3] == "*") -1 else match.groupValues[3].toLongOrNull() ?: return null
            if (end < start || end == Long.MAX_VALUE || (total != -1L && (total == 0L || end >= total))) return null
            return ContentRange(start, end, total)
        }

        fun parseContentDisposition(value: String?): String? {
            if (value == null) return null
            val parameters = Regex("(?:^|;)\\s*(filename\\*?)\\s*=\\s*(\"(?:\\\\.|[^\"])*\"|[^;]*)", RegexOption.IGNORE_CASE)
                .findAll(value).associate { match ->
                    val raw = match.groupValues[2].trim()
                    match.groupValues[1].lowercase() to if (raw.startsWith('"') && raw.endsWith('"'))
                        raw.substring(1, raw.length - 1).replace(Regex("\\\\(.)"), "$1") else raw
                }
            val extended = parameters["filename*"]?.let { encoded ->
                val parts = encoded.split('\'', limit = 3)
                if (parts.size != 3 || parts[0].uppercase() !in setOf("UTF-8", "ISO-8859-1")) null
                else runCatching {
                    val bytes = java.io.ByteArrayOutputStream()
                    val encodedValue = parts[2]
                    var index = 0
                    while (index < encodedValue.length) {
                        val character = encodedValue[index]
                        if (character == '%') {
                            require(index + 2 < encodedValue.length)
                            bytes.write(encodedValue.substring(index + 1, index + 3).toInt(16))
                            index += 3
                        } else {
                            require(character.code in 33..126)
                            bytes.write(character.code)
                            index++
                        }
                    }
                    java.nio.charset.Charset.forName(parts[0]).newDecoder()
                        .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString().takeIf { it.isNotBlank() }
                }.getOrNull()
            }
            return (extended ?: parameters["filename"])?.replace('\\', '/')?.substringAfterLast('/')
                ?.filter { it >= ' ' && it != '\u007f' }?.trim()?.takeIf { it.isNotEmpty() && it != "." && it != ".." }
        }
    }
}
