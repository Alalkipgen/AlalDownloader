package com.alal.downloader.core.service

import com.alal.downloader.core.engine.*
import com.alal.downloader.feature.downloads.DownloadPresentation
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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadIntakeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun batchQueuesBeforeProbingAndRejectsHtmlWithoutCreatingOutput() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val history = Collections.synchronizedList(mutableListOf<DownloadState>())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request.url.host
            if (request.url.host == "example.com" && request.method == "HEAD") {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            val body = if (request.url.host == "www.facebook.com") "<!DOCTYPE html><html>page</html>" else "PKzip"
            val ranged = request.header("Range") != null
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("OK")
                .code(if (ranged) 206 else 200)
                .header("Content-Type", "application/octet-stream")
                .header("Accept-Ranges", "bytes").header("Content-Length", body.length.toString())
                .apply { if (ranged) header("Content-Range", "bytes 0-${body.length - 1}/${body.length}") }
                .body((if (request.method == "HEAD") "" else body).toResponseBody()).build()
        }.build()
        val store = object : DownloadStore {
            override suspend fun load() = emptyList<DownloadState>()
            override suspend fun save(state: DownloadState) { history += state }
            override suspend fun delete(id: String) = Unit
        }
        val prepared = Collections.synchronizedList(mutableListOf<String>())
        val files = FileDownloadStorage()
        val storage = object : DownloadStorage by files {
            override fun prepare(state: DownloadState): DownloadState {
                prepared += state.id
                return files.prepare(state)
            }
        }
        val engine = DownloadEngine(client, store, maxConcurrentDownloads = 1, segmentCount = 1, storage = storage)
        val intake = DownloadIntake { request, front -> engine.add(request, front) }
        try {
            val (urls, invalid) = DownloadPresentation.batch("https://example.com/file.zip\nmagnet:?xt=urn:btih:abc\nwww.facebook.com")
            assertTrue(invalid.isEmpty())
            val ids = mutableListOf<String>()
            var rejected = 0
            for (url in urls) {
                try { ids += intake.add(DownloadRequest(url, "", targetDir = temporary.root, retryOnFailure = false)) }
                catch (unsupported: IllegalArgumentException) {
                    assertEquals("Torrent not supported yet", unsupported.message)
                    rejected++
                }
            }
            assertEquals(1, rejected)
            assertEquals(2, ids.size)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("example.com"), requests.toList())
            assertEquals(DownloadStatus.QUEUED, engine.states.value.single { it.id == ids[1] }.status)
            for (id in ids) assertEquals(DownloadStatus.QUEUED, history.first { it.id == id }.status)
            release.countDown()
            val result = withTimeout(10_000) {
                engine.states.first { states -> states.size == 2 && states.all {
                    it.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.NEEDS_BROWSER, DownloadStatus.FAILED)
                } }
            }
            assertEquals(listOf(DownloadStatus.COMPLETED, DownloadStatus.NEEDS_BROWSER), result.map { it.status })
            assertEquals("file.zip", result.first().fileName)
            assertFalse(prepared.contains(ids[1]))
            assertFalse(File(temporary.root, ids[1]).exists())
            assertFalse(history.any { it.id == ids[1] && it.status == DownloadStatus.COMPLETED })
        } finally {
            release.countDown()
            engine.pauseAll()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}