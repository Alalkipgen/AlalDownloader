package com.alal.downloader.ui

import kotlinx.coroutines.flow.first
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alal.downloader.feature.browser.*
import com.alal.downloader.feature.downloads.DownloadsScreen
import com.alal.downloader.feature.downloads.DownloadsViewModel
import com.alal.downloader.feature.downloads.SettingsScreen

@Composable
internal fun AlalApp(viewModel: DownloadsViewModel, browserViewModel: BrowserViewModel, notificationIntent: android.content.Intent? = null) {
    val context = LocalContext.current
    val tick = rememberUiTick()
    val browser = remember(context) { BrowserSession(context) }
    var destination by rememberSaveable { mutableStateOf("Downloads") }
    var clipboardUrl by remember { mutableStateOf<String?>(null) }
    val error by viewModel.error.collectAsStateWithLifecycle()
    val message by browserViewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var backgroundDialog by rememberSaveable { mutableStateOf(false) }
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val interrupted by viewModel.interruptedWarning.collectAsStateWithLifecycle()
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
                } else { browser.reopen(state); destination = "Browser" }
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
    DisposableEffect(browser) { onDispose { browser.close() } }
    BrowserLifecycle(browser) { clipboardUrl = it }
    LaunchedEffect(destination) {
        browser.visible = destination == "Browser"
        android.util.Log.d("Browser", "destination=$destination tabs=${browser.tabs.size}")
        if (browser.visible) browser.resume() else browser.pause()
    }
    LaunchedEffect(error) { error?.let { snackbar.showSnackbar(it); viewModel.clearError() } }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); browserViewModel.clearMessage() } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) browser.directProgress = "Notifications disabled; download controls remain available in the app"
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) viewModel.setTree(uri) }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    Scaffold(modifier = Modifier.fillMaxSize().safeDrawingPadding(), snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
        Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)
        NavigationBar(modifier = Modifier.height(84.dp), windowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF101017) else MaterialTheme.colorScheme.surface) {
            listOf("Browser", "Downloads", "Settings").forEach { item ->
                NavigationBarItem(selected = destination == item, onClick = { if (destination != item) { tick(); destination = item } },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer, indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = Color(0xFF5F5F78), unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    icon = { Icon(when (item) { "Browser" -> Icons.Outlined.Language; "Downloads" -> Icons.Outlined.Download; else -> Icons.Outlined.Settings }, item) },
                    label = { Text(item, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold) })
            }
        }
        }
    }) { padding ->
        val content = Modifier.padding(padding).fillMaxSize()
        when (destination) {
            "Browser" -> BrowserScreen(browser, content)
            "Downloads" -> DownloadsScreen(viewModel, { browser.reopen(it); destination = "Browser" }, content, { folder.launch(null) })
            else -> SettingsScreen(viewModel, browser, { folder.launch(null) }, content, { backgroundDialog = true })
        }
    }
    BrowserConfirmation(browser, browserViewModel, { folder.launch(null) }, { viewModel.setTree(null) })
    clipboardUrl?.let { url ->
        AlertDialog(onDismissRequest = { clipboardUrl = null }, title = { Text("Clipboard link") }, text = { Text(url, maxLines = 4) },
            confirmButton = { TextButton(onClick = { browser.active?.let { browser.captureLink(it.id, url) }; clipboardUrl = null }) { Text("Download") } },
            dismissButton = { TextButton(onClick = { clipboardUrl = null }) { Text("Ignore") } })
    }
}
