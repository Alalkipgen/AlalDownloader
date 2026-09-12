package app.onedown.core.engine

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Recovery, positional storage and network-policy regressions without Android dependencies. */
class RecoveryPolicyTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun missingFileResetsEveryCheckpoint() {
        assertEquals(listOf(0L, 0L), recoverSegments(segments(), null).map { it.downloaded })
    }

    @Test fun truncatedFileResetsOnlyUncoveredSegments() {
        assertEquals(listOf(4L, 0L), recoverSegments(segments(), 11).map { it.downloaded })
    }

    @Test fun exactPersistedExtentIsValid() {
        assertEquals(segments(), recoverSegments(segments(), 12))
    }

    @Test fun preallocatedFilePreservesCheckpoints() {
        assertEquals(segments(), recoverSegments(segments(), 20))
    }

    @Test fun invalidSegmentByteCountIsReset() {
        assertEquals(0L, recoverSegments(listOf(Segment(0, 0, 3, 5)), 20).single().downloaded)
    }

    @Test fun positionalWritesDoNotOverwriteOtherSegments() {
        val file = directory.newFile()
        FileDownloadOutput(file).use {
            it.resize(6)
            it.write(3, byteArrayOf(4, 5, 6), 3)
            it.write(0, byteArrayOf(1, 2, 3), 3)
            it.sync()
        }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), file.readBytes())
    }

    @Test fun restartPausesActiveTransferAndRepairsTruncation() = runBlocking {
        val state = DownloadState("restore", request(), segments = segments(), status = DownloadStatus.RUNNING)
        val file = File(File(directory.root, state.id), state.fileName)
        file.parentFile!!.mkdirs()
        file.writeBytes(ByteArray(11))
        val store = MemoryStore(listOf(state))
        val engine = DownloadEngine(OkHttpClient(), store)
        engine.restore()
        assertEquals(DownloadStatus.PAUSED, engine.states.value.single().status)
        assertEquals(listOf(4L, 0L), engine.states.value.single().segments.map { it.downloaded })
        assertEquals(engine.states.value.single(), store.saved[state.id])
    }

    @Test fun blockedResumeWaitsAndManualPausePreventsAutoResume() = runBlocking {
        val engine = DownloadEngine(OkHttpClient(), MemoryStore())
        engine.setNetworkAllowed(false)
        val id = engine.add(request())
        assertEquals(DownloadStatus.WAITING_FOR_NETWORK, engine.states.value.single().status)
        engine.pause(id)
        engine.setNetworkAllowed(true)
        assertEquals(DownloadStatus.PAUSED, engine.states.value.single().status)
        engine.setNetworkAllowed(false)
        engine.resume(id)
        assertEquals(DownloadStatus.WAITING_FOR_NETWORK, engine.states.value.single().status)
        engine.cancel(id)
        engine.setNetworkAllowed(true)
        assertEquals(DownloadStatus.CANCELLED, engine.states.value.single().status)
    }

    @Test fun networkRegainResumesWaitingTransfer() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").header("Content-Length", "3")
                .body("abc".toResponseBody()).build()
        }.build()
        val engine = DownloadEngine(client, MemoryStore())
        try {
            engine.setNetworkAllowed(false)
            engine.add(request())
            engine.setNetworkAllowed(true)
            val completed = withTimeout(5_000) {
                engine.states.first { it.single().status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED) }.single()
            }
            assertEquals(DownloadStatus.COMPLETED, completed.status)
            assertEquals("abc", File(File(directory.root, completed.id), completed.fileName).readText())
        } finally {
            engine.pauseAll()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test fun unavailableDestinationRetainsCheckpointsAndReportsPausedError() = runBlocking {
        val initial = DownloadState("revoked", request(), segments = segments(), status = DownloadStatus.RUNNING)
        val store = MemoryStore(listOf(initial))
        val storage = object : DownloadStorage by FileDownloadStorage() {
            override fun length(state: DownloadState): Long? = throw SecurityException("Grant revoked")
        }
        val engine = DownloadEngine(OkHttpClient(), store, storage = storage)
        engine.restore()
        val restored = engine.states.value.single()
        assertEquals(DownloadStatus.PAUSED, restored.status)
        assertEquals(initial.segments, restored.segments)
        assertTrue(restored.error?.message?.contains("Grant revoked") == true)
        assertEquals(restored, store.saved[initial.id])
    }

    @Test fun shutdownPausesWaitingWorkWithoutChangingManualCancellation() = runBlocking {
        val engine = DownloadEngine(OkHttpClient(), MemoryStore())
        engine.setNetworkAllowed(false)
        val waiting = engine.add(request())
        val cancelled = engine.add(request())
        engine.cancel(cancelled)
        engine.pauseAll()
        engine.setNetworkAllowed(true)
        assertEquals(DownloadStatus.PAUSED, engine.states.value.single { it.id == waiting }.status)
        assertEquals(DownloadStatus.CANCELLED, engine.states.value.single { it.id == cancelled }.status)
    }

    @Test fun destinationAndCheckpointResetAreSavedBeforeTruncation() = runBlocking {
        val initial = DownloadState("reset", request(), totalBytes = 3,
            segments = listOf(Segment(0, 0, 2, 2)), acceptsRanges = true, eTag = "old")
        val store = MemoryStore(listOf(initial))
        val legacy = FileDownloadStorage()
        legacy.prepare(initial)
        legacy.open(initial).use { it.write(0, "old".toByteArray(), 3); it.sync() }
        var truncated = false
        var published = false
        val storage = object : DownloadStorage by legacy {
            override fun prepare(state: DownloadState) = legacy.prepare(state).copy(destinationUri = "test:destination")
            override fun open(state: DownloadState): DownloadOutput {
                assertEquals("test:destination", store.saved[state.id]?.destinationUri)
                val output = legacy.open(state)
                return object : DownloadOutput by output {
                    override fun resize(length: Long) {
                        if (length == 0L) {
                            assertTrue(store.saved[state.id]!!.segments.isEmpty())
                            truncated = true
                        }
                        output.resize(length)
                    }
                }
            }
            override fun complete(state: DownloadState) {
                assertEquals(3L, legacy.length(state))
                assertEquals(3L, store.saved[state.id]?.downloadedBytes)
                assertEquals(DownloadStatus.RUNNING, store.saved[state.id]?.status)
                published = true
            }
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").header("Content-Length", "3")
                .header("ETag", "new").body("new".toResponseBody()).build()
        }.build()
        try {
            val task = DownloadTask(initial, client, store, publish = {}, storage = storage)
            task.run()
            assertEquals(DownloadStatus.COMPLETED, task.state.status)
            assertTrue(truncated)
            assertTrue(published)
            assertEquals("new", File(File(directory.root, initial.id), initial.fileName).readText())
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    private fun segments() = listOf(Segment(0, 0, 9, 4), Segment(1, 10, 19, 2))
    private fun request() = DownloadRequest("https://example.invalid/file", "file.bin", targetDir = directory.root)

    private class MemoryStore(initial: List<DownloadState> = emptyList()) : DownloadStore {
        val saved = initial.associateBy { it.id }.toMutableMap()
        override suspend fun load() = saved.values.toList()
        override suspend fun save(state: DownloadState) { saved[state.id] = state }
        override suspend fun delete(id: String) { saved.remove(id) }
    }
}