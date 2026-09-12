package com.alal.downloader.feature.browser

import android.content.Context

/** Small browser preferences kept separate from transfer policy. */
class BrowserSettings(context: Context) {
    private val preferences = context.getSharedPreferences("browser", Context.MODE_PRIVATE)
    var blockPopups: Boolean
        get() = preferences.getBoolean("block_popups", true)
        set(value) { preferences.edit().putBoolean("block_popups", value).apply() }
    var desktop: Boolean
        get() = preferences.getBoolean("desktop", false)
        set(value) { preferences.edit().putBoolean("desktop", value).apply() }
    var clipboard: Boolean
        get() = preferences.getBoolean("clipboard", false)
        set(value) { preferences.edit().putBoolean("clipboard", value).apply() }
    var media: Boolean
        get() = preferences.getBoolean("media", false)
        set(value) { preferences.edit().putBoolean("media", value).apply() }
    var extensions: String
        get() = preferences.getString("extensions", BrowserPolicy.EXTENSIONS) ?: BrowserPolicy.EXTENSIONS
        set(value) { preferences.edit().putString("extensions", value).apply() }

    fun unseenClipboard(url: String): Boolean {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val seen = preferences.getStringSet("seen_clipboard", emptySet()).orEmpty()
        if (digest in seen) return false
        preferences.edit().putStringSet("seen_clipboard", (seen.takeLastSafe(99) + digest).toSet()).apply()
        return true
    }

    private fun Set<String>.takeLastSafe(count: Int) = toList().takeLast(count)
}