package com.alal.downloader.feature.browser

import android.content.Context
import java.net.URLDecoder
import java.net.URLEncoder
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** An open tab or a bookmark: its address and title. */
data class SavedPage(val url: String, val title: String)

/** The open tabs in order and the index of the selected one. */
data class SavedTabs(val pages: List<SavedPage>, val selected: Int)

/** Pure text format of the browser's app-private state: one URL-encoded "address<TAB>title" line per page. */
object BrowserState {
    private const val TABS = "alal-tabs 1"
    private const val BOOKMARKS = "alal-bookmarks 1"
    const val MAX_TABS = 50
    const val MAX_BOOKMARKS = 500
    private const val MAX_URL = 8192
    private const val MAX_TITLE = 300

    /** Only http(s) addresses up to 8 KiB are kept; titles are trimmed to 300 characters. */
    fun page(url: String, title: String): SavedPage? =
        if (url.length <= MAX_URL && url.toHttpUrlOrNull() != null) SavedPage(url, title.trim().take(MAX_TITLE)) else null

    fun encodeTabs(tabs: SavedTabs): String = encode(TABS, tabs.pages, tabs.selected.toString())

    /** A damaged or foreign text gives no tabs. */
    fun decodeTabs(text: String?): SavedTabs? {
        val lines = text?.split('\n') ?: return null
        if (lines.size < 3 || lines[0] != TABS || lines.size - 2 > MAX_TABS) return null
        val selected = lines[1].toIntOrNull() ?: return null
        val pages = lines.drop(2).map { decode(it) ?: return null }
        return if (selected in pages.indices) SavedTabs(pages, selected) else null
    }

    fun encodeBookmarks(bookmarks: List<SavedPage>): String = encode(BOOKMARKS, bookmarks, null)

    /** A damaged or foreign text gives no bookmarks. */
    fun decodeBookmarks(text: String?): List<SavedPage> {
        val lines = text?.split('\n') ?: return emptyList()
        if (lines[0] != BOOKMARKS || lines.size - 1 > MAX_BOOKMARKS) return emptyList()
        return lines.drop(1).map { decode(it) ?: return emptyList() }
    }

    /** The same page: equal addresses once OkHttp normalizes them and drops the fragment. */
    fun bookmarked(bookmarks: List<SavedPage>, url: String): Boolean = key(url).let { wanted -> bookmarks.any { key(it.url) == wanted } }

    /** Newest first; an address already saved is not added twice. */
    fun addBookmark(bookmarks: List<SavedPage>, page: SavedPage): List<SavedPage> =
        if (bookmarked(bookmarks, page.url)) bookmarks else (listOf(page) + bookmarks).take(MAX_BOOKMARKS)

    fun removeBookmark(bookmarks: List<SavedPage>, url: String): List<SavedPage> =
        key(url).let { wanted -> bookmarks.filterNot { key(it.url) == wanted } }

    private fun key(url: String) = url.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()?.toString() ?: url

    private fun encode(header: String, pages: List<SavedPage>, extra: String?) = buildString {
        append(header)
        extra?.let { append('\n').append(it) }
        pages.forEach { append('\n').append(URLEncoder.encode(it.url, "UTF-8")).append('\t').append(URLEncoder.encode(it.title, "UTF-8")) }
    }

    private fun decode(line: String): SavedPage? {
        val parts = line.split('\t')
        if (parts.size != 2) return null
        return runCatching { page(URLDecoder.decode(parts[0], "UTF-8"), URLDecoder.decode(parts[1], "UTF-8")) }.getOrNull()
    }
}

/** App-private storage of the open tabs and bookmarks (never logged). */
class BrowserStore(context: Context) {
    private val preferences = context.getSharedPreferences("browser_state", Context.MODE_PRIVATE)

    fun tabs(): SavedTabs? = BrowserState.decodeTabs(preferences.getString("tabs", null))

    fun saveTabs(tabs: SavedTabs?) {
        preferences.edit().apply { if (tabs == null) remove("tabs") else putString("tabs", BrowserState.encodeTabs(tabs)) }.apply()
    }

    fun bookmarks(): List<SavedPage> = BrowserState.decodeBookmarks(preferences.getString("bookmarks", null))

    fun saveBookmarks(bookmarks: List<SavedPage>) { preferences.edit().putString("bookmarks", BrowserState.encodeBookmarks(bookmarks)).apply() }
}
