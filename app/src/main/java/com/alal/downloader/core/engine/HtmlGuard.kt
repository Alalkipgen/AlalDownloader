package com.alal.downloader.core.engine

/** Rejects page responses while preserving explicitly attached HTML files. */
object HtmlGuard {
    fun checkPrefix(url: String, bytes: ByteArray, disposition: String?) {
        if (disposition?.substringBefore(';')?.trim().equals("attachment", true)) return
        val prefix = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n', '\u000c')
        if (prefix.startsWith("<!doctype html", true) || prefix.startsWith("<html", true)) throw NeedsBrowser(url)
    }

    fun needsBrowser(contentType: String?, disposition: String?): Boolean =
        contentType?.substringBefore(';')?.trim().equals("text/html", ignoreCase = true) &&
            !disposition?.substringBefore(';')?.trim().equals("attachment", ignoreCase = true)

    fun check(url: String, contentType: String?, disposition: String?) {
        if (needsBrowser(contentType, disposition)) throw NeedsBrowser(url)
    }
}
