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
    private var activeCounter = 0
    private val activeTasks = linkedSetOf<String>()
    private var restored = false
    private var networkAllowed = true
    private var onWifi = true
    private var waitingStatus = DownloadStatus.WAITING_FOR_NETWORK
    private val interrupted = linkedSetOf<String>()

    suspend fun interruptedIds(): List<String> = gate.withLock { restoreLocked(); interrupted.toList() }
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
        val loaded = store.load()
        interrupted.addAll(loaded.filter { it.status in setOf(DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.WAITING_FOR_NETWORK, DownloadStatus.WAITING_FOR_WIFI) }.map { it.id })
        val saved = loaded.map {
            if (it.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELLED, DownloadStatus.NEEDS_BROWSER)) it else
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
            status = if (networkAllowed) DownloadStatus.QUEUED else waitingStatus, totalBytes = request.contentLength)
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
        interrupted.remove(id)
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
        interrupted.remove(id)
        joinStoppedJob(id)
        val next = current.copy(
            request = current.request.copy(url = url, headers = headers.toMap(),
                cookies = headers.entries.find { it.key.equals("Cookie", true) }?.value,
                referer = headers.entries.find { it.key.equals("Referer", true) }?.value ?: current.request.referrerPageUrl,
                userAgent = headers.entries.find { it.key.equals("User-Agent", true) }?.value), finalUrl = url,
            status = if (networkAllowed) DownloadStatus.QUEUED else waitingStatus,
            error = null, speedBytesPerSecond = 0,
        )
        store.save(next)
        publish(next)
        if (networkAllowed) enqueue(next, front = true)
    }

    private suspend fun resumeLocked(id: String) {
        val current = mutableStates.value.find { it.id == id } ?: return
        if (current.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.NEEDS_BROWSER)) return
        joinStoppedJob(id)
        val next = current.copy(status = if (networkAllowed) DownloadStatus.QUEUED else waitingStatus, error = null)
        store.save(next)
        publish(next)
        if (networkAllowed) enqueue(next)
    }

    suspend fun setNetworkAllowed(allowed: Boolean, wifiRestricted: Boolean = false, wifi: Boolean = true) = gate.withLock {
        val previouslyAllowed = networkAllowed
        networkAllowed = allowed
        onWifi = wifi
        waitingStatus = if (wifiRestricted) DownloadStatus.WAITING_FOR_WIFI else DownloadStatus.WAITING_FOR_NETWORK
        val waiting = setOf(DownloadStatus.WAITING_FOR_NETWORK, DownloadStatus.WAITING_FOR_WIFI)
        if (!allowed) {
            val eligible = mutableStates.value.filter { it.status in waiting || it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED }.map { it.id }.toSet()
            val order = (jobs.keys.toList() + pending.toList() + eligible).filter { it in eligible }
            for (id in order.distinct()) stopLocked(id, waitingStatus)
            pending.clear()
            pending.addAll(order.distinct())
        } else if (!previouslyAllowed) {
            val order = pending.toList() + mutableStates.value.filter { it.status in waiting }.map { it.id }
            pending.clear()
            for (id in order.distinct()) resumeLocked(id)
        }
        if (allowed) {
            val restricted = mutableStates.value.filter { it.request.wifiOnly && !onWifi &&
                it.status in setOf(DownloadStatus.RUNNING, DownloadStatus.QUEUED) }
            for (state in restricted) stopLocked(state.id, DownloadStatus.WAITING_FOR_WIFI)
            if (onWifi) {
                for (state in mutableStates.value.filter { it.status == DownloadStatus.WAITING_FOR_WIFI }) resumeLocked(state.id)
            }
            drain()
        }
    }

    suspend fun pauseAll() = gate.withLock {
        mutableStates.value.filter { it.status in setOf(DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.WAITING_FOR_NETWORK, DownloadStatus.WAITING_FOR_WIFI) }
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
            if (activeCounter >= capacity) {
                pending.addFirst(id)
                break
            }
            val state = mutableStates.value.find { it.id == id && it.status == DownloadStatus.QUEUED } ?: continue
            activeCounter++
            start(state)
        }
    }

    private fun start(state: DownloadState) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (state.request.wifiOnly && !onWifi) {
                    val waiting = state.copy(status = DownloadStatus.WAITING_FOR_WIFI)
                    store.save(waiting)
                    publish(waiting)
                    return@launch
                }
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
                    activeCounter = (activeCounter - 1).coerceAtLeast(0)
                    activeTasks.remove(state.id)
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