package com.alal.downloader.core.engine

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Resolves server and URL names before a host-based, MIME-aware fallback. */
object FilenameResolver {
    fun normalizeUrl(value: String): String {
        val text = value.trim()
        return (if ("://" in text) text else "https://$text").toHttpUrl().toString()
    }

    fun resolve(url: String, disposition: String? = null, mime: String? = null, now: Long = System.currentTimeMillis()): String {
        RangeProbe.parseContentDisposition(disposition)?.let { return FileNames.sanitize(it) }
        val parsed = normalizeUrl(url).toHttpUrl()
        parsed.pathSegments.lastOrNull()?.takeIf { Regex(".+\\.[A-Za-z0-9]{1,16}").matches(it) }
            ?.let { return FileNames.sanitize(it) }
        val extension = when (mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
            "application/zip", "application/x-zip-compressed" -> "zip"
            "application/pdf" -> "pdf"
            "application/octet-stream" -> "bin"
            "application/json" -> "json"
            "application/vnd.android.package-archive" -> "apk"
            "application/x-7z-compressed" -> "7z"
            "application/gzip" -> "gz"
            "text/plain" -> "txt"
            "text/html" -> "html"
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "audio/mpeg" -> "mp3"
            "video/mp4" -> "mp4"
            else -> null
        }
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        return FileNames.sanitize("${parsed.host}-$timestamp" + (extension?.let { ".$it" } ?: ""))
    }
}