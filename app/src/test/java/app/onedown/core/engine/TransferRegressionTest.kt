package app.onedown.core.engine

import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Regression checks using in-process responses without external network access. */
class TransferRegressionTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun fullProbeResponseOverridesAdvertisedRangeSupport() = runBlocking {
        val methods = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            methods.add(chain.request().method)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").header("Accept-Ranges", "bytes")
                .apply {
                    if (chain.request().method == "GET") header("Content-Length", "3")
                }
                .body("abc".toResponseBody()).build()
        }.build()
        try {
            val result = RangeProbe(client).probe(request())
            assertEquals(listOf("HEAD", "GET"), methods)
            assertEquals(3L, result.totalBytes)
            assertFalse(result.acceptsRanges)
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test fun fullTransferDoesNotTrustAnExistingCompleteByteCount() = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("new".toResponseBody()).build()
        }.build()
        val file = temporaryFolder.newFile().apply { writeText("old") }
        val progress = mutableListOf<Long>()
        try {
            val downloaded = SegmentDownloader(client).download(
                request(), file, Segment(0, 0, 2, 3), false, 3, null,
            ) { progress.add(it) }
            assertEquals(1, requests)
            assertEquals(3L, downloaded)
            assertEquals("new", file.readText())
            assertEquals(0L, progress.first())
            assertEquals(3L, progress.last())
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test fun failedCheckpointIsNotPublishedAsRunning() = runBlocking {
        val initial = DownloadState("checkpoint-test", request())
        val published = mutableListOf<DownloadState>()
        val store = object : DownloadStore {
            override suspend fun delete(id: String) { throw IllegalStateException("Checkpoint unavailable") }
            override suspend fun load(): List<DownloadState> = emptyList()
            override suspend fun save(state: DownloadState) {
                throw IllegalStateException("Checkpoint unavailable")
            }
        }
        DownloadTask(initial, OkHttpClient(), store, publish = { published.add(it) }).run()
        assertEquals(listOf(DownloadStatus.FAILED), published.map { it.status })
        assertTrue(published.single().error is DownloadError.Unknown)
    }

    private fun request(): DownloadRequest = DownloadRequest(
        url = "https://example.invalid/file", fileName = "file.bin",
        targetDir = File(temporaryFolder.root, "downloads"),
    )
}