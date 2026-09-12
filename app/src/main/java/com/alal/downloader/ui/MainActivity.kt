package com.alal.downloader.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alal.downloader.feature.downloads.DownloadsViewModel
import com.alal.downloader.feature.browser.BrowserViewModel
import dagger.hilt.android.AndroidEntryPoint

/** Hosts the Alal Compose interface. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val downloadsViewModel: DownloadsViewModel by viewModels()
    private val browserViewModel: BrowserViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme by downloadsViewModel.theme.collectAsStateWithLifecycle()
            AlalTheme(theme) {
                AlalApp(downloadsViewModel, browserViewModel)
            }
        }
    }
}