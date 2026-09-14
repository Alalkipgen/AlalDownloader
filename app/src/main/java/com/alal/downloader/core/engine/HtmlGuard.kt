package com.alal.downloader.core.engine

/** Rejects page responses while preserving explicitly attached HTML files. */
object HtmlGuard {
    fun needsBrowser(contentType: String?, disposition: String?): Boolean =
        contentType?.substringBefore(';')?.trim().equals("text/html", ignoreCase = true) &&
            !disposition?.substringBefore(';')?.trim().equals("attachment", ignoreCase = true)

    fun check(url: String, contentType: String?, disposition: String?) {
        if (needsBrowser(contentType, disposition)) throw NeedsBrowser(url)
    }
}
