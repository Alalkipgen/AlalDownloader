package com.alal.downloader.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.alal.downloader.feature.browser.BrowserSession
import com.alal.downloader.feature.browser.BrowserPolicy
import com.alal.downloader.ui.theme.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alal.downloader.feature.browser.historyRepository
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alal.downloader.feature.browser.HistorySuggestionsViewModel

@Composable
fun BrowserChrome(session: BrowserSession, tabs: () -> Unit, media: () -> Unit, more: () -> Unit, navigateBack: () -> Unit) {
    val tab = session.active
    val context = LocalContext.current
    val history = remember(context) { context.historyRepository() }
    val incognito by history.incognito.collectAsStateWithLifecycle()
    var stopped by remember(tab?.id, tab?.finishedLoads, tab?.url) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val suggestionsModel: HistorySuggestionsViewModel = viewModel()
    val suggestions by suggestionsModel.suggestions.collectAsStateWithLifecycle()
    DisposableEffect(suggestionsModel) { onDispose { suggestionsModel.update("") } }
    val loading = tab != null && tab.progress < 100 && !stopped && tab.error == null
    val progress by animateFloatAsState((tab?.progress ?: 100) / 100f, tween(250), label = "page progress")
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChromeButton(Icons.Outlined.ArrowBack, "Back to Downloads", click = navigateBack)
            key(tab?.id) {
                BrowserAddressBar(
                    pageUrl = tab?.url.orEmpty(), suggestions = suggestions, incognito = incognito,
                    onQueryChange = suggestionsModel::update,
                    onNavigate = { stopped = false; session.navigate(it) },
                    modifier = Modifier.weight(1f),
                    trailingContent = {
                        IconButton(onClick = { if (loading) { tab?.webView?.stopLoading(); stopped = true } else { stopped = false; tab?.webView?.reload() } }, modifier = Modifier.size(30.dp)) {
                            Icon(if (loading) Icons.Outlined.Stop else Icons.Outlined.Refresh, if (loading) "Stop loading" else "Reload", Modifier.size(18.dp))
                        }
                    },
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(3.dp).padding(horizontal = 24.dp)) {
            BrowserLoadingProgress(loading, progress)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ChromeButton(Icons.Outlined.ArrowBack, "Back", enabled = tab?.back == true) { tab?.webView?.goBack() }
            ChromeButton(Icons.Outlined.ArrowForward, "Forward", enabled = tab?.forward == true) { tab?.webView?.goForward() }
            ChromeButton(Icons.Outlined.Home, "Home") { stopped = false; focus.clearFocus(); session.navigate(BrowserPolicy.HOME) }
            Spacer(Modifier.weight(1f))
            ChromeButton(Icons.Outlined.Tab, "Tabs", count = session.tabs.size, click = tabs)
            ChromeButton(Icons.Outlined.Download, "Media candidates", count = tab?.media?.size?.takeIf { it > 0 }, click = media)
            ChromeButton(Icons.Outlined.MoreVert, "Browser menu", click = more)
        }
    }
}

@Composable
private fun BrowserLoadingProgress(loading: Boolean, progress: Float) {
    AnimatedVisibility(loading, exit = fadeOut(tween(200))) {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.5.dp), trackColor = MaterialTheme.colorScheme.primaryContainer)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChromeButton(icon: ImageVector, label: String, enabled: Boolean = true, count: Int? = null, click: () -> Unit) {
    IconButton(onClick = click, enabled = enabled, modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant, IconShape)) {
        if (count == null) Icon(icon, label, tint = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        else BadgedBox(badge = { Badge { Text("$count", style = MaterialTheme.typography.labelSmall) } }) { Icon(icon, "$label: $count", tint = MaterialTheme.colorScheme.onPrimaryContainer) }
    }
}