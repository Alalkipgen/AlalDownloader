package com.alal.downloader

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/** Persists fatal exceptions privately, always delegating to Android's fatal handler. */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "crash/last_crash.txt")

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            try {
                val target = file(context)
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                target.writeText(buildString {
                    appendLine("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US).format(java.util.Date())}")
                    appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE}; SDK ${Build.VERSION.SDK_INT}")
                    appendLine("Thread: ${thread.name}")
                    appendLine(failure.stackTraceToString())
                })
            } catch (loggingFailure: Exception) {
                Log.e("AlalCrashHandler", "Could not persist fatal exception", loggingFailure)
            } finally {
                if (previous != null) previous.uncaughtException(thread, failure)
                else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                }
            }
        }
    }

    fun read(context: Context): String? = file(context).takeIf(File::isFile)?.readText()
}
