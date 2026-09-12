package com.alal.downloader.core.service

import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView

/** Centralized haptic feedback helper respecting system and app settings. */
class HapticFeedbackHelper(
    private val context: Context,
    private val enabled: () -> Boolean
) {
    private val view: View? by lazy {
        try {
            (context as? android.app.Activity)?.window?.decorView
        } catch (_: Exception) {
            null
        }
    }

    fun perform(type: HapticType) {
        if (!enabled()) return
        
        val constant = when (type) {
            HapticType.CLICK -> HapticFeedbackConstants.CONTEXT_CLICK
            HapticType.LONG_PRESS -> HapticFeedbackConstants.LONG_PRESS
            HapticType.SUCCESS -> if (Build.VERSION.SDK_INT >= 30) 
                HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
            HapticType.ERROR -> if (Build.VERSION.SDK_INT >= 30) 
                HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            HapticType.TICK -> if (Build.VERSION.SDK_INT >= 27) 
                HapticFeedbackConstants.CLOCK_TICK else HapticFeedbackConstants.CONTEXT_CLICK
        }
        
        view?.performHapticFeedback(
            constant,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }
}

enum class HapticType {
    CLICK,
    LONG_PRESS,
    SUCCESS,
    ERROR,
    TICK
}

@Composable
fun rememberHapticFeedback(enabled: () -> Boolean): HapticFeedbackHelper {
    val context = LocalContext.current
    return remember(context) { HapticFeedbackHelper(context, enabled) }
}

@Composable
fun composeHapticFeedback(enabled: Boolean = true): HapticFeedback? {
    return if (enabled) LocalHapticFeedback.current else null
}
