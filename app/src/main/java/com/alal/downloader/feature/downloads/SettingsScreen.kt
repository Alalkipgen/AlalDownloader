package com.alal.downloader.feature.downloads

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alal.downloader.feature.browser.BrowserSession

/** App settings using the existing persisted transfer and browser preferences. */
@Composable
fun SettingsScreen(viewModel: DownloadsViewModel, browser: BrowserSession, chooseFolder: () -> Unit, modifier: Modifier = Modifier) {
    val savedSegments by viewModel.segments.collectAsStateWithLifecycle()
    val savedConcurrent by viewModel.concurrent.collectAsStateWithLifecycle()
    val savedSpeed by viewModel.speed.collectAsStateWithLifecycle()
    val wifi by viewModel.wifiOnly.collectAsStateWithLifecycle()
    val tree by viewModel.treeUri.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    var segments by remember(savedSegments) { mutableStateOf(savedSegments.toString()) }
    var concurrent by remember(savedConcurrent) { mutableStateOf(savedConcurrent.toString()) }
    var speed by remember(savedSpeed) { mutableStateOf(savedSpeed.toString()) }
    val valid = (segments.toIntOrNull() ?: 0) in 1..32 && (concurrent.toIntOrNull() ?: 0) in 1..10 &&
        speed.toLongOrNull()?.let { it in 0..Long.MAX_VALUE / 1024 } == true
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(segments, { segments = it }, label = { Text("Default segments (1–32)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        OutlinedTextField(concurrent, { concurrent = it }, label = { Text("Concurrent downloads (1–10)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        OutlinedTextField(speed, { speed = it }, label = { Text("Speed limit (KiB/s; 0 = unlimited)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        Text("Limit is shared by engine downloads. 1 KiB = 1024 bytes. Blob/data saves are not throttled.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { viewModel.setTransfer(segments.toInt(), concurrent.toInt(), speed.toLong()) }, enabled = valid) { Text("Apply transfer settings") }
        if (!valid) Text("Enter values within the indicated ranges", color = MaterialTheme.colorScheme.error)
        Toggle("Wi-Fi only (unmetered)", wifi, viewModel::setWifiOnly)
        Text("Folder: ${tree ?: if (Build.VERSION.SDK_INT >= 29) "Download/Alal" else "Choose a folder"}")
        Row {
            TextButton(onClick = chooseFolder) { Text("Choose folder") }
            if (Build.VERSION.SDK_INT >= 29) TextButton(onClick = { viewModel.setTree(null) }) { Text("Use Downloads") }
        }
        HorizontalDivider()
        Toggle("Desktop UA default", browser.desktop, browser::setDesktopMode)
        Toggle("Block popups", browser.blockPopups) { value ->
            browser.blockPopups = value
            browser.settings.blockPopups = value
            browser.tabs.forEach { it.webView.settings.javaScriptCanOpenWindowsAutomatically = !value }
        }
        Toggle("Media detection (URL candidates)", browser.mediaEnabled) { browser.mediaEnabled = it; browser.settings.media = it }
        Toggle("Clipboard watcher (foreground only)", browser.clipboardEnabled) { browser.clipboardEnabled = it; browser.settings.clipboard = it }
        OutlinedTextField(browser.extensions, { browser.extensions = it; browser.settings.extensions = it },
            label = { Text("Download extensions (comma-separated)") }, modifier = Modifier.fillMaxWidth())
        Text("Media detection uses URL extensions, not response MIME; playlists are saved as manifests.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("Theme", style = MaterialTheme.typography.titleMedium)
        Row { listOf("system", "light", "dark").forEach { value ->
            FilterChip(selected = theme == value, onClick = { viewModel.setTheme(value) }, label = { Text(value.replaceFirstChar { it.uppercase() }) }, modifier = Modifier.padding(end = 8.dp))
        } }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, change)
    }
}