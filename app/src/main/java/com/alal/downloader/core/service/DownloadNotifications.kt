package com.alal.downloader.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.alal.downloader.core.engine.canRefreshLink
import com.alal.downloader.core.engine.DownloadState
import com.alal.downloader.core.engine.DownloadStatus
import com.alal.downloader.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Grouped progress and completion notifications with explicit immutable actions. */
@Singleton
class DownloadNotifications @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val previous = mutableMapOf<String, DownloadStatus>()

    fun seed(states: List<DownloadState>) {
        states.forEach { previous.putIfAbsent(it.id, it.status) }
    }

    fun remove(id: String) {
        manager.cancel(id, CHILD_ID)
        previous.remove(id)
    }

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
            manager.createNotificationChannel(NotificationChannel(COMPLETE, "Completed downloads", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun downloadsIntent() = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra("downloads", true)

    fun recoveryRequired() {
        try {
            manager.notify(3, builder(COMPLETE).setContentTitle("Resume interrupted downloads")
                .setContentText("Android restricted background startup. Tap to open Alal and resume.").setAutoCancel(true).build())
        } catch (_: SecurityException) { }
    }

    private fun activityAction(id: String, key: String, title: String): Notification.Action {
        val intent = downloadsIntent().putExtra(key, id)
            .setData(Uri.Builder().scheme("alal").authority(key).appendPath(id).build())
        val pending = PendingIntent.getActivity(context, (key + id).hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(null as android.graphics.drawable.Icon?, title, pending).build()
    }

    private fun builder(channel: String): Notification.Builder =
        (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, channel) else Notification.Builder(context))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(PendingIntent.getActivity(context, 0, downloadsIntent(),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setGroup(if (channel == PROGRESS) "downloads" else "completed_downloads")
            .setOnlyAlertOnce(channel == PROGRESS)
            .setShowWhen(false)

    fun summary(states: List<DownloadState>): Notification {
        val active = states.filter { it.status in setOf(DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.WAITING_FOR_NETWORK, DownloadStatus.WAITING_FOR_WIFI) }
        val total = active.sumOf { it.totalBytes.coerceAtLeast(0).toDouble() }
        val done = active.sumOf { it.downloadedBytes.toDouble() }
        val unknown = active.isEmpty() || active.any { it.totalBytes < 0 }
        val percent = if (total > 0) (done / total * 100).toInt().coerceIn(0, 100) else 0
        val waiting = active.isNotEmpty() && active.all { it.status in setOf(DownloadStatus.WAITING_FOR_NETWORK, DownloadStatus.WAITING_FOR_WIFI) }
        val notification = builder(PROGRESS).setContentTitle("Alal · ${active.size} downloads")
            .setContentText("${active.count { it.status == DownloadStatus.RUNNING }} downloading · ${active.count { it.status != DownloadStatus.RUNNING }} waiting · ${active.sumOf { it.speedBytesPerSecond } / 1024} KiB/s")
            .addAction(action("all", "pause_all", "Pause all"))
            .setProgress(100, percent, unknown && !waiting)
            .setOngoing(true).setGroupSummary(true)
        if (Build.VERSION.SDK_INT >= 26) notification.setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
        return notification.build()
    }

    fun update(states: List<DownloadState>, showSummary: Boolean = true) {
        states.forEach { state ->
            val before = previous[state.id]
            if (state.status == DownloadStatus.COMPLETED && before == DownloadStatus.COMPLETED) return@forEach
            val completed = state.status == DownloadStatus.COMPLETED
            val notification = builder(if (completed) COMPLETE else PROGRESS)
                .setContentTitle(state.fileName)
                .setOngoing(state.status == DownloadStatus.RUNNING || state.status == DownloadStatus.QUEUED)
                .setAutoCancel(completed)
            val percent = if (state.totalBytes > 0) (state.downloadedBytes.toDouble() / state.totalBytes * 100).toInt().coerceIn(0, 100) else 0
            if (completed) {
                notification.setSmallIcon(android.R.drawable.stat_sys_download_done).setContentText("Download complete")
                    .addAction(activityAction(state.id, "open_download", "Open"))
            } else {
                notification.setContentText("${if (state.totalBytes >= 0) "$percent%" else "Unknown size"} · ${state.speedBytesPerSecond / 1024} KiB/s · ${state.status}")
                    .setProgress(100, percent, state.totalBytes < 0 && state.status == DownloadStatus.RUNNING)
                if (state.status in setOf(DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.WAITING_FOR_NETWORK, DownloadStatus.WAITING_FOR_WIFI)) {
                    notification.addAction(action(state.id, "pause", "Pause"))
                } else if (state.status == DownloadStatus.NEEDS_BROWSER) {
                    notification.addAction(activityAction(state.id, "reopen_download", "Open in browser"))
                } else if (state.canRefreshLink()) {
                    notification.addAction(activityAction(state.id, "reopen_download", "Reopen page"))
                } else {
                    notification.addAction(action(state.id, "resume", "Resume"))
                }
                if (state.status != DownloadStatus.CANCELLED) notification.addAction(action(state.id, "cancel", "Cancel"))
            }
            try {
                manager.notify(state.id, CHILD_ID, notification.build())
                previous[state.id] = state.status
            } catch (failure: SecurityException) {
                Log.w("DownloadNotifications", "Notification permission unavailable", failure)
            }
        }
        if (showSummary) {
            try {
                manager.notify(SUMMARY_ID, summary(states))
            } catch (failure: SecurityException) {
                Log.w("DownloadNotifications", "Notification permission unavailable", failure)
            }
        }
    }

    private fun action(id: String, action: String, title: String): Notification.Action {
        val intent = Intent(context, DownloadActionReceiver::class.java).setAction(action)
            .setData(Uri.Builder().scheme("alal").authority("action").appendPath(id).appendPath(action).build())
            .putExtra("download_id", id)
        val pending = PendingIntent.getBroadcast(context, (id + action).hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(null as android.graphics.drawable.Icon?, title, pending).build()
    }

    companion object {
        const val SUMMARY_ID = 1
        private const val CHILD_ID = 2
        private const val PROGRESS = "download_progress"
        private const val COMPLETE = "download_complete"
    }
}