package com.alal.downloader.core.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*

/** Persisted, bounded recovery scheduling; platform start restrictions still apply. */
object DownloadRecovery {
    fun schedule(context: Context) {
        val builder = JobInfo.Builder(4107, ComponentName(context, DownloadRecoveryService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setPersisted(true)
        if (Build.VERSION.SDK_INT >= 31) builder.setExpedited(true)
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.schedule(builder.build()) == JobScheduler.RESULT_FAILURE) {
            if (Build.VERSION.SDK_INT >= 31) builder.setExpedited(false)
            scheduler.schedule(builder.build())
        }
    }
}

/** Boot never directly launches a dataSync foreground service. */
class DownloadBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON", "com.htc.intent.action.QUICKBOOT_POWERON")) {
            DownloadRecovery.schedule(context)
        }
    }
}

/** Attempts recovery once; user interaction is required if Android rejects the start. */
@AndroidEntryPoint
class DownloadRecoveryService : JobService() {
    @Inject lateinit var coordinator: DownloadCoordinator
    @Inject lateinit var notifications: DownloadNotifications
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                coordinator.recover(deferOnRestriction = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("DownloadRecovery", "Recovery requires opening the app", failure)
                withContext(Dispatchers.Main) { notifications.recoveryRequired() }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { jobFinished(params, false) }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return true
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }
}