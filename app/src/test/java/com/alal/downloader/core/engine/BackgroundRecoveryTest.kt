package com.alal.downloader.core.engine

import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Offline policy and process recovery tests without Android or real networking. */
class BackgroundRecoveryTest {
    @get:Rule val directory = TemporaryFolder()
    private class Store(val rows: MutableList<DownloadState> = mutableListOf()) : DownloadStore {
        override suspend fun load() = rows.toList()
        override suspend fun save(state: DownloadState) {
            val index = rows.indexOfFirst { it.id == state.id }
            if (index < 0) rows.add(state) else rows[index] = state
        }
        override suspend fun delete(id: String) { rows.removeAll { it.id == id } }
    }
    private fun request() = DownloadRequest("https://example.invalid/file", "file.bin", targetDir = directory.root)

    @Test fun interruptedRestorePreservesOffsetsAndDoesNotSelectManualPauses() = runBlocking {
        val folder = File(directory.root, "active").apply { mkdirs() }
        File(folder, "file.bin").writeBytes(ByteArray(32))
        val active = DownloadState("active", request(), status = DownloadStatus.RUNNING,
            segments = listOf(Segment(0, 0, 63, 32)))
        val store = Store(mutableListOf(active, DownloadState("paused", request(), status = DownloadStatus.PAUSED)))
        val engine = DownloadEngine(OkHttpClient(), store)
        engine.restore()
        assertEquals(listOf("active"), engine.interruptedIds())
        assertEquals(32L, engine.states.value.first().downloadedBytes)
        assertTrue(engine.states.value.all { it.status == DownloadStatus.PAUSED })
        engine.restore()
        assertEquals(32L, store.rows.first().downloadedBytes)
    }

    @Test fun wifiRestrictionChangesWaitingReasonAndPauseAllStopsWaiters() = runBlocking {
        val engine = DownloadEngine(OkHttpClient(), Store())
        engine.setNetworkAllowed(false, wifiRestricted = true)
        val id = engine.add(request())
        assertEquals(DownloadStatus.WAITING_FOR_WIFI, engine.states.value.single().status)
        engine.setNetworkAllowed(false)
        assertEquals(DownloadStatus.WAITING_FOR_NETWORK, engine.states.value.single().status)
        engine.pauseAll()
        engine.setNetworkAllowed(true)
        assertEquals(DownloadStatus.PAUSED, engine.states.value.single().status)
        engine.cancel(id)
    }

    @Test fun connectivityDoesNotRetryExpiredOrForbiddenDownloads() = runBlocking {
        val store = Store(mutableListOf(DownloadState("expired", request(), status = DownloadStatus.FAILED,
            error = DownloadError.LinkExpired()), DownloadState("forbidden", request(), status = DownloadStatus.FAILED,
            error = DownloadError.Forbidden())))
        val engine = DownloadEngine(OkHttpClient(), store)
        engine.restore()
        repeat(3) { engine.setNetworkAllowed(false); engine.setNetworkAllowed(true) }
        assertTrue(engine.states.value.all { it.status == DownloadStatus.FAILED })
        assertTrue(engine.interruptedIds().isEmpty())
    }
}