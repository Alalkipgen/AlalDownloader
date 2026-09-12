package app.onedown.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import app.onedown.core.engine.DownloadState
import app.onedown.core.engine.DownloadStatus
import app.onedown.ui.MainActivity
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
            manager.createNotificationChannel(NotificationChannel(PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(COMPLETE, "Completed downloads", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun builder(channel: String): Notification.Builder =
        (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, channel) else Notification.Builder(context))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setGroup(if (channel == PROGRESS) "downloads" else "completed_downloads")
            .setOnlyAlertOnce(channel == PROGRESS)
            .setShowWhen(false)

    fun summary(states: List<DownloadState>): Notification {
        val active = states.filter { it.status in setOf(DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.WAITING_FOR_NETWORK) }
        val total = active.sumOf { it.totalBytes.coerceAtLeast(0).toDouble() }
        val done = active.sumOf { it.downloadedBytes.toDouble() }
        val unknown = active.isEmpty() || active.any { it.totalBytes < 0 }
        val percent = if (total > 0) (done / total * 100).toInt().coerceIn(0, 100) else 0
        val waiting = active.isNotEmpty() && active.all { it.status == DownloadStatus.WAITING_FOR_NETWORK }
        val notification = builder(PROGRESS).setContentTitle("OneDown · ${active.size} downloads")
            .setContentText(if (waiting) "Waiting for an allowed network" else if (unknown) "Downloading" else "$percent% downloaded")
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
            } else {
                notification.setContentText("${if (state.totalBytes >= 0) "$percent%" else "Unknown size"} · ${state.speedBytesPerSecond / 1024} KiB/s · ${state.status}")
                    .setProgress(100, percent, state.totalBytes < 0 && state.status == DownloadStatus.RUNNING)
                if (state.status in setOf(DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.WAITING_FOR_NETWORK)) {
                    notification.addAction(action(state.id, "pause", "Pause"))
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
            .setData(Uri.Builder().scheme("onedown").authority("action").appendPath(id).appendPath(action).build())
            .putExtra("download_id", id)
        val pending = PendingIntent.getBroadcast(context, 0, intent,
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