package com.alal.downloader.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alal.downloader.feature.browser.BrowserSession
import com.alal.downloader.feature.browser.BrowserPolicy
import com.alal.downloader.ui.theme.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
fun BrowserChrome(session: BrowserSession, tabs: () -> Unit, media: () -> Unit, more: () -> Unit) {
    val tab = session.active
    var address by remember(tab?.id) { mutableStateOf(TextFieldValue(tab?.url.orEmpty())) }
    var editing by remember { mutableStateOf(false) }
    var acquiredFocus by remember { mutableStateOf(false) }
    var homeEmpty by remember(tab?.id) { mutableStateOf(false) }
    var stopped by remember(tab?.id, tab?.finishedLoads, tab?.url) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    val loading = tab != null && tab.progress < 100 && !stopped && tab.error == null
    val progress by animateFloatAsState((tab?.progress ?: 100) / 100f, tween(250), label = "page progress")
    LaunchedEffect(tab?.url, tab?.finishedLoads, editing) {
        if (!editing) address = TextFieldValue(if (homeEmpty && tab?.url?.trimEnd('/') == BrowserPolicy.HOME.trimEnd('/')) "" else tab?.url.orEmpty())
    }
    LaunchedEffect(editing) { if (editing) requester.requestFocus() }
    fun navigate() { homeEmpty = false; stopped = false; session.navigate(address.text); focus.clearFocus(); editing = false }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp).height(46.dp).background(MaterialTheme.colorScheme.surfaceVariant, PillShape).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!editing && address.text.isNotEmpty()) Icon(Icons.Outlined.Lock, "Connection security", Modifier.size(15.dp), tint = if (tab?.url?.startsWith("https://") == true) Ok else Warn)
            Spacer(Modifier.width(7.dp))
            Box(Modifier.weight(1f)) {
                if (editing) BasicTextField(address, { address = it }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri), keyboardActions = KeyboardActions(onGo = { navigate() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(requester).onFocusChanged {
                        if (it.isFocused) acquiredFocus = true
                        else if (acquiredFocus) { editing = false; acquiredFocus = false }
                    })
                else Row(Modifier.fillMaxWidth().clickable { address = TextFieldValue(tab?.url.orEmpty(), TextRange(0, tab?.url.orEmpty().length)); editing = true }, verticalAlignment = Alignment.CenterVertically) {
                    val parsed = address.text.toHttpUrlOrNull()
                    if (parsed == null) Text("Search or type URL", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, fontSize = 14.sp)
                    else {
                        Text(parsed.host, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, softWrap = false)
                        Text(parsed.encodedPath + (parsed.encodedQuery?.let { "?$it" } ?: ""), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (editing) IconButton(onClick = { address = TextFieldValue("") }, modifier = Modifier.size(30.dp)) { Icon(Icons.Outlined.Close, "Clear", Modifier.size(18.dp)) }
            IconButton(onClick = { if (loading) { tab?.webView?.stopLoading(); stopped = true } else { stopped = false; tab?.webView?.reload() } }, modifier = Modifier.size(30.dp)) {
                Icon(if (loading) Icons.Outlined.Stop else Icons.Outlined.Refresh, if (loading) "Stop loading" else "Reload", Modifier.size(18.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(3.dp).padding(horizontal = 24.dp)) {
            BrowserLoadingProgress(loading, progress)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ChromeButton(Icons.Outlined.ArrowBack, "Back", enabled = tab?.back == true) { tab?.webView?.goBack() }
            ChromeButton(Icons.Outlined.ArrowForward, "Forward", enabled = tab?.forward == true) { tab?.webView?.goForward() }
            ChromeButton(Icons.Outlined.Home, "Home") { homeEmpty = true; stopped = false; address = TextFieldValue(""); focus.clearFocus(); editing = false; session.navigate(BrowserPolicy.HOME) }
            Spacer(Modifier.weight(1f))
            ChromeButton(Icons.Outlined.Tab, "Tabs", count = session.tabs.size, click = tabs)
            ChromeButton(Icons.Outlined.Download, "Media candidates", count = tab?.media?.size ?: 0, click = media)
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