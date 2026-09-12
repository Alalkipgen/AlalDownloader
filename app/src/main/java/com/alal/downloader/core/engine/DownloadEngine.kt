package com.alal.downloader.core.engine

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Owns a priority-aware transfer queue and exposes snapshots through StateFlow. */
class DownloadEngine(
    private val client: OkHttpClient,
    private val store: DownloadStore,
    maxConcurrentDownloads: Int = 3,
    private var segmentCount: Int = 8,
    private val connectionsPerDownload: Int = 8,
    private val storage: DownloadStorage = FileDownloadStorage(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private var capacity = maxConcurrentDownloads.also { require(it in 1..10) }
    private val limiter = TransferLimiter()
    private val pending = java.util.ArrayDeque<String>()
    private val jobs = mutableMapOf<String, Job>()
    private var restored = false
    private var networkAllowed = true
    private val mutableStates = MutableStateFlow<List<DownloadState>>(emptyList())
    val states: StateFlow<List<DownloadState>> = mutableStates.asStateFlow()

    init {
        require(segmentCount in 1..32 && connectionsPerDownload in 1..32)
    }

    suspend fun restore() = gate.withLock { restoreLocked() }

    suspend fun configure(segments: Int, concurrent: Int, bytesPerSecond: Long) = gate.withLock {
        require(segments in 1..32 && concurrent in 1..10 && bytesPerSecond >= 0)
        segmentCount = segments
        capacity = concurrent
        limiter.setLimit(bytesPerSecond)
        drain()
    }

    suspend fun delete(id: String, deleteFile: Boolean) = gate.withLock {
        restoreLocked()
        stopLocked(id, DownloadStatus.PAUSED)
        val current = mutableStates.value.find { it.id == id } ?: return@withLock
        try {
            if (deleteFile) storage.delete(current) else storage.complete(current)
            store.delete(id)
            mutableStates.update { list -> list.filterNot { it.id == id } }
        } catch (failure: Exception) {
            val retained = current.copy(error = DownloadError.Unknown("Deletion failed: ${failure.message}"), speedBytesPerSecond = 0)
            publish(retained)
            store.save(retained)
            throw failure
        } finally { drain() }
    }

    private suspend fun restoreLocked() {
        if (restored) return
        val saved = store.load().map {
            if (it.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELLED)) it else
                try {
                    it.copy(status = DownloadStatus.PAUSED, speedBytesPerSecond = 0,
                        segments = recoverSegments(it.segments, storage.length(it)))
                } catch (failure: Exception) {
                    it.copy(status = DownloadStatus.PAUSED, speedBytesPerSecond = 0,
                        error = DownloadError.Unknown("Destination unavailable: ${failure.message}"))
                }
        }
        saved.forEach { store.save(it) }
        mutableStates.value = saved
        restored = true
    }

    suspend fun add(request: DownloadRequest, front: Boolean = false): String = gate.withLock {
        restoreLocked()
        require(request.url.toHttpUrlOrNull() != null) { "Enter a valid HTTP or HTTPS URL" }
        require(request.segmentCount == null || request.segmentCount in 1..32) { "Segment count must be 1..32" }
        require(request.headers.keys.none { it.equals("Range", true) || it.equals("If-Range", true) }) {
            "Range and If-Range headers are managed by the engine"
        }
        val snapshot = DownloadState(UUID.randomUUID().toString(), request.copy(headers = request.headers.toMap()),
            status = if (networkAllowed) DownloadStatus.QUEUED else DownloadStatus.WAITING_FOR_NETWORK)
        store.save(snapshot)
        mutableStates.update { it + snapshot }
        if (networkAllowed) enqueue(snapshot, front)
        snapshot.id
    }

    suspend fun pause(id: String) = stop(id, DownloadStatus.PAUSED)

    suspend fun cancel(id: String) = stop(id, DownloadStatus.CANCELLED)

    private suspend fun stop(id: String, status: DownloadStatus) = gate.withLock {
        stopLocked(id, status)
        drain()
    }

    private suspend fun stopLocked(id: String, status: DownloadStatus) {
        pending.remove(id)
        joinStoppedJob(id)
        val current = mutableStates.value.find { it.id == id } ?: return
        if (current.status == DownloadStatus.COMPLETED) return
        val next = current.copy(status = status, speedBytesPerSecond = 0)
        store.save(next)
        publish(next)
    }

    private suspend fun joinStoppedJob(id: String) {
        val job = jobs[id] ?: return
        job.cancelAndJoin()
        if (jobs[id] === job) jobs.remove(id)
    }

    suspend fun resume(id: String) = gate.withLock {
        restoreLocked()
        resumeLocked(id)
    }

    suspend fun replaceLink(id: String, url: String, headers: Map<String, String>) = gate.withLock {
        restoreLocked()
        require(url.toHttpUrlOrNull() != null) { "Invalid replacement URL" }
        require(headers.keys.none { it.equals("Range", true) || it.equals("If-Range", true) })
        val current = mutableStates.value.find { it.id == id } ?: error("Download no longer exists")
        check(current.canRefreshLink()) { "Download is not waiting for a replacement link" }
        pending.remove(id)
        joinStoppedJob(id)
        val next = current.copy(
            request = current.request.copy(url = url, headers = headers.toMap()), finalUrl = url,
            status = if (networkAllowed) DownloadStatus.QUEUED else DownloadStatus.WAITING_FOR_NETWORK,
            error = null, speedBytesPerSecond = 0,
        )
        store.save(next)
        publish(next)
        if (networkAllowed) enqueue(next, front = true)
    }

    private suspend fun resumeLocked(id: String) {
        val current = mutableStates.value.find { it.id == id } ?: return
        if (current.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.RUNNING, DownloadStatus.QUEUED)) return
        joinStoppedJob(id)
        val next = current.copy(status = if (networkAllowed) DownloadStatus.QUEUED else DownloadStatus.WAITING_FOR_NETWORK, error = null)
        store.save(next)
        publish(next)
        if (networkAllowed) enqueue(next)
    }

    suspend fun setNetworkAllowed(allowed: Boolean) = gate.withLock {
        networkAllowed = allowed
        if (!allowed) {
            mutableStates.value.filter { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED }
                .forEach { stopLocked(it.id, DownloadStatus.WAITING_FOR_NETWORK) }
        } else {
            mutableStates.value.filter { it.status == DownloadStatus.WAITING_FOR_NETWORK }
                .forEach { resumeLocked(it.id) }
        }
    }

    suspend fun pauseAll() = gate.withLock {
        mutableStates.value.filter { it.status in setOf(DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.WAITING_FOR_NETWORK) }
            .forEach { stopLocked(it.id, DownloadStatus.PAUSED) }
    }

    private fun enqueue(state: DownloadState, front: Boolean = false) {
        pending.remove(state.id)
        if (front) pending.addFirst(state.id) else pending.addLast(state.id)
        drain()
    }

    private fun drain() {
        while (networkAllowed && jobs.size < capacity && pending.isNotEmpty()) {
            val id = pending.removeFirst()
            val state = mutableStates.value.find { it.id == id && it.status == DownloadStatus.QUEUED } ?: continue
            start(state)
        }
    }

    private fun start(state: DownloadState) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                DownloadTask(state, client, store, state.request.segmentCount ?: segmentCount,
                    connectionsPerDownload, ::publish, storage, limiter).run()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val latest = mutableStates.value.find { it.id == state.id } ?: state
                publish(latest.copy(status = DownloadStatus.FAILED, error = failure.asDownloadError(), speedBytesPerSecond = 0))
            }
        }
        jobs[state.id] = job
        job.invokeOnCompletion {
            scope.launch {
                gate.withLock {
                    if (jobs[state.id] === job) jobs.remove(state.id)
                    drain()
                }
            }
        }
        job.start()
    }

    private fun publish(state: DownloadState) {
        mutableStates.update { list -> list.map { if (it.id == state.id) state else it } }
    }
}