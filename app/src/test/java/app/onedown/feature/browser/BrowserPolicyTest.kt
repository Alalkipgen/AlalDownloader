package app.onedown.feature.browser

import app.onedown.core.engine.DownloadRequest
import app.onedown.core.engine.DownloadState
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Browser decisions and streaming data decoding without Android runtime dependencies. */
class BrowserPolicyTest {
    @Test fun addressDistinguishesDomainsSearchAndMagnet() {
        assertEquals("https://example.com/file.zip", BrowserPolicy.address(" example.com/file.zip "))
        assertEquals("https://duckduckgo.com/?q=two+words", BrowserPolicy.address("two words"))
        assertEquals("magnet:?xt=abc", BrowserPolicy.address("magnet:?xt=abc"))
        assertTrue(BrowserPolicy.address("javascript:alert(1)").startsWith(BrowserPolicy.HOME))
    }

    @Test fun extensionUsesPathNotSignedQuery() {
        assertTrue(BrowserPolicy.downloadable("https://example.com/FILE.ZIP?token=x", "zip pdf"))
        assertFalse(BrowserPolicy.downloadable("https://example.com/page?file=a.zip", "zip"))
        assertFalse(BrowserPolicy.downloadable("https://example.com/page", ""))
    }

    @Test fun filenamesCannotEscapeDestination() {
        assertEquals("safe.zip", BrowserPolicy.sanitize("../../safe.zip"))
        assertEquals("safe.zip", BrowserPolicy.sanitize("C:\\safe.zip"))
        assertEquals("download.bin", BrowserPolicy.sanitize(".."))
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