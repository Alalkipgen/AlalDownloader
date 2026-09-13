package com.alal.downloader.ui

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alal.downloader.feature.downloads.DownloadsViewModel
import com.alal.downloader.feature.browser.BrowserViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.alal.downloader.core.data.DownloadSettings
import com.alal.downloader.ui.theme.AlalTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

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
            val appearance = remember { getSharedPreferences("ui_appearance", MODE_PRIVATE) }
            var dynamic by remember { mutableStateOf(appearance.getBoolean("dynamic", false)) }
            val effectiveTheme = if (getSharedPreferences("download_settings", MODE_PRIVATE).contains("theme")) theme else "system"
            CompositionLocalProvider(LocalDownloadSettings provides downloadSettings, LocalHapticsEnabled provides haptics,
                LocalDynamicColor provides dynamic, LocalSetDynamicColor provides { value ->
                    appearance.edit().putBoolean("dynamic", value).apply(); dynamic = value
                }) {
            AlalTheme(effectiveTheme, dynamic) {
                AlalApp(downloadsViewModel, browserViewModel, browserHostViewModel.session, notificationIntent)
            }
            }
        }
    }
}