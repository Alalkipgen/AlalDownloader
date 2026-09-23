package com.alal.downloader.ui

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alal.downloader.feature.downloads.DownloadsViewModel
import com.alal.downloader.feature.browser.BrowserViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.alal.downloader.core.data.DownloadSettings
import com.alal.downloader.ui.theme.AlalTheme
import com.alal.downloader.ui.theme.isDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

private val LightScrim = android.graphics.Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
private val DarkScrim = android.graphics.Color.argb(0x80, 0x1B, 0x1B, 0x1B)

/** Hosts the Alal Compose interface. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var downloadSettings: DownloadSettings
    private val downloadsViewModel: DownloadsViewModel by viewModels()
    private val browserViewModel: BrowserViewModel by viewModels()
    private val browserHostViewModel: BrowserHostViewModel by viewModels()
    private var notificationIntent by mutableStateOf<Intent?>(null)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationIntent = intent
    }
    override fun onResume() {
        super.onResume()
        downloadsViewModel.recover()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationIntent = intent
        enableEdgeToEdge()
        setContent {
            val theme by downloadsViewModel.theme.collectAsStateWithLifecycle()
            val haptics by downloadSettings.haptics.collectAsStateWithLifecycle()
            val appearance = remember { AppearanceState(getSharedPreferences("ui_appearance", MODE_PRIVATE)) }
            val dark = isDarkTheme(theme)
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                    SystemBarStyle.auto(LightScrim, DarkScrim) { dark },
                )
            }
            CompositionLocalProvider(LocalDownloadSettings provides downloadSettings, LocalHapticsEnabled provides haptics,
                LocalAppearance provides appearance) {
                AlalTheme(theme, appearance.dynamic, appearance.accent, appearance.amoled) {
                    AlalApp(downloadsViewModel, browserViewModel, browserHostViewModel.session, notificationIntent)
                    PreviousCrashNotice()
                }
            }
        }
    }
}
