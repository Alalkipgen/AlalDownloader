package app.onedown.core.engine

import app.onedown.feature.downloads.DownloadPresentation
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Deterministic token bucket, filename and Downloads presentation regressions. */
class Phase4PolicyTest {
    @Test fun aggregateLimitUsesOneBudgetAndLiveChanges() = runBlocking {
        var now = 0L
        val limiter = TransferLimiter({ now }, { now += it * 1_000_000 })
        limiter.setLimit(1024)
        limiter.acquire(1024)
        limiter.acquire(1024)
        assertTrue(now in 2_000_000_000L..2_010_000_000L)
        limiter.setLimit(2048)
        val start = now
        limiter.acquire(1024)
        assertTrue(now - start in 500_000_000L..510_000_000L)
        limiter.setLimit(0)
        val unlimited = now
        limiter.acquire(65536)
        assertEquals(unlimited, now)
    }

    @Test fun runtimeUnlimitedWakesBlockedAcquisition() = runBlocking {
        var now = 0L
        lateinit var limiter: TransferLimiter
        limiter = TransferLimiter({ now }, { now += it * 1_000_000; limiter.setLimit(0) })
        limiter.setLimit(1)
        limiter.acquire(65536)
        assertEquals(100_000_000L, now)
    }

    @Test fun cancelledWaitDoesNotContinue() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        val limiter = TransferLimiter(sleep = { waiting.complete(Unit); awaitCancellation() })
        limiter.setLimit(1)
        val job = launch { limiter.acquire(1000); fail("Cancelled acquisition completed") }
        waiting.await()
        withTimeout(1000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }

    @Test fun filenamesArePortableBoundedAndNumbered() {
        assertEquals("file (2).zip", FileNames.numbered("../file.zip", 2))
        assertEquals("download.bin", FileNames.sanitize(".."))
        assertEquals("ab.zip", FileNames.sanitize("a\u202Eb.zip"))
        val name = FileNames.numbered("😀".repeat(100) + ".zip", 123)
        assertTrue(name.toByteArray().size <= 180)
        assertTrue(name.endsWith(" (123).zip"))
        assertFalse(name.contains('\uFFFD'))
    }

    @Test fun extendedDispositionDecodesStrictlyAndFallsBack() {
        assertEquals("a+b.zip", RangeProbe.parseContentDisposition("attachment; filename*=UTF-8''a+b.zip"))
        assertEquals("café.zip", RangeProbe.parseContentDisposition("attachment; filename*=UTF-8''caf%C3%A9.zip"))
        assertEquals("safe.zip", RangeProbe.parseContentDisposition("attachment; filename=safe.zip; filename*=UTF-8''%FF"))
        assertEquals("safe.zip", RangeProbe.parseContentDisposition("attachment; filename=safe.zip; filename*=UTF-8''%Z1"))
    }

    @Test fun batchRetainsDuplicatesLongQueriesAndLineNumbers() {
        val url = "https://example.com/file?token=" + "a".repeat(12000)
        val (urls, invalid) = DownloadPresentation.batch("\uFEFF$url\r\n\n$url\nftp://example.com/file\nnot a url")
        assertEquals(listOf(url, url), urls)
        assertEquals(listOf(4, 5), invalid)
    }

    @Test fun oversizedBatchIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { DownloadPresentation.batch("x".repeat(DownloadPresentation.MAX_INPUT + 1)) }
        assertThrows(IllegalArgumentException::class.java) { DownloadPresentation.batch("https://example.com\n".repeat(1001)) }
    }

    @Test fun filteringRedactionAndZeroByteProgress() {
        val state = DownloadState("id", DownloadRequest("https://example.com", "file", targetDir = File("/tmp")))
        assertTrue(DownloadPresentation.matches(state.copy(status = DownloadStatus.PAUSED), "Active"))
        assertFalse(DownloadPresentation.matches(state.copy(status = DownloadStatus.CANCELLED), "Active"))
        assertTrue(DownloadPresentation.matches(state.copy(status = DownloadStatus.FAILED), "Failed"))
        assertEquals(100, DownloadPresentation.percent(state.copy(totalBytes = 0, status = DownloadStatus.COMPLETED)))
        assertNull(DownloadPresentation.percent(state))
        val headers = DownloadPresentation.headers(mapOf("cOoKiE" to "secret1", "AUTHORIZATION" to "secret2", "Accept" to "*/*"))
        assertFalse(headers.contains("secret"))
        assertTrue(headers.contains("Accept: */*"))
    }
}