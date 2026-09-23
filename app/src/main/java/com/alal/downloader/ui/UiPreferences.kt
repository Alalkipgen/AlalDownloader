package com.alal.downloader.ui

import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.alal.downloader.core.data.DownloadSettings

val LocalDownloadSettings = staticCompositionLocalOf<DownloadSettings> { error("Download settings not provided") }
val LocalHapticsEnabled = compositionLocalOf { true }

/** Appearance choices owned by the UI layer and mirrored into shared preferences. */
@Stable
class AppearanceState(private val preferences: SharedPreferences) {
    var dynamic by mutableStateOf(preferences.getBoolean(DYNAMIC, false))
        private set
    var accent by mutableStateOf(preferences.getString(ACCENT, DEFAULT_ACCENT) ?: DEFAULT_ACCENT)
        private set
    var amoled by mutableStateOf(preferences.getBoolean(AMOLED, false))
        private set
    var colorfulIcons by mutableStateOf(preferences.getBoolean(ICONS, true))
        private set
    var speedGraph by mutableStateOf(preferences.getBoolean(GRAPH, true))
        private set

    fun updateDynamic(value: Boolean) {
        dynamic = value
        preferences.edit().putBoolean(DYNAMIC, value).apply()
    }

    fun updateAccent(value: String) {
        accent = value
        preferences.edit().putString(ACCENT, value).apply()
    }

    fun updateAmoled(value: Boolean) {
        amoled = value
        preferences.edit().putBoolean(AMOLED, value).apply()
    }

    fun updateColorfulIcons(value: Boolean) {
        colorfulIcons = value
        preferences.edit().putBoolean(ICONS, value).apply()
    }

    fun updateSpeedGraph(value: Boolean) {
        speedGraph = value
        preferences.edit().putBoolean(GRAPH, value).apply()
    }

    private companion object {
        const val DYNAMIC = "dynamic"
        const val ACCENT = "accent"
        const val AMOLED = "amoled"
        const val ICONS = "colorful_icons"
        const val GRAPH = "speed_graph"
        const val DEFAULT_ACCENT = "indigo"
    }
}

val LocalAppearance = staticCompositionLocalOf<AppearanceState> { error("Appearance settings not provided") }

@Composable
fun rememberUiTick(): () -> Unit {
    val feedback = LocalHapticFeedback.current
    val enabled = LocalHapticsEnabled.current
    return remember(feedback, enabled) { { if (enabled) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove) } }
}
