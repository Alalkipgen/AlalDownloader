package com.alal.downloader.feature.downloads

import com.alal.downloader.core.engine.DownloadState
import com.alal.downloader.core.engine.DownloadStatus
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/** Pure presentation and bounded batch input rules. */
object DownloadPresentation {
    val filters = listOf("All", "Active", "Completed", "Failed")
    fun matches(state: DownloadState, filter: String): Boolean = when (filter) {
         "Waiting" -> state.status in setOf(DownloadStatus.PAUSED, DownloadStatus.WAITING_FOR_WIFI, DownloadStatus.WAITING_FOR_NETWORK)
        "Active" -> state.status in setOf(DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.WAITING_FOR_NETWORK, DownloadStatus.WAITING_FOR_WIFI, DownloadStatus.PAUSED)
        "Completed" -> state.status == DownloadStatus.COMPLETED
        "Failed" -> state.status == DownloadStatus.FAILED
        else -> true
    }
    fun bytes(value: Long): String = when {
        value < 0 -> "Unknown"
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KiB", value / 1024.0)
        value < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MiB", value / 1048576.0)
        else -> String.format(Locale.ROOT, "%.2f GiB", value / 1073741824.0)
    }
    fun percent(state: DownloadState): Int? = when {
        state.status == DownloadStatus.COMPLETED -> 100
        state.totalBytes > 0 -> (state.downloadedBytes.toDouble() / state.totalBytes * 100).toInt().coerceIn(0, 100)
        else -> null
    }
    fun eta(state: DownloadState): String {
        if (state.status != DownloadStatus.RUNNING || state.totalBytes < 0 || state.speedBytesPerSecond <= 0) return "—"
        val seconds = ((state.totalBytes - state.downloadedBytes).coerceAtLeast(0) / state.speedBytesPerSecond)
        return if (seconds >= 3600) "${seconds / 3600}h ${(seconds % 3600) / 60}m" else "${seconds / 60}m ${seconds % 60}s"
    }
    fun headers(headers: Map<String, String>): String = headers.entries.joinToString("\n") {
        val secret = it.key.lowercase() in setOf("cookie", "set-cookie", "authorization", "proxy-authorization", "x-api-key")
        "${it.key}: ${if (secret) "[redacted]" else it.value}"
    }
    const val MAX_INPUT = 1024 * 1024
    fun batch(input: String): Pair<List<String>, List<Int>> {
        require(input.length <= MAX_INPUT && input.toByteArray(Charsets.UTF_8).size <= MAX_INPUT) { "Import is limited to 1 MiB of text" }
        val urls = mutableListOf<String>()
        val invalid = mutableListOf<Int>()
        val lines = input.removePrefix("\uFEFF").lineSequence()
        lines.forEachIndexed { index, line ->
            val text = line.trim()
            if (text.isNotEmpty()) {
                val parsed = if (text.startsWith("magnet:", true)) text else
                    runCatching { com.alal.downloader.core.engine.FilenameResolver.normalizeUrl(text) }.getOrNull()
                if (parsed == null || text.any { it.isWhitespace() }) invalid.add(index + 1) else urls.add(parsed)
            }
            require(urls.size + invalid.size <= 1000) { "Import is limited to 1000 URLs" }
        }
        return urls to invalid
    }
}