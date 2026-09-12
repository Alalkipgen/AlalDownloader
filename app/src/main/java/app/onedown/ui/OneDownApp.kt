package app.onedown.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onedown.feature.browser.*
import app.onedown.feature.downloads.DownloadsScreen
import app.onedown.feature.downloads.DownloadsViewModel
import app.onedown.feature.downloads.SettingsScreen

@Composable
internal fun OneDownApp(viewModel: DownloadsViewModel, browserViewModel: BrowserViewModel) {
    val context = LocalContext.current
    val browser = remember(context) { BrowserSession(context).apply { newTab() } }
    var destination by rememberSaveable { mutableStateOf("Browser") }
    var clipboardUrl by remember { mutableStateOf<String?>(null) }
    val error by viewModel.error.collectAsStateWithLifecycle()
    val message by browserViewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    DisposableEffect(browser) { onDispose { browser.close() } }
    BrowserLifecycle(browser) { clipboardUrl = it }
    LaunchedEffect(destination) {
        browser.visible = destination == "Browser"
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
        NavigationBar {
            listOf("Browser", "Downloads", "Settings").forEach { item ->
                NavigationBarItem(selected = destination == item, onClick = { destination = item },
                    icon = { Text(when (item) { "Browser" -> "◎"; "Downloads" -> "↓"; else -> "⚙" }) }, label = { Text(item) })
            }
        }
    }) { padding ->
        val content = Modifier.padding(padding).fillMaxSize()
        when (destination) {
            "Browser" -> BrowserScreen(browser, content)
            "Downloads" -> DownloadsScreen(viewModel, { browser.reopen(it); destination = "Browser" }, content)
            else -> SettingsScreen(viewModel, browser, { folder.launch(null) }, content)
        }
    }
    BrowserConfirmation(browser, browserViewModel, { folder.launch(null) }, { viewModel.setTree(null) })
    clipboardUrl?.let { url ->
        AlertDialog(onDismissRequest = { clipboardUrl = null }, title = { Text("Clipboard link") }, text = { Text(url, maxLines = 4) },
            confirmButton = { TextButton(onClick = { browser.active?.let { browser.captureLink(it.id, url) }; clipboardUrl = null }) { Text("Download") } },
            dismissButton = { TextButton(onClick = { clipboardUrl = null }) { Text("Ignore") } })
    }
}
