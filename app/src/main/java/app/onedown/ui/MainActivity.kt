package app.onedown.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onedown.feature.downloads.DownloadsViewModel
import app.onedown.feature.browser.BrowserViewModel
import dagger.hilt.android.AndroidEntryPoint

/** Hosts the OneDown Compose interface. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val downloadsViewModel: DownloadsViewModel by viewModels()
    private val browserViewModel: BrowserViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme by downloadsViewModel.theme.collectAsStateWithLifecycle()
            OneDownTheme(theme) {
                OneDownApp(downloadsViewModel, browserViewModel)
            }
        }
    }
}