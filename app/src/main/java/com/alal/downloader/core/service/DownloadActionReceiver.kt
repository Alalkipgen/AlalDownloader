package com.alal.downloader.core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.util.Log

/** Handles explicit notification commands without opening an activity. */
@AndroidEntryPoint
class DownloadActionReceiver : BroadcastReceiver() {
    @Inject lateinit var coordinator: DownloadCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("download_id") ?: return
        val action = intent.action ?: return
        if (action !in setOf("pause", "resume", "cancel", "pause_all")) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (action) {
                    "pause_all" -> coordinator.pauseAll()
                    "pause" -> coordinator.pause(id)
                    "resume" -> coordinator.resume(id)
                    "cancel" -> coordinator.cancel(id)
                }
            } catch (failure: Exception) {
                Log.e("DownloadAction", "Notification action failed", failure)
            } finally {
                pending.finish()
            }
        }
    }
}