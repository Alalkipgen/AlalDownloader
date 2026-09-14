package com.alal.downloader.core.engine

import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Captured request priority, confirmed naming and safe replacement checkpoint regressions. */
class BrowserTransferTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun replacementPersistsUrlHeadersAndOffsetsBeforeQueueing() = runBlocking {
        val initial = expired()
        File(File(initial.request.targetDir, initial.id).apply { mkdirs() }, initial.fileName).writeText("abcxxx")
        val store = MemoryStore(listOf(initial))
        val client = OkHttpClient()
        val engine = DownloadEngine(client, store)
        engine.restore()
        engine.setNetworkAllowed(false)
        engine.replaceLink(initial.id, "https://example.com/new", mapOf("Cookie" to "fresh"))
        val saved = store.load().single()
        assertEquals(initial.segments, saved.segments)
        assertEquals(initial.id, saved.id)
        assertEquals("https://example.com/new", saved.request.url)
        assertEquals("fresh", saved.request.headers["Cookie"])
        assertEquals(DownloadStatus.WAITING_FOR_NETWORK, saved.status)
        assertEquals(initial.request.referrerPageUrl, saved.request.referrerPageUrl)
        engine.pauseAll()
        close(client)
    }

    @Test fun matchingValidatorResumesAtSavedOffset() = replacementTransfer("\"same\"", "bytes=3-5", "abcdef")
    @Test fun changedValidatorRestartsEvenWhenSizeMatches() = replacementTransfer("\"changed\"", "bytes=0-5", "uvwxyz")

    private fun replacementTransfer(tag: String, expectedRange: String, body: String) = runBlocking {
        val initial = expired().copy(request = expired().request.copy(url = "https://example.com/new"))
        val file = File(File(initial.request.targetDir, initial.id).apply { mkdirs() }, initial.fileName)
        file.writeText("abcxxx")
        val ranges = mutableListOf<String?>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val head = request.method == "HEAD"
            val returnedRange = if (request.header("Range") == "bytes=0-511") "bytes=0-5" else expectedRange
            if (!head) ranges.add(request.header("Range"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("OK")
                .code(if (head) 200 else 206).header("Accept-Ranges", "bytes").header("ETag", tag)
                .header("Content-Length", if (head) "6" else if (returnedRange == "bytes=3-5") "3" else "6")
                .header("Content-Disposition", "attachment; filename=server.bin")
                .apply { if (!head) header("Content-Range", "bytes ${returnedRange.removePrefix("bytes=")}/6") }
                .body((if (head) "" else if (returnedRange == "bytes=3-5") body.substring(3) else body).toResponseBody()).build()
        }.build()
        try {
            val task = DownloadTask(initial, client, MemoryStore(), segmentCount = 1, publish = { })
            task.run()
            assertEquals(DownloadStatus.COMPLETED, task.state.status)
            assertEquals(listOf("bytes=0-511", expectedRange), ranges)
            assertEquals("chosen.bin", task.state.fileName)
            assertEquals(body, file.readText())
        } finally { close(client) }
    }

    @Test fun frontInsertionDoesNotPreemptActiveTransfer() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method == "HEAD") {
                order.add(request.url.encodedPath)
                if (request.url.encodedPath == "/active") { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Length", "0").header("Accept-Ranges", "bytes").body("".toResponseBody()).build()
        }.build()
        val engine = DownloadEngine(client, MemoryStore(), maxConcurrentDownloads = 1)
        try {
            engine.add(request("active"))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            engine.add(request("waiting"))
            engine.add(request("captured"), front = true)
            assertEquals(listOf("/active"), order.toList())
            release.countDown()
            withTimeout(10_000) { engine.states.first { it.size == 3 && it.all { state -> state.status == DownloadStatus.COMPLETED } } }
            assertEquals(listOf("/active", "/captured", "/waiting"), order.toList())
        } finally { release.countDown(); engine.pauseAll(); close(client) }
    }

    private fun request(path: String) = DownloadRequest("https://example.com/$path", "chosen.bin",
        referrerPageUrl = "https://example.com/page", targetDir = temporaryFolder.root, segmentCount = 1, preserveFileName = true)

    private fun expired() = DownloadState("saved", request("old"), totalBytes = 6,
        segments = listOf(Segment(0, 0, 5, 3)), status = DownloadStatus.FAILED,
        eTag = "\"same\"", acceptsRanges = true, error = DownloadError.LinkExpired())

    private fun close(client: OkHttpClient) { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }

    private class MemoryStore(initial: List<DownloadState> = emptyList()) : DownloadStore {
        private val snapshots = initial.associateBy { it.id }.toMutableMap()
        override suspend fun load(): List<DownloadState> = synchronized(snapshots) { snapshots.values.toList() }
        override suspend fun save(state: DownloadState) { synchronized(snapshots) { snapshots[state.id] = state } }
        override suspend fun delete(id: String) { synchronized(snapshots) { snapshots.remove(id) } }
    }
}