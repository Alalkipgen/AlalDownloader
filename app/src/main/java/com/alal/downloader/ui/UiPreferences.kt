package com.alal.downloader.ui

import androidx.compose.runtime.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.alal.downloader.core.data.DownloadSettings

val LocalDownloadSettings = staticCompositionLocalOf<DownloadSettings> { error("Download settings not provided") }
val LocalHapticsEnabled = compositionLocalOf { true }
val LocalDynamicColor = compositionLocalOf { false }
val LocalSetDynamicColor = staticCompositionLocalOf<(Boolean) -> Unit> { error("Appearance settings not provided") }

@Composable
fun rememberUiTick(): () -> Unit {
    val feedback = LocalHapticFeedback.current
    val enabled = LocalHapticsEnabled.current
    return remember(feedback, enabled) { { if (enabled) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove) } }
}