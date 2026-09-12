package com.alal.downloader.core.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Safe battery and manufacturer settings shortcuts; autostart grants cannot be queried. */
@Singleton
class BackgroundAccess @Inject constructor(@ApplicationContext private val context: Context) {
    private val manufacturer = Build.MANUFACTURER.lowercase(java.util.Locale.ROOT)
    private val component: ComponentName? get() = when {
        manufacturer.contains("xiaomi") -> ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        manufacturer.contains("huawei") || manufacturer.contains("honor") -> ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        manufacturer.contains("oppo") || manufacturer.contains("realme") -> ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
        manufacturer.contains("vivo") -> ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
        manufacturer.contains("samsung") -> ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
        manufacturer.contains("oneplus") -> ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
        else -> null
    }
    val hasAutostart: Boolean get() = component != null
    val instruction: String get() = when {
        manufacturer.contains("samsung") -> "Remove Alal from Sleeping apps and Deep sleeping apps; allow unrestricted battery use."
        manufacturer.contains("huawei") || manufacturer.contains("honor") -> "In App launch, manage Alal manually and allow auto-launch, secondary launch and background activity."
        hasAutostart -> "Enable Autostart, then set Battery saver → No restrictions."
        else -> "Allow unrestricted battery use for Alal to help downloads continue with the screen off."
    }
    fun ignored(): Boolean = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    fun requestBattery() {
        if (!ignored()) launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
    }
    fun openAutostart() = launch(component?.let { Intent().setComponent(it) } ?: details())
    private fun details() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    private fun launch(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: RuntimeException) {
            try { context.startActivity(details().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: RuntimeException) { }
        }
    }
}