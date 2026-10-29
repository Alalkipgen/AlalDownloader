package com.alal.downloader.feature.browser

import com.alal.downloader.feature.browser.BrowserPolicy.Guard
import com.alal.downloader.feature.browser.BrowserPolicy.Popup
import com.alal.downloader.feature.browser.BrowserPolicy.Tap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Popup and redirect guard decisions as pure functions. */
class BrowserGuardTest {
    private val host = "https://files.example.com/abc123/Movie.2026.720p.mp4"
    private val other = "https://ads.example.net/landing"

    private fun guard(page: String?, target: String, redirect: Boolean = false, gesture: Boolean = false,
        userChain: Boolean = false, tap: Tap? = null, block: Boolean = true, allowed: Set<String> = emptySet()) =
        BrowserPolicy.guard(page, target, redirect, gesture, userChain, tap, block, allowed)

    @Test fun pageToItsDownloadSubdomainLoads() {
        val download = "https://download.files.example.com/f/Movie.2026.720p.mp4"
        assertEquals(Guard.LOAD, guard(host, download))
        assertEquals(Guard.LOAD, guard(host, download, redirect = true))
    }

    @Test fun driveToUsercontentIsTheSameSite() {
        assertEquals("google.com", BrowserPolicy.site("https://drive.usercontent.google.com/download"))
        assertEquals(Guard.LOAD, guard("https://drive.google.com/file/d/abc123/view", "https://drive.usercontent.google.com/download"))
        assertEquals(Guard.USER, guard("https://drive.google.com/file/d/abc123/view", "https://drive.usercontent.google.com/download",
            gesture = true, tap = Tap("https://drive.usercontent.google.com/download", form = true, visible = true)))
    }

    @Test fun popupsNeverReplaceThePage() {
        assertEquals(Popup.BLOCK, BrowserPolicy.popup(host, other, gesture = true, blockPopups = true))
        assertEquals(Popup.TAB, BrowserPolicy.popup(host, "https://www.example.com/mirror", gesture = true, blockPopups = true))
        assertEquals(Popup.BLOCK, BrowserPolicy.popup(host, "https://www.example.com/mirror", gesture = false, blockPopups = true))
        assertEquals(Popup.BLOCK, BrowserPolicy.popup(null, other, gesture = true, blockPopups = true))
    }

    @Test fun scriptRedirectToAnotherSiteIsBlocked() {
        assertEquals(Guard.BLOCK, guard(host, other))
        assertEquals(Guard.BLOCK, guard(host, other, gesture = true))
        assertEquals(Guard.BLOCK, guard(host, other, gesture = true, tap = Tap("https://ads.example.net/other", false, true)))
        assertEquals(Guard.BLOCK, guard(host, other, tap = Tap(other, false, true)))
        assertEquals(Guard.LOAD, guard(host, "https://files.example.com/go"))
        assertEquals(Guard.BLOCK, guard(host, other, redirect = true))
    }

    @Test fun tappedVisibleLinkAndItsServerRedirectsLoad() {
        val link = "https://mirror.example.org/get/abc123"
        assertEquals(Guard.USER, guard(host, link, gesture = true, tap = Tap("$link#top", false, true)))
        assertEquals(Guard.USER, guard(host, "https://cdn.example.info/Movie.mp4", redirect = true, userChain = true))
        assertEquals(Guard.USER, guard(host, "https://files.example.com/out", gesture = true, tap = Tap("https://files.example.com/out", false, true)))
        assertEquals(Guard.USER, guard(host, "https://search.example.org/find?q=movie", gesture = true,
            tap = Tap("https://search.example.org/find", form = true, visible = true)))
        assertEquals(Guard.USER, guard(host, "https://cdn.example.info/Movie.mp4", redirect = true,
            tap = Tap("https://files.example.com/download", form = true, visible = true)))
        assertEquals(Guard.BLOCK, guard(host, "https://search.example.org/find?q=movie", gesture = true,
            tap = Tap("https://search.example.org/find", form = false, visible = true)))
    }

    @Test fun overlayAndInvisibleTapsAreBlocked() {
        assertFalse(BrowserPolicy.visible(1.0, 412.0, 915.0, 412.0, 915.0))
        assertFalse(BrowserPolicy.visible(1.0, 2000.0, 2000.0, 412.0, 915.0))
        assertFalse(BrowserPolicy.visible(0.0, 160.0, 48.0, 412.0, 915.0))
        assertFalse(BrowserPolicy.visible(0.05, 160.0, 48.0, 412.0, 915.0))
        assertFalse(BrowserPolicy.visible(1.0, 1.0, 1.0, 412.0, 915.0))
        assertFalse(BrowserPolicy.visible(Double.NaN, 160.0, 48.0, 412.0, 915.0))
        assertTrue(BrowserPolicy.visible(1.0, 160.0, 48.0, 412.0, 915.0))
        assertTrue(BrowserPolicy.visible(0.9, 412.0, 300.0, 412.0, 915.0))
        assertEquals(Guard.BLOCK, guard(host, other, gesture = true, tap = Tap(other, false, visible = false)))
    }

    @Test fun allowedSiteRedirects() {
        assertEquals(Guard.LOAD, guard("https://www.files.example.com/x", other, allowed = setOf("example.com")))
        assertEquals(Guard.BLOCK, guard("https://www.files.example.com/x", other, allowed = setOf("example.net")))
    }

    @Test fun eachSettingOff() {
        assertEquals(Guard.LOAD, guard(host, other, block = false))
        assertEquals(Guard.LOAD, guard(host, other, redirect = true, block = false))
        assertEquals(Popup.TAB, BrowserPolicy.popup(host, other, gesture = false, blockPopups = false))
        assertEquals(Popup.TAB, BrowserPolicy.popup(host, other, gesture = true, blockPopups = false))
    }

    @Test fun blankTabLoadsAndSitesFallBackToTheHost() {
        assertEquals(Guard.LOAD, guard(null, other))
        assertEquals(Guard.LOAD, guard("about:blank", other))
        assertEquals("localhost", BrowserPolicy.site("http://localhost:8080/a"))
        assertNull(BrowserPolicy.site("not a url"))
        assertEquals("https://sub.example.com:8443/", BrowserPolicy.origin("https://name@sub.example.com:8443/a/b?c=d#e"))
        assertEquals("https://example.com/", BrowserPolicy.origin("https://example.com/a"))
    }
}
