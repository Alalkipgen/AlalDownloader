package com.alal.downloader.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saved tabs and bookmarks: round trips, damaged text and bookmark rules. */
class BrowserStateTest {
    private val megaUp = SavedPage("https://megaup.net/abc123/Verity.2026.720p.AMZN.WEB-DL.(Hmtv).NC.mp4", "Verity (2026) – MegaUp")
    private val drive = SavedPage("https://drive.google.com/file/d/abc123/view", "Movie.mkv - Google Drive")
    private val search = SavedPage("https://www.google.com/search?q=verity+2026", "verity 2026 - Google Search")

    @Test fun tabsRestoreInOrderWithTheSelectedOne() {
        val saved = SavedTabs(listOf(megaUp, drive, search), selected = 1)
        val restored = BrowserState.decodeTabs(BrowserState.encodeTabs(saved))
        assertEquals(saved, restored)
        assertEquals(listOf(megaUp.url, drive.url, search.url), restored?.pages?.map { it.url })
        assertEquals(drive, restored?.pages?.get(restored.selected))
    }

    @Test fun titlesWithTabsNewlinesAndUnicodeSurvive() {
        val odd = SavedPage("https://example.com/a%20b/ဇာတ်ကား?x=1&y=2#part", "Line one\nline\ttwo – ဇာတ်ကား ⭐")
        assertEquals(SavedTabs(listOf(odd), 0), BrowserState.decodeTabs(BrowserState.encodeTabs(SavedTabs(listOf(odd), 0))))
    }

    @Test fun damagedTextGivesNoTabs() {
        val good = BrowserState.encodeTabs(SavedTabs(listOf(megaUp, drive), selected = 0))
        assertNull(BrowserState.decodeTabs(null))
        assertNull(BrowserState.decodeTabs(""))
        assertNull(BrowserState.decodeTabs("garbage"))
        assertNull(BrowserState.decodeTabs(good.replace("alal-tabs 1", "alal-tabs 9")))
        assertNull(BrowserState.decodeTabs(good.substringBeforeLast('\t')))
        assertNull(BrowserState.decodeTabs(good.replaceFirst("\n0\n", "\n7\n")))
        assertNull(BrowserState.decodeTabs(good.replaceFirst("\n0\n", "\nx\n")))
        assertNull(BrowserState.decodeTabs(good + "\n%E0%A4%A\tbroken"))
        assertNull(BrowserState.decodeTabs(good + "\njavascript%3Aalert(1)\tscript"))
        assertNull(BrowserState.decodeTabs("alal-tabs 1\n0"))
        assertNull(BrowserState.decodeTabs(good.substringBefore('\t')))
    }

    @Test fun onlyWebPagesAreKept() {
        assertNull(BrowserState.page("about:blank", "Blank"))
        assertNull(BrowserState.page("file:///sdcard/a.html", "File"))
        assertNull(BrowserState.page("https://example.com/" + "a".repeat(9000), "Long"))
        assertEquals(SavedPage("https://example.com/", "Title"), BrowserState.page("https://example.com/", "  Title  "))
        assertEquals(300, BrowserState.page("https://example.com/", "t".repeat(400))?.title?.length)
    }

    @Test fun bookmarksAddRemoveWithoutDuplicates() {
        var bookmarks = BrowserState.addBookmark(emptyList(), megaUp)
        bookmarks = BrowserState.addBookmark(bookmarks, drive)
        assertEquals(listOf(drive, megaUp), bookmarks)
        assertEquals(bookmarks, BrowserState.addBookmark(bookmarks, megaUp.copy(title = "Another title")))
        assertEquals(bookmarks, BrowserState.addBookmark(bookmarks, megaUp.copy(url = megaUp.url + "#comments")))
        assertEquals(bookmarks, BrowserState.addBookmark(bookmarks, drive.copy(url = "HTTPS://DRIVE.GOOGLE.COM/file/d/abc123/view")))
        assertTrue(BrowserState.bookmarked(bookmarks, megaUp.url))
        bookmarks = BrowserState.removeBookmark(bookmarks, megaUp.url)
        assertEquals(listOf(drive), bookmarks)
        assertFalse(BrowserState.bookmarked(bookmarks, megaUp.url))
        assertEquals(emptyList<SavedPage>(), BrowserState.removeBookmark(bookmarks, drive.url + "#top"))
    }

    @Test fun bookmarksRoundTripAndDamagedTextGivesNone() {
        val bookmarks = listOf(megaUp, drive, search)
        assertEquals(bookmarks, BrowserState.decodeBookmarks(BrowserState.encodeBookmarks(bookmarks)))
        assertEquals(emptyList<SavedPage>(), BrowserState.decodeBookmarks(BrowserState.encodeBookmarks(emptyList())))
        assertEquals(emptyList<SavedPage>(), BrowserState.decodeBookmarks(null))
        assertEquals(emptyList<SavedPage>(), BrowserState.decodeBookmarks("alal-tabs 1\n0"))
        assertEquals(emptyList<SavedPage>(), BrowserState.decodeBookmarks(BrowserState.encodeBookmarks(bookmarks) + "\nno-tab-here"))
    }
}
