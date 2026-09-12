package com.alal.downloader.core.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.alal.downloader.core.data.DownloadSettings
import com.alal.downloader.core.engine.DownloadEngine
import com.alal.downloader.core.engine.DownloadStatus
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Foreground owner of active download sessions, network monitoring and CPU wakefulness. */
@AndroidEntryPoint
class DownloadService : Service() {
    @Inject lateinit var engine: DownloadEngine
    @Inject lateinit var coordinator: DownloadCoordinator
    @Inject lateinit var notifications: DownloadNotifications
    @Inject lateinit var network: NetworkMonitor
    @Inject lateinit var settings: DownloadSettings
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onCreate() {
        super.onCreate()
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Alal:downloads")
            .apply { setReferenceCounted(false) }
        try {
            val notification = notifications.summary(engine.states.value)
            notifications.seed(engine.states.value)
            if (Build.VERSION.SDK_INT >= 29) startForeground(DownloadNotifications.SUMMARY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(DownloadNotifications.SUMMARY_ID, notification)
        } catch (failure: Exception) {
            Log.e("DownloadService", "Foreground start rejected", failure)
            coordinator.serviceStartFailed(failure)
            stopSelf()
            return
        }
        coordinator.serviceReady(this)
        scope.launch(Dispatchers.IO) {
            try {
                combine(network.changes, settings.wifiOnly) { _, _ -> Unit }.collect { coordinator.updateNetwork() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e("DownloadService", "Network monitoring stopped", failure)
                coordinator.rejectCommands(this@DownloadService)
                try {
                    coordinator.shutdown(this@DownloadService)
                } catch (checkpointFailure: Exception) {
                    Log.e("DownloadService", "Network failure checkpoint failed", checkpointFailure)
                } finally {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        scope.launch {
            engine.states.collect { states ->
                val active = states.any { it.status == DownloadStatus.RUNNING }
                if (active && !wakeLock.isHeld) wakeLock.acquire()
                if (!active && wakeLock.isHeld) wakeLock.release()
            }
        }
        scope.launch {
            while (isActive) {
                delay(1_000)
                val snapshot = engine.states.value
                notifications.update(snapshot)
                withContext(Dispatchers.IO) { coordinator.stopIfIdle(snapshot) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        coordinator.rejectCommands(this)
        scope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { coordinator.shutdown(this@DownloadService) } catch (failure: Exception) {
                Log.e("DownloadService", "Timeout checkpoint failed", failure)
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        coordinator.rejectCommands(this)
        scope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        coordinator.serviceStopped(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { coordinator.shutdown(this@DownloadService) } catch (failure: Exception) {
                Log.e("DownloadService", "Shutdown checkpoint failed", failure)
            }
        }
        super.onDestroy()
    }
}