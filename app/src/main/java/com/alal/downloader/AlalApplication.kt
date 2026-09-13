package com.alal.downloader

import android.app.Application
import android.util.Log
import com.alal.downloader.core.service.DownloadCoordinator
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.*

/** Restores checkpointed downloads independently of activity lifetime. */
@HiltAndroidApp
class AlalApplication : Application() {
    @Inject lateinit var coordinator: DownloadCoordinator
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    override fun onCreate() {
        CrashLog.install(this)
        super.onCreate()
        
        scope.launch {
            try { coordinator.recover() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { Log.w("AlalRecovery", "Background recovery deferred", failure) }
        }
    }
    
}
