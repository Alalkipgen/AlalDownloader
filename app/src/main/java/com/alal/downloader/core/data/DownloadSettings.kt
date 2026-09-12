package com.alal.downloader.core.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persisted download policy and default destination for newly added transfers. */
@Singleton
class DownloadSettings @Inject constructor(@ApplicationContext private val context: Context) {
    private val preferences = context.getSharedPreferences("download_settings", Context.MODE_PRIVATE)
    private val mutableWifiOnly = MutableStateFlow(preferences.getBoolean("wifi_only", false))
    private val mutableTree = MutableStateFlow(preferences.getString("tree_uri", null))
    val wifiOnly = mutableWifiOnly.asStateFlow()
    val treeUri = mutableTree.asStateFlow()
    private val mutableSegments = MutableStateFlow(preferences.getInt("segments", 8).coerceIn(1, 32))
    private val mutableConcurrent = MutableStateFlow(preferences.getInt("concurrent", 3).coerceIn(1, 10))
    private val mutableSpeed = MutableStateFlow(preferences.getLong("speed_kib", 0).coerceIn(0, Long.MAX_VALUE / 1024))
    private val mutableTheme = MutableStateFlow(preferences.getString("theme", "dark") ?: "dark")
    val segments = mutableSegments.asStateFlow()
    val concurrent = mutableConcurrent.asStateFlow()
    val speed = mutableSpeed.asStateFlow()
    val theme = mutableTheme.asStateFlow()

    private val mutableAutoResume = MutableStateFlow(preferences.getBoolean("auto_resume", true))
    val autoResume = mutableAutoResume.asStateFlow()
    var backgroundPromptShown: Boolean
        get() = preferences.getBoolean("background_prompt", false)
        set(value) { preferences.edit().putBoolean("background_prompt", value).apply() }
    var wasActiveAtShutdown: Boolean
        get() = preferences.getBoolean("wasActiveAtShutdown", false)
        set(value) { check(preferences.edit().putBoolean("wasActiveAtShutdown", value).commit()) }
    var recoveryIds: Set<String>
        get() = preferences.getStringSet("recovery_ids", emptySet()).orEmpty().toSet()
        set(value) { check(preferences.edit().putStringSet("recovery_ids", value.toSet()).commit()) }

    fun setAutoResume(value: Boolean) {
        check(preferences.edit().putBoolean("auto_resume", value).commit())
        mutableAutoResume.value = value
    }

    fun setTransfer(segments: Int, concurrent: Int, speed: Long) {
        require(segments in 1..32 && concurrent in 1..10 && speed in 0..Long.MAX_VALUE / 1024)
        check(preferences.edit().putInt("segments", segments).putInt("concurrent", concurrent)
            .putLong("speed_kib", speed).commit()) { "Cannot save transfer settings" }
        mutableSegments.value = segments
        mutableConcurrent.value = concurrent
        mutableSpeed.value = speed
    }

    fun setTheme(theme: String) {
        require(theme in setOf("system", "light", "dark"))
        check(preferences.edit().putString("theme", theme).commit()) { "Cannot save theme" }
        mutableTheme.value = theme
    }

    fun setWifiOnly(value: Boolean) {
        check(preferences.edit().putBoolean("wifi_only", value).commit()) { "Cannot save network setting" }
        mutableWifiOnly.value = value
    }

    fun setTree(uri: Uri?) {
        if (uri != null) context.contentResolver.takePersistableUriPermission(uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        check(preferences.edit().putString("tree_uri", uri?.toString()).commit()) { "Cannot save folder" }
        mutableTree.value = uri?.toString()
    }
}