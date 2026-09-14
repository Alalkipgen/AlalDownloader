package com.alal.downloader.core.engine

import java.io.File
import java.io.IOException
import java.net.ServerSocket
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing tests for range metadata, server-provided filenames and failure reporting. */
class RangeProbeTest {
    @Test fun parsesKnownAndUnknownTotals() {
        assertEquals(RangeProbe.ContentRange(0, 0, 100), RangeProbe.parseContentRange("bytes 0-0/100"))
        assertEquals(RangeProbe.ContentRange(10, 19, -1), RangeProbe.parseContentRange(" bytes 10-19/* "))
    }

    @Test fun rejectsMalformedAndOverflowingRanges() {
        listOf(null, "", "bytes */100", "items 0-9/10", "bytes 9-0/10", "bytes 0-10/10",
            "bytes 0-0/0", "bytes 0-9223372036854775808/*", "bytes -1-0/10").forEach {
            assertNull(it, RangeProbe.parseContentRange(it))
        }
    }

    @Test fun parsesPlainAndQuotedNames() {
        assertEquals("file.zip", RangeProbe.parseContentDisposition("attachment; filename=file.zip"))
        assertEquals("a;b.zip", RangeProbe.parseContentDisposition("attachment; filename=\"a;b.zip\""))
    }

    @Test fun prefersExtendedUtf8AndPreservesPlus() {
        assertEquals("€+file.zip", RangeProbe.parseContentDisposition(
            "attachment; filename=old.zip; filename*=UTF-8''%E2%82%AC+file.zip"))
    }

    @Test fun invalidExtendedEncodingFallsBack() {
        assertEquals("old.zip", RangeProbe.parseContentDisposition("attachment; filename=old.zip; filename*=UTF-8''%ZZ"))
    }

    @Test fun stripsTraversalAndRejectsEmptyNames() {
        assertEquals("file.zip", RangeProbe.parseContentDisposition("attachment; filename=../../file.zip"))
        assertNull(RangeProbe.parseContentDisposition("attachment; filename=\"\""))
        assertNull(RangeProbe.parseContentDisposition(null))
        assertNull(RangeProbe.parseContentDisposition("attachment; filename=.."))
    }

    @Test fun failureMessageNamesRootCauseAndHost() {
        val wrapped = IOException("wrapper", SSLHandshakeException("handshake aborted"))
        assertEquals("SSLHandshakeException: handshake aborted (mmunicode.org.mm)",
            RangeProbe.describe(wrapped, "https://mmunicode.org.mm/downloads/zips/All-in-One_Pyidaungsu_Font.zip"))
        assertEquals("IOException: no detail (example.com)", RangeProbe.describe(IOException(), "https://example.com/a"))
    }

    @Test fun connectProbeMakesASingleAttemptAndReportsTheRealReason() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val client = OkHttpClient()
        try {
            val started = System.nanoTime()
            var failure: DownloadError.Network? = null
            try {
                RangeProbe(client).probe(DownloadRequest("http://127.0.0.1:$port/file.zip", "file.zip",
                    targetDir = File("."), retryOnFailure = false))
            } catch (network: DownloadError.Network) { failure = network }
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            assertNotNull(failure)
            val message = failure!!.message.orEmpty()
            assertFalse(message, message.contains("probe failed"))
            assertTrue(message, message.contains("127.0.0.1"))
            assertTrue(message, message.contains("Exception"))
            assertTrue("took $elapsedMillis ms; retry backoff must not run for Connect", elapsedMillis < 5_000)
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
