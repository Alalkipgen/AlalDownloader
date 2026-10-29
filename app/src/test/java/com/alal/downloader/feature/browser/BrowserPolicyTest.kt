package com.alal.downloader.feature.browser

import com.alal.downloader.core.engine.DownloadRequest
import com.alal.downloader.core.engine.DownloadState
import com.alal.downloader.core.engine.ProbeResult
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Browser decisions and streaming data decoding without Android runtime dependencies. */
class BrowserPolicyTest {
    @Test fun addressDistinguishesDomainsSearchAndMagnet() {
        assertEquals("https://example.com/file.zip", BrowserPolicy.address(" example.com/file.zip "))
        assertEquals("https://www.google.com/search?q=two+words", BrowserPolicy.address("two words"))
        assertEquals("magnet:?xt=abc", BrowserPolicy.address("magnet:?xt=abc"))
        assertTrue(BrowserPolicy.address("javascript:alert(1)").startsWith(BrowserPolicy.HOME))
    }

    @Test fun extensionUsesPathNotSignedQuery() {
        assertTrue(BrowserPolicy.downloadable("https://example.com/FILE.ZIP?token=x", "zip pdf"))
        assertFalse(BrowserPolicy.downloadable("https://example.com/page?file=a.zip", "zip"))
        assertFalse(BrowserPolicy.downloadable("https://example.com/page", ""))
    }

    @Test fun defaultExtensionsCaptureZipAndSupportWhitespaceSeparators() {
        assertTrue(BrowserPolicy.downloadable("https://example.com/file.zip", BrowserPolicy.EXTENSIONS))
        assertTrue(BrowserPolicy.downloadable("https://example.com/file.zip", "pdf\tzip\nmp4"))
        assertTrue(BrowserPolicy.downloadable("https://example.com/file.zip", ".pdf,.zip;.mp4"))
        assertFalse(BrowserPolicy.downloadable("https://example.com/file.zip", "gzip"))
    }

    @Test fun navigationLoadsPagesWhoseUrlEndsInDownloadExtension() {
        listOf("https://files.example.com/a1b2c3/video.mp4", "https://example.com/FILE.ZIP?token=x",
            "http://example.com/movie.mkv", "https://example.com/page").forEach {
            assertEquals(it, BrowserPolicy.Navigation.LOAD, BrowserPolicy.navigation(it))
        }
    }

    @Test fun navigationKeepsMagnetPageDataAndUnsupportedSchemes() {
        assertEquals(BrowserPolicy.Navigation.MAGNET, BrowserPolicy.navigation("magnet:?xt=urn:btih:abc"))
        assertEquals(BrowserPolicy.Navigation.MAGNET, BrowserPolicy.navigation("MAGNET:?xt=urn:btih:abc"))
        assertEquals(BrowserPolicy.Navigation.CAPTURE, BrowserPolicy.navigation("blob:https://example.com/0f1e2d3c"))
        assertEquals(BrowserPolicy.Navigation.CAPTURE, BrowserPolicy.navigation("data:video/mp4;base64,AAAA"))
        listOf("intent://scan#Intent;end", "mailto:user@example.com", "about:blank", "file:///sdcard/video.mp4",
            "ftp://example.com/file.zip").forEach {
            assertEquals(it, BrowserPolicy.Navigation.UNSUPPORTED, BrowserPolicy.navigation(it))
        }
    }

    @Test fun documentTypeReadsOnlyAJsonMimeString() {
        assertEquals("video/mp4", BrowserPolicy.documentType("\"video/mp4\""))
        assertEquals("text/plain", BrowserPolicy.documentType(" \"Text/Plain; charset=UTF-8\" "))
        listOf(null, "null", "\"\"", "video/mp4", "\"not a type\"", "\"<b>/x\"").forEach {
            assertNull(it, BrowserPolicy.documentType(it))
        }
    }

    @Test fun documentOfferCrossesTypeExtensionAndSetting() {
        val mp4 = "https://files.example.com/a1b2c3/video.mp4"
        val zip = "https://cdn.example.com/files/pack.zip?token=x"
        val bare = "https://cdn.example.com/stream?id=1"
        for (type in listOf("video/mp4", "video/webm", "audio/mpeg", "Audio/Ogg; codecs=opus")) {
            for (url in listOf(mp4, zip, bare)) for (setting in listOf(BrowserPolicy.EXTENSIONS, "")) {
                assertTrue("$type $url [$setting]", BrowserPolicy.offersDocument(type, url, setting))
            }
        }
        for (type in listOf("text/html", "application/xhtml+xml", "TEXT/HTML; charset=utf-8")) {
            for (url in listOf(mp4, zip, bare)) assertFalse("$type $url", BrowserPolicy.offersDocument(type, url, BrowserPolicy.EXTENSIONS))
        }
        for (type in listOf("text/plain", "application/octet-stream", "application/zip")) {
            assertTrue(type, BrowserPolicy.offersDocument(type, zip, BrowserPolicy.EXTENSIONS))
            assertTrue(type, BrowserPolicy.offersDocument(type, zip, ".pdf,.zip"))
            assertFalse(type, BrowserPolicy.offersDocument(type, zip, "pdf mp4"))
            assertFalse(type, BrowserPolicy.offersDocument(type, zip, ""))
            assertFalse(type, BrowserPolicy.offersDocument(type, bare, BrowserPolicy.EXTENSIONS))
        }
        assertFalse(BrowserPolicy.offersDocument("image/png", "https://cdn.example.com/a.png", BrowserPolicy.EXTENSIONS))
        assertTrue(BrowserPolicy.offersDocument("image/png", "https://cdn.example.com/a.png", "png"))
        for (type in listOf(null, "", " ")) assertFalse(BrowserPolicy.offersDocument(type, mp4, BrowserPolicy.EXTENSIONS))
        assertFalse(BrowserPolicy.offersDocument("video/mp4", "blob:https://example.com/0f1e", BrowserPolicy.EXTENSIONS))
        assertFalse(BrowserPolicy.offersDocument("video/mp4", "data:video/mp4;base64,AAAA", BrowserPolicy.EXTENSIONS))
    }

    @Test fun documentReferrerIsThePreviousPageNeverTheFile() {
        val page = "https://host.example.com/f/abc"
        val file = "https://cdn.example.com/v/clip.mp4?token=x"
        assertEquals(page, BrowserPolicy.documentReferrer(listOf(BrowserPolicy.HOME, page, file), 2, file))
        assertEquals(page, BrowserPolicy.documentReferrer(listOf(page, "https://cdn.example.com/v/clip.mp4?challenge=1", file), 2, file))
        assertEquals(page, BrowserPolicy.documentReferrer(listOf(BrowserPolicy.HOME, page), 1, file))
        assertNull(BrowserPolicy.documentReferrer(listOf(file), 0, file))
        assertNull(BrowserPolicy.documentReferrer(listOf("about:blank", file), 1, file))
        assertNull(BrowserPolicy.documentReferrer(emptyList(), -1, file))
    }

    @Test fun documentDetailsUseProbeNameAndSizeOrFallBackToUrlName() {
        val probe = ProbeResult(4096, true, null, null, "https://cdn.example.com/final", "final.mp4", serverFileName = "Real Name.mp4")
        assertEquals("Real Name.mp4" to 4096L, BrowserPolicy.documentDetails(probe, "clip.mp4"))
        assertEquals("clip.mp4" to 4096L, BrowserPolicy.documentDetails(probe.copy(serverFileName = null), "clip.mp4"))
        assertEquals("clip.mp4" to -1L, BrowserPolicy.documentDetails(null, "clip.mp4"))
    }

    @Test fun acceptanceAddressesResolveWithoutSendingMagnetToWebView() {
        assertEquals("https://www.facebook.com/", BrowserPolicy.address("www.facebook.com"))
        assertEquals("https://www.google.com/search?q=notion+android", BrowserPolicy.address("notion android"))
        assertEquals("magnet:?xt=urn:btih:abc", BrowserPolicy.address("magnet:?xt=urn:btih:abc"))
    }

    @Test fun filenamesCannotEscapeDestination() {
        assertEquals("safe.zip", BrowserPolicy.sanitize("../../safe.zip"))
        assertEquals("safe.zip", BrowserPolicy.sanitize("C:\\safe.zip"))
        assertEquals("download", BrowserPolicy.sanitize(".."))
        assertEquals("file.exe", BrowserPolicy.sanitize("file\u202e.exe"))
    }

    @Test fun refreshMatchesNameOrKnownSizeButNotUnknownSize() {
        val target = DownloadState("id", DownloadRequest("https://example.com/file", "file.zip", targetDir = File(".")), totalBytes = 42)
        assertTrue(BrowserPolicy.matches(target, "file.zip", -1))
        assertTrue(BrowserPolicy.matches(target, "different.zip", 42))
        assertFalse(BrowserPolicy.matches(target, "different.zip", -1))
        assertFalse(BrowserPolicy.matches(target.copy(totalBytes = -1), "different.zip", -1))
    }

    @Test fun replayDropsEngineOwnedHeaders() {
        assertEquals(mapOf("Cookie" to "session=test", "User-Agent" to "browser"), BrowserPolicy.replayHeaders(
            mapOf("rAnGe" to "bytes=0-1", "If-Range" to "old", "Host" to "old", "Accept-Encoding" to "gzip",
                "Cookie" to "session=test", "User-Agent" to "browser")))
    }

    @Test fun dataDecodingPreservesPlusAndBinaryBytes() {
        assertEquals("hello+world", decode("data:text/plain,hello+world"))
        assertEquals("hello world", decode("data:text/plain,hello%20world"))
        assertEquals("hello", decode("data:text/plain;base64,aGVsbG8="))
        assertEquals("hi", decode("data:;base64,aGk%3D"))
        assertEquals("h", decode("data:;base64,aA=="))
        assertEquals("", decode("data:,"))
        val output = ByteArrayOutputStream()
        BrowserPolicy.decodeData("data:,%00%FF", output) { }
        assertTrue(output.toByteArray().contentEquals(byteArrayOf(0, -1)))
    }

    @Test fun largeDataUsesBoundedWrites() {
        var total = 0L
        BrowserPolicy.decodeData("data:," + "a".repeat(200_000), object : java.io.OutputStream() {
            override fun write(value: Int) { total++ }
            override fun write(bytes: ByteArray, start: Int, count: Int) { assertTrue(count <= 49152); total += count }
        }) { }
        assertEquals(200_000L, total)
    }

    @Test(expected = IllegalArgumentException::class) fun malformedPercentFails() { decode("data:,%GG") }
    @Test(expected = IllegalArgumentException::class) fun malformedBase64Fails() { decode("data:;base64,a===") }

    private fun decode(url: String): String = ByteArrayOutputStream().also {
        BrowserPolicy.decodeData(url, it) { }
    }.toString("UTF-8")
}