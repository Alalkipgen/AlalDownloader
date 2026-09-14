package com.alal.downloader.ui

import kotlinx.coroutines.flow.first
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alal.downloader.feature.browser.*
import com.alal.downloader.feature.downloads.DownloadsScreen
import com.alal.downloader.feature.downloads.DownloadsViewModel
import com.alal.downloader.feature.downloads.SettingsScreen

@Composable
internal fun AlalApp(viewModel: DownloadsViewModel, browserViewModel: BrowserViewModel, browser: BrowserSession, notificationIntent: android.content.Intent? = null) {
    val context = LocalContext.current
    val tick = rememberUiTick()
    var destination by rememberSaveable { mutableStateOf("Downloads") }
    val screenState = rememberSaveableStateHolder()
    var clipboardUrl by remember { mutableStateOf<String?>(null) }
    val error by viewModel.error.collectAsStateWithLifecycle()
    val message by browserViewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var backgroundDialog by rememberSaveable { mutableStateOf(false) }
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val interrupted by viewModel.interruptedWarning.collectAsStateWithLifecycle()
    var handledPages by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val page = downloads.firstOrNull {
        it.status == com.alal.downloader.core.engine.DownloadStatus.NEEDS_BROWSER && it.id !in handledPages
    }
    LaunchedEffect(page?.id) {
        page?.let {
            val result = snackbar.showSnackbar("This is a web page, not a file", "Open in browser")
            handledPages = ArrayList(handledPages + it.id)
            if (result == SnackbarResult.ActionPerformed) {
                browser.newTab(it.request.url)
                destination = "Browser"
            }
        }
    }
    LaunchedEffect(downloads.isNotEmpty()) {
        if (downloads.isNotEmpty() && viewModel.shouldShowBackgroundPrompt()) {
            viewModel.markBackgroundPromptShown()
            backgroundDialog = true
        }
    }
    LaunchedEffect(interrupted) {
        if (interrupted) {
            if (snackbar.showSnackbar("Downloads were stopped by the system. Fix background settings?", "Fix") == SnackbarResult.ActionPerformed) backgroundDialog = true
            viewModel.dismissInterruptedWarning()
        }
    }
    LaunchedEffect(notificationIntent) {
        if (notificationIntent?.getBooleanExtra("downloads", false) == true) destination = "Downloads"
        val openId = notificationIntent?.getStringExtra("open_download")
        val reopenId = notificationIntent?.getStringExtra("reopen_download")
        if (openId != null || reopenId != null) {
            val states = kotlinx.coroutines.withTimeoutOrNull(10_000) {
                viewModel.downloads.first { list -> list.any { it.id == (openId ?: reopenId) } }
            }
            val state = states?.find { it.id == (openId ?: reopenId) }
            notificationIntent?.removeExtra("open_download")
            notificationIntent?.removeExtra("reopen_download")
            if (state != null) {
                if (openId != null) {
                    try { com.alal.downloader.feature.downloads.DownloadFiles.open(context, state) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (failure: Exception) { snackbar.showSnackbar(failure.message ?: "Cannot open download") }
                } else {
                    if (state.status == com.alal.downloader.core.engine.DownloadStatus.NEEDS_BROWSER) browser.newTab(state.request.url)
                    else browser.reopen(state)
                    destination = "Browser"
                }
            }
        }
    }
    if (backgroundDialog) AlertDialog(
        onDismissRequest = { backgroundDialog = false },
        title = { Text("Allow Alal to run in background") },
        text = { Text(viewModel.backgroundAccess.instruction + " Android and OEM limits may still stop long-running downloads.") },
        confirmButton = { TextButton(onClick = { viewModel.backgroundAccess.requestBattery(); backgroundDialog = false }) { Text("Allow background use") } },
        dismissButton = { Row {
            if (viewModel.backgroundAccess.hasAutostart) TextButton(onClick = viewModel.backgroundAccess::openAutostart) { Text("Open Autostart settings") }
            TextButton(onClick = { backgroundDialog = false }) { Text("Later") }
        } }
    )
    BrowserLifecycle(browser) { clipboardUrl = it }
    BackHandler(enabled = destination != "Downloads") {
        if (destination == "Browser" && browser.active?.webView?.canGoBack() == true) browser.active?.webView?.goBack()
        else destination = if (destination == "History") "Browser" else "Downloads"
    }
    LaunchedEffect(destination) {
        browser.visible = destination == "Browser"
        android.util.Log.d("Browser", "destination=$destination tabs=${browser.tabs.size}")
        if (browser.visible) browser.resume() else browser.pause()
    }
    LaunchedEffect(error) { error?.let { snackbar.showSnackbar(it); viewModel.clearError() } }
    LaunchedEffect(message) {
        message?.let {
            val queued = it == "Download submitted at front of queue"
            val result = snackbar.showSnackbar(if (queued) "Added to queue" else it, actionLabel = if (queued) "View" else null)
            browserViewModel.clearMessage()
            if (queued && result == SnackbarResult.ActionPerformed) destination = "Downloads"
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) browser.directProgress = "Notifications disabled; download controls remain available in the app"
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) viewModel.setTree(uri) }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    Scaffold(modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        snackbarHost = { SnackbarHost(snackbar, Modifier.navigationBarsPadding()) }) { padding ->
        AnimatedContent(destination, modifier = Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize(),
            transitionSpec = {
                val direction = if (targetState == "Downloads") AnimatedContentTransitionScope.SlideDirection.Right else AnimatedContentTransitionScope.SlideDirection.Left
                slideIntoContainer(direction, tween(250)) togetherWith slideOutOfContainer(direction, tween(250))
            }, label = "screen navigation") { route ->
            screenState.SaveableStateProvider(route) {
                val content = Modifier.fillMaxSize()
                when (route) {
                    "Browser" -> BrowserScreen(browser, content.navigationBarsPadding(), { tick(); destination = "Downloads" }, { tick(); destination = "History" })
                    "History" -> HistoryScreen(remember(context) { context.historyRepository() }, browser, content.navigationBarsPadding()) { destination = "Browser" }
                    "Downloads" -> DownloadsScreen(viewModel, {
                        if (it.status == com.alal.downloader.core.engine.DownloadStatus.NEEDS_BROWSER) browser.newTab(it.request.url)
                        else browser.reopen(it)
                        destination = "Browser"
                    }, content, { folder.launch(null) },
                        openBrowser = { tick(); destination = "Browser" }, openSettings = { tick(); destination = "Settings" })
                    else -> SettingsScreen(viewModel, browser, { folder.launch(null) }, content.navigationBarsPadding(), { backgroundDialog = true },
                        navigateBack = { tick(); destination = "Downloads" })
                }
            }
        }
    }
    BrowserConfirmation(browser, browserViewModel, { folder.launch(null) }, { viewModel.setTree(null) })
    clipboardUrl?.let { url ->
        AlertDialog(onDismissRequest = { clipboardUrl = null }, title = { Text("Clipboard link") }, text = { Text(url, maxLines = 4) },
            confirmButton = { TextButton(onClick = { browser.active?.let { browser.captureLink(it.id, url) }; clipboardUrl = null }) { Text("Download") } },
            dismissButton = { TextButton(onClick = { clipboardUrl = null }) { Text("Ignore") } })
    }
}
