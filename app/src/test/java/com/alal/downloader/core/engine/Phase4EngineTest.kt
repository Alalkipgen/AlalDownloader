package com.alal.downloader.core.engine

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Queue resizing, deletion ordering and transfer edge cases without Android. */
class Phase4EngineTest {
    @get:Rule val directory = TemporaryFolder()

    private class Store : DownloadStore {
        val states = ConcurrentHashMap<String, DownloadState>()
        override suspend fun load() = states.values.toList()
        override suspend fun save(state: DownloadState) { states[state.id] = state }
        override suspend fun delete(id: String) { states.remove(id) }
    }

    private fun request() = DownloadRequest("https://example.invalid/file", "file.bin", targetDir = directory.root)

    @Test fun queuedDuplicateUrlsHaveIndependentIdsAndDeletion() = runBlocking {
        val store = Store()
        val engine = DownloadEngine(OkHttpClient(), store)
        engine.setNetworkAllowed(false)
        val first = engine.add(request())
        val second = engine.add(request())
        assertNotEquals(first, second)
        engine.delete(first, true)
        assertEquals(listOf(second), engine.states.value.map { it.id })
        assertFalse(store.states.containsKey(first))
    }

    @Test fun providerDeletionFailureKeepsPausedRecord() = runBlocking {
        val store = Store()
        val legacy = FileDownloadStorage()
        val storage = object : DownloadStorage {
            override fun prepare(state: DownloadState) = legacy.prepare(state)
            override fun length(state: DownloadState) = legacy.length(state)
            override fun open(state: DownloadState) = legacy.open(state)
            override fun complete(state: DownloadState) = legacy.complete(state)
            override fun delete(state: DownloadState) { error("Provider denied deletion") }
        }
        val engine = DownloadEngine(OkHttpClient(), store, storage = storage)
        engine.setNetworkAllowed(false)
        val id = engine.add(request())
        try { engine.delete(id, true); fail("Expected provider failure") } catch (_: IllegalStateException) { }
        assertEquals(DownloadStatus.PAUSED, engine.states.value.single().status)
        assertTrue(store.states.containsKey(id))
        assertTrue(engine.states.value.single().error!!.message!!.contains("Deletion failed"))
    }

    @Test fun zeroByteResourceCompletesAndObservableExtraBytesFail() = runBlocking {
        for (zero in listOf(true, false)) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .header("Content-Length", if (zero) "0" else "2")
                    .body((if (zero) "" else "abc").toResponseBody()).build()
            }.build()
            try {
                val state = DownloadState(if (zero) "zero" else "mismatch", request())
                val task = DownloadTask(state, client, Store(), publish = {})
                task.run()
                assertEquals(if (zero) DownloadStatus.COMPLETED else DownloadStatus.FAILED, task.state.status)
                if (zero) assertEquals(0L, File(File(directory.root, state.id), state.fileName).length())
                else assertTrue(task.state.error!!.message!!.contains("Size mismatch"))
            } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
        }
    }

    @Test
    fun decreasingConcurrencyDoesNotPreemptAndIncreasingDrainsQueue() {
        runBlocking {
            val release = CountDownLatch(1)
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                check(release.await(5, TimeUnit.SECONDS))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .header("Content-Length", "0").header("Accept-Ranges", "bytes").body("".toResponseBody()).build()
            }.build()
            val engine = DownloadEngine(client, Store(), maxConcurrentDownloads = 2)
            try {
                repeat(3) { engine.add(request()) }
                withTimeout(3000) { engine.states.first { list -> list.count { it.status == DownloadStatus.RUNNING } == 2 } }
                engine.configure(8, 1, 0)
                assertEquals(2, engine.states.value.count { it.status == DownloadStatus.RUNNING })
                assertEquals(1, engine.states.value.count { it.status == DownloadStatus.QUEUED })
                engine.configure(8, 3, 0)
                withTimeout(3000) { engine.states.first { list -> list.count { it.status == DownloadStatus.RUNNING } == 3 } }
                release.countDown()
                withTimeout(3000) { engine.states.first { list -> list.all { it.status == DownloadStatus.COMPLETED } } }
            } finally { release.countDown(); engine.pauseAll(); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
        }
    }

    @Test fun cancelledPauseRetainsCapacityUntilWorkerCheckpointFinishes() = runBlocking {
        val checkpointStarted = CompletableDeferred<Unit>()
        val releaseCheckpoint = CompletableDeferred<Unit>()
        val states = ConcurrentHashMap<String, DownloadState>()
        val store = object : DownloadStore {
            override suspend fun load() = states.values.toList()
            override suspend fun save(state: DownloadState) {
                if (state.status == DownloadStatus.PAUSED) {
                    checkpointStarted.complete(Unit)
                    releaseCheckpoint.await()
                }
                states[state.id] = state
            }
            override suspend fun delete(id: String) { states.remove(id) }
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Length", "65536").body(ByteArray(65536).toResponseBody()).build()
        }.build()
        val engine = DownloadEngine(client, store, maxConcurrentDownloads = 1)
        try {
            engine.configure(1, 1, 1)
            val first = engine.add(request())
            withTimeout(3000) { engine.states.first { list -> list.any { it.id == first && it.status == DownloadStatus.RUNNING } } }
            val pause = launch { engine.pause(first) }
            withTimeout(3000) { checkpointStarted.await() }
            pause.cancelAndJoin()
            val second = engine.add(request())
            assertEquals(DownloadStatus.QUEUED, engine.states.value.single { it.id == second }.status)
            releaseCheckpoint.complete(Unit)
            withTimeout(3000) { engine.states.first { list -> list.any { it.id == second && it.status == DownloadStatus.RUNNING } } }
            assertEquals(DownloadStatus.PAUSED, engine.states.value.single { it.id == first }.status)
        } finally {
            releaseCheckpoint.complete(Unit)
            engine.pauseAll()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test fun deletionWaitsForOutputCloseAndNoCheckpointResurrectsRecord() = runBlocking {
        val opened = CompletableDeferred<Unit>()
        var closed = false
        val legacy = FileDownloadStorage()
        val storage = object : DownloadStorage {
            override fun prepare(state: DownloadState) = legacy.prepare(state)
            override fun length(state: DownloadState) = legacy.length(state)
            override fun complete(state: DownloadState) = legacy.complete(state)
            override fun open(state: DownloadState): DownloadOutput {
                val output = legacy.open(state)
                opened.complete(Unit)
                return object : DownloadOutput {
                    override fun write(position: Long, bytes: ByteArray, count: Int) = output.write(position, bytes, count)
                    override fun resize(length: Long) = output.resize(length)
                    override fun sync() = output.sync()
                    override fun close() { output.close(); closed = true }
                }
            }
            override fun delete(state: DownloadState) { assertTrue(closed); legacy.delete(state) }
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Length", "65536").body(ByteArray(65536).toResponseBody()).build()
        }.build()
        val store = Store()
        val engine = DownloadEngine(client, store, storage = storage)
        try {
            engine.configure(1, 1, 1)
            val id = engine.add(request())
            withTimeout(3000) { opened.await() }
            withTimeout(3000) { engine.delete(id, true) }
            assertTrue(engine.states.value.isEmpty())
            assertFalse(store.states.containsKey(id))
            assertFalse(File(File(directory.root, id), "file.bin").exists())
        } finally { engine.pauseAll(); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }
}