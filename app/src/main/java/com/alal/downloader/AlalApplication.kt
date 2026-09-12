package com.alal.downloader

import android.app.Application
import android.util.Log
import com.alal.downloader.core.service.DownloadCoordinator
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.*

/** Restores checkpointed downloads independently of activity lifetime. */
@HiltAndroidApp
class AlalApplication : Application() {
    @Inject lateinit var coordinator: DownloadCoordinator
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    override fun onCreate() {
        super.onCreate()
        
        // Install global exception handler for crash-proofing
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                logCrash(throwable)
            } catch (e: Exception) {
                Log.e("AlalCrashHandler", "Failed to log crash", e)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
        
        scope.launch {
            try { coordinator.recover() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { Log.w("AlalRecovery", "Background recovery deferred", failure) }
        }
    }
    
    private fun logCrash(throwable: Throwable) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val crashFile = File(filesDir, "crash_$timestamp.txt")
            
            val writer = StringWriter()
            val printer = PrintWriter(writer)
            throwable.printStackTrace(printer)
            
            crashFile.writeText("""
                Alal Downloader Crash Report
                Time: $timestamp
                
                ${writer.toString()}
            """.trimIndent())
            
            Log.e("AlalCrash", "Crash logged to ${crashFile.absolutePath}", throwable)
        } catch (e: Exception) {
            Log.e("AlalCrashHandler", "Could not write crash log", e)
        }
    }
}
