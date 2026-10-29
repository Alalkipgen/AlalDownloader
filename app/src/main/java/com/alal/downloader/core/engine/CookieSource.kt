package com.alal.downloader.core.engine

/** Browser cookie lookup for redirect hops that leave the original host; implemented outside the engine. */
fun interface CookieSource {
    fun cookieFor(url: String): String?

    companion object {
        val None = CookieSource { null }
    }
}
