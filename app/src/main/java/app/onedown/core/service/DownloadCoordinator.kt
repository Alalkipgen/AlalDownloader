package app.onedown.core.service

import android.content.Context
import android.content.Intent
import android.os.Build
import app.onedown.core.data.DownloadSettings
import app.onedown.core.engine.DownloadEngine
import app.onedown.core.engine.DownloadRequest
import app.onedown.core.engine.DownloadState
import app.onedown.core.engine.DownloadStatus
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

    private suspend fun protect() {
        if (!accepting && serviceRunning) error("Download service is stopping; try again shortly")
        if (!serviceRunning) {
            engine.pauseAll()
            engine.restore()
            withContext(Dispatchers.Main.immediate) { notifications.seed(engine.states.value) }
            ready = CompletableDeferred()
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            withTimeout(8_000) { ready.await() }
        }
        check(accepting) { "Background time limit reached; reopen the app to resume" }
        engine.configure(settings.segments.value, settings.concurrent.value, settings.speed.value * 1024)
    }

    suspend fun add(request: DownloadRequest, front: Boolean = false) = gate.withLock {
        protect()
        engine.restore()
        engine.setNetworkAllowed(network.allowed(settings.wifiOnly.value))
        engine.add(request, front)
    }

    suspend fun replaceLink(id: String, url: String, headers: Map<String, String>) = gate.withLock {
        protect()
        engine.restore()
        engine.setNetworkAllowed(network.allowed(settings.wifiOnly.value))
        engine.replaceLink(id, url, headers)
    }

    suspend fun resume(id: String) = gate.withLock {
        protect()
        engine.restore()
        engine.setNetworkAllowed(network.allowed(settings.wifiOnly.value))
        engine.resume(id)
    }

    suspend fun pause(id: String) = gate.withLock {
        engine.restore()
        engine.pause(id)
        refreshIfStopped()
    }

    suspend fun cancel(id: String) = gate.withLock {
        engine.restore()
        engine.cancel(id)
        refreshIfStopped()
    }

    suspend fun delete(id: String, deleteFile: Boolean) = gate.withLock {
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
        if (serviceRunning && accepting) engine.setNetworkAllowed(network.allowed(settings.wifiOnly.value))
    }

    suspend fun stopIfIdle(observed: List<DownloadState>, stop: () -> Unit) = gate.withLock {
        val busy = engine.states.value.any {
            it.status in setOf(DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.WAITING_FOR_NETWORK)
        }
        if (!busy && engine.states.value == observed) {
            accepting = false
            withContext(Dispatchers.Main.immediate) { stop() }
        }
    }

    suspend fun shutdown(token: Any) = gate.withLock {
        if (owner === token && !accepting) {
            accepting = false
            engine.pauseAll()
            withContext(Dispatchers.Main.immediate) {
                notifications.update(engine.states.value, showSummary = false)
            }
        }
    }
}