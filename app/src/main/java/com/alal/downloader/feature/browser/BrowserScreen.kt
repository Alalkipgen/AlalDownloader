package com.alal.downloader.feature.browser

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
fun BrowserScreen(session: BrowserSession, modifier: Modifier = Modifier) {
    val tab = session.active
    var address by remember(tab?.id) { mutableStateOf(tab?.url.orEmpty()) }
    var showTabs by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showMedia by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    SideEffect { Log.d("Browser", "composition tabs=${session.tabs.size} active=${tab?.id}") }
    LaunchedEffect(session) {
        snapshotFlow { session.tabs.size }.collect { Log.d("Browser", "tabs changed size=$it") }
    }
    LaunchedEffect(session, tab?.id) { if (tab == null) session.ensureActiveTab() }
    LaunchedEffect(tab?.id, tab?.finishedLoads) { address = tab?.url.orEmpty() }
    BackHandler(enabled = tab?.back == true) { tab?.webView?.goBack() }
    
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                placeholder = { Text("Search or type URL") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri),
                keyboardActions = KeyboardActions(onGo = {
                    Log.d("Browser", "Go source=IME")
                    focus.clearFocus()
                    session.navigate(address)
                }),
                trailingIcon = {
                    Row {
                        if (address.isNotBlank()) {
                            IconButton(onClick = { address = "" }) {
                                Icon(Icons.Filled.Close, "Clear")
                            }
                        }
                        IconButton(onClick = { tab?.webView?.reload() }) {
                            Icon(Icons.Filled.Refresh, "Reload")
                        }
                    }
                }
            )
            TextButton(onClick = {
                Log.d("Browser", "Go source=button")
                focus.clearFocus()
                session.navigate(address)
            }) { Text("Go") }
        }
        
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = { tab?.webView?.goBack() }, enabled = tab?.back == true) {
                Icon(Icons.Filled.ArrowBack, "Back")
            }
            IconButton(onClick = { tab?.webView?.goForward() }, enabled = tab?.forward == true) {
                Icon(Icons.Filled.ArrowForward, "Forward")
            }
            IconButton(onClick = { address = ""; focus.clearFocus(); session.navigate(BrowserPolicy.HOME) }) {
                Icon(Icons.Filled.Home, "Home")
            }
            TextButton(onClick = { showTabs = true }) { Text("Tabs ${session.tabs.size}") }
            TextButton(onClick = { showMedia = true }) { Text("Media ${tab?.media?.size ?: 0}") }
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Filled.MoreVert, "Menu")
            }
        }
        
        if (tab != null && tab.progress < 100) {
            LinearProgressIndicator(progress = { tab.progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
        
        session.refresh?.let {
            Text("Waiting for new link for ${it.fileName}… tap the download button on the page.",
                Modifier.padding(8.dp), color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = { session.refresh = null }) { Text("Cancel link refresh") }
        }
        session.directProgress?.let {
            Text(it, Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { session.writer.cancel(); session.directProgress = null }) { Text("Cancel / dismiss save") }
        }
        
        if (tab != null) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                key(tab.id) {
                    AndroidView(
                        factory = {
                            (tab.webView.parent as? android.view.ViewGroup)?.removeView(tab.webView)
                            Log.d("Browser", "factory=${System.identityHashCode(tab.webView)} active=${tab.id}")
                            tab.webView
                        },
                        update = { webViewInTree -> require(webViewInTree === session.active?.webView) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                tab.error?.let { message ->
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.errorContainer) {
                        Column(Modifier.fillMaxSize().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center) {
                            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                            Button(onClick = { session.retry(tab) }) { Text("Retry") }
                        }
                    }
                }
            }
        } else {
            Text("Starting browser…", Modifier.padding(16.dp))
        }
    }
    if (showTabs) AlertDialog(onDismissRequest = { showTabs = false }, title = { Text("Tabs") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            session.tabs.toList().forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        session.active?.webView?.onPause()
                        session.selected = item.id
                        session.resume()
                        showTabs = false
                    }, modifier = Modifier.weight(1f)) { Text(item.title, maxLines = 2) }
                    TextButton(onClick = { session.closeTab(item.id) }) { Text("Close") }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { session.newTab(); showTabs = false }) { Text("New tab") } })
    if (showSettings) AlertDialog(onDismissRequest = { showSettings = false }, title = { Text("Browser settings") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SettingSwitch("Block obvious popups", session.blockPopups) { value ->
                session.blockPopups = value; session.settings.blockPopups = value
                session.tabs.forEach { it.webView.settings.javaScriptCanOpenWindowsAutomatically = !value }
            }
            SettingSwitch("Desktop mode", session.desktop, session::setDesktopMode)
            SettingSwitch("Clipboard suggestions", session.clipboardEnabled) { session.clipboardEnabled = it; session.settings.clipboard = it }
            SettingSwitch("Media candidates (URL based)", session.mediaEnabled) { session.mediaEnabled = it; session.settings.media = it }
            OutlinedTextField(session.extensions, { session.extensions = it; session.settings.extensions = it }, label = { Text("Download extensions") })
            Text("Cookies persist. Captchas and timers are completed manually. Media candidates are not MIME-confirmed; playlists download as manifests, not assembled videos.")
        }
    }, confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Done") } })
    if (showMedia) AlertDialog(onDismissRequest = { showMedia = false }, title = { Text("Media candidates") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (tab?.media.isNullOrEmpty()) Text("No candidates. Enable media candidates in browser settings.")
            tab?.media?.toList()?.forEach { media ->
                Text(media.fileName)
                TextButton(onClick = { if (session.capture == null) session.capture = media; showMedia = false }) { Text("Download") }
            }
        }
    }, confirmButton = { TextButton(onClick = { showMedia = false }) { Text("Close") } })
    session.linkMenu?.let { (id, url) ->
        AlertDialog(onDismissRequest = { session.linkMenu = null }, title = { Text("Link") }, text = { Text(url, maxLines = 5) },
            confirmButton = { TextButton(onClick = { session.captureLink(id, url); session.linkMenu = null }) { Text("Download link") } },
            dismissButton = { TextButton(onClick = { session.copyLink(url); session.linkMenu = null }) { Text("Copy link") } })
    }
}

@Composable
private fun SettingSwitch(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, change)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserConfirmation(session: BrowserSession, viewModel: BrowserViewModel, chooseFolder: () -> Unit, defaultFolder: () -> Unit) {
    val capture = session.capture ?: return
    val tree by viewModel.treeUri.collectAsStateWithLifecycle()
    val submitting by viewModel.submitting.collectAsStateWithLifecycle()
    var name by remember(capture) { mutableStateOf(capture.fileName) }
    val defaults by viewModel.defaultSegments.collectAsStateWithLifecycle()
    var segments by remember(capture) { mutableStateOf(defaults.toString()) }
    var saveError by remember(capture) { mutableStateOf<String?>(null) }
    val direct = capture.url.startsWith("blob:") || capture.url.startsWith("data:")
    ModalBottomSheet(onDismissRequest = { if (!submitting) session.capture = null }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (capture.refreshId == null) "Confirm download" else "Replace expired link", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { name = it }, label = { Text("Filename") }, enabled = capture.refreshId == null && !submitting, modifier = Modifier.fillMaxWidth())
            Text(if (capture.size >= 0) "Size: ${capture.size} bytes" else "Size: unknown")
            if (capture.refreshId == null) {
                Text("Target: ${tree ?: if (Build.VERSION.SDK_INT >= 29) "Download/Alal" else "Select a folder"}")
                Row {
                    TextButton(onClick = chooseFolder, enabled = !submitting) { Text("Choose folder") }
                    if (Build.VERSION.SDK_INT >= 29) TextButton(onClick = defaultFolder, enabled = !submitting) { Text("Use Downloads") }
                }
                if (!direct) OutlinedTextField(segments, { segments = it }, label = { Text("Segments (1–32)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !submitting)
            } else Text("Keep existing destination and checkpoints. Changed or unverifiable content restarts safely.")
            if (direct) Text("Single-threaded page save. Keep this page open; this transfer cannot resume after process death.")
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = !submitting && name.isNotBlank() && (direct || (segments.toIntOrNull() ?: 0) in 1..32), onClick = {
                try {
                    if (direct) {
                        val tab = session.tabs.find { it.id == capture.tabId } ?: error("Source tab closed")
                        session.writer.start(tab.webView, viewModel.request(capture, name, 1))
                        session.capture = null
                    } else viewModel.submit(capture, name, segments.toInt(), { session.refresh = null }, { session.capture = null })
                } catch (failure: Exception) { saveError = failure.message }
            }) { Text(if (submitting) "Starting…" else if (capture.refreshId == null) "Download" else "Replace link and resume") }
        }
    }
}

@Composable
fun BrowserLifecycle(session: BrowserSession, clipboardSuggestion: (String) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(session, lifecycle, session.clipboardEnabled) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        fun inspectClipboard() {
            if (!session.clipboardEnabled || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !view.hasWindowFocus()) return
            val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.trim() ?: return
            if (text.length <= 8192 && text.toHttpUrlOrNull() != null && session.settings.unseenClipboard(text)) clipboardSuggestion(text)
        }
        val listener = ClipboardManager.OnPrimaryClipChangedListener { inspectClipboard() }
        val windowListener = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) inspectClipboard() }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> session.pause()
                Lifecycle.Event.ON_RESUME -> { session.resume(); view.post { inspectClipboard() } }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (session.clipboardEnabled) clipboard.addPrimaryClipChangedListener(listener)
        view.viewTreeObserver.addOnWindowFocusChangeListener(windowListener)
        view.post { inspectClipboard() }
        onDispose {
            lifecycle.removeObserver(observer)
            clipboard.removePrimaryClipChangedListener(listener)
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(windowListener)
        }
    }
}