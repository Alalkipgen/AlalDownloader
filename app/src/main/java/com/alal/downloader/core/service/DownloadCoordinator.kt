package com.alal.downloader.core.service

import android.app.ForegroundServiceStartNotAllowedException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.content.Context
import android.content.Intent
import android.os.Build
import com.alal.downloader.core.data.DownloadSettings
import com.alal.downloader.core.engine.DownloadEngine
import com.alal.downloader.core.engine.DownloadRequest
import com.alal.downloader.core.engine.DownloadState
import com.alal.downloader.core.engine.DownloadStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Serializes user commands, foreground protection and network policy. */
@Singleton
class DownloadCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: DownloadEngine,
    private val settings: DownloadSettings,
    private val network: NetworkMonitor,
    private val notifications: DownloadNotifications,
) {
    private val gate = Mutex()
    @Volatile private var ready = CompletableDeferred<Unit>()
    @Volatile private var serviceRunning = false
    @Volatile private var accepting = true
    @Volatile private var owner: Any? = null

    private val mutableInterrupted = MutableStateFlow(false)
    val interruptedWarning = mutableInterrupted.asStateFlow()
    private var initialized = false
    fun dismissInterruptedWarning() { mutableInterrupted.value = false }

    private suspend fun restoreProcess() {
        if (initialized) return
        val wasActive = settings.wasActiveAtShutdown
        val ids = engine.interruptedIds()
        settings.recoveryIds = settings.recoveryIds + ids
        mutableInterrupted.value = wasActive && !serviceRunning
        initialized = true
    }

    suspend fun recover(deferOnRestriction: Boolean = true) = gate.withLock {
        restoreProcess()
        if (!settings.autoResume.value) return@withLock
        val candidates = engine.states.value.filter { it.id in settings.recoveryIds && it.status == DownloadStatus.PAUSED }
        if (candidates.isEmpty()) return@withLock
        protect(deferOnRestriction)
        applyNetwork()
        for (state in candidates) engine.resume(state.id)
        settings.recoveryIds = emptySet()
    }

    suspend fun pauseAll() = gate.withLock {
        restoreProcess()
        settings.recoveryIds = emptySet()
        engine.pauseAll()
        refreshIfStopped()
    }

    private suspend fun applyNetwork() = engine.setNetworkAllowed(
        network.allowed(settings.wifiOnly.value), network.wifiRestricted(settings.wifiOnly.value))

    fun serviceReady(token: Any) {
        owner = token
        serviceRunning = true
        accepting = true
        ready.complete(Unit)
    }

    fun serviceStopped(token: Any) {
        if (owner !== token) return
        serviceRunning = false
        ready = CompletableDeferred()
    }

    fun rejectCommands(token: Any) { if (owner === token) accepting = false }

    fun serviceStartFailed(failure: Exception) {
        ready.completeExceptionally(failure)
    }

    private suspend fun protect(deferOnRestriction: Boolean = true) {
        restoreProcess()
        if (!accepting && serviceRunning) error("Download service is stopping; try again shortly")
        if (!serviceRunning) {
            engine.restore()
            withContext(Dispatchers.Main.immediate) { notifications.seed(engine.states.value) }
            ready = CompletableDeferred()
            val intent = Intent(context, DownloadService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            } catch (failure: IllegalStateException) {
                if (Build.VERSION.SDK_INT >= 31 && failure is ForegroundServiceStartNotAllowedException && deferOnRestriction) {
                    DownloadRecovery.schedule(context)
                }
                throw failure
            }
            withTimeout(8_000) { ready.await() }
        }
        check(accepting) { "Background time limit reached; reopen the app to resume" }
        engine.configure(settings.segments.value, settings.concurrent.value, settings.speed.value * 1024)
    }

    suspend fun add(request: DownloadRequest, front: Boolean = false) = gate.withLock {
        protect()
        engine.restore()
        applyNetwork()
        engine.add(request, front)
    }

    suspend fun replaceLink(id: String, url: String, headers: Map<String, String>) = gate.withLock {
        protect()
        engine.restore()
        applyNetwork()
        engine.replaceLink(id, url, headers)
    }

    suspend fun resume(id: String) = gate.withLock {
        protect()
        engine.restore()
        applyNetwork()
        engine.resume(id)
    }

    suspend fun pause(id: String) = gate.withLock {
        restoreProcess()
        settings.recoveryIds = settings.recoveryIds - id
        engine.pause(id)
        refreshIfStopped()
    }

    suspend fun cancel(id: String) = gate.withLock {
        restoreProcess()
        settings.recoveryIds = settings.recoveryIds - id
        engine.cancel(id)
        refreshIfStopped()
    }

    suspend fun delete(id: String, deleteFile: Boolean) = gate.withLock {
        restoreProcess()
        settings.recoveryIds = settings.recoveryIds - id
        engine.delete(id, deleteFile)
        withContext(Dispatchers.Main.immediate) { notifications.remove(id) }
        refreshIfStopped()
    }

    suspend fun updateTransferSettings() = gate.withLock {
        engine.configure(settings.segments.value, settings.concurrent.value, settings.speed.value * 1024)
    }

    private suspend fun refreshIfStopped() {
        if (!serviceRunning) withContext(Dispatchers.Main.immediate) {
            notifications.seed(engine.states.value)
            notifications.update(engine.states.value, showSummary = false)
        }
    }

    suspend fun updateNetwork() = gate.withLock {
        if (serviceRunning && accepting) applyNetwork()
    }

    suspend fun stopIfIdle(observed: List<DownloadState>, stop: () -> Unit) = gate.withLock {
        val busy = engine.states.value.any {
            it.status in setOf(DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.WAITING_FOR_NETWORK, DownloadStatus.WAITING_FOR_WIFI)
        }
        if (!busy && engine.states.value == observed) {
            accepting = false
            withContext(Dispatchers.Main.immediate) { stop() }
        }
    }

    suspend fun shutdown(token: Any) = gate.withLock {
        if (owner === token && !accepting) {
            accepting = false
            settings.recoveryIds = settings.recoveryIds + engine.states.value.filter {
                it.status in setOf(DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.WAITING_FOR_NETWORK, DownloadStatus.WAITING_FOR_WIFI)
            }.map { it.id }
            engine.pauseAll()
            withContext(Dispatchers.Main.immediate) {
                notifications.update(engine.states.value, showSummary = false)
            }
        }
    }
}