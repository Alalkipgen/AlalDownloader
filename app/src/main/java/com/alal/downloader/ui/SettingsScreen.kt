package com.alal.downloader.feature.downloads

import android.os.Build
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alal.downloader.BuildConfig
import com.alal.downloader.ui.*
import com.alal.downloader.ui.theme.SettingsShape
import com.alal.downloader.ui.theme.Ok
import com.alal.downloader.ui.theme.Warn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
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
fun SettingsScreen(viewModel: DownloadsViewModel, browser: BrowserSession, chooseFolder: () -> Unit, modifier: Modifier = Modifier, background: () -> Unit = {}, navigateBack: () -> Unit) {
    val wifi by viewModel.wifiOnly.collectAsStateWithLifecycle()
    val tree by viewModel.treeUri.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val autoResume by viewModel.autoResume.collectAsStateWithLifecycle()
    val settings = LocalDownloadSettings.current
    val haptics by settings.haptics.collectAsStateWithLifecycle()
    val dynamic = LocalDynamicColor.current
    val setDynamic = LocalSetDynamicColor.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var extensions by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    var showCrash by remember { mutableStateOf(false) }
    var crashReport by remember { mutableStateOf<String?>(null) }
    var allowed by remember { mutableStateOf(viewModel.backgroundAccess.ignored()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) allowed = viewModel.backgroundAccess.ignored() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { scope.launch { snackbar.showSnackbar("No browser available") } }
    }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = navigateBack, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.ArrowBack, "Back to Downloads") }
                Text("Settings", style = MaterialTheme.typography.titleLarge)
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { SettingsGroup("TRANSFERS") { TransferControls(viewModel) { scope.launch { snackbar.showSnackbar("Applied") } } } }
                item { SettingsGroup("STORAGE") {
                    SettingsLink("Download location", tree ?: "/storage/emulated/0/Download/Alal", chooseFolder, monospace = true)
                    if (tree != null && Build.VERSION.SDK_INT >= 29) TextButton(onClick = { viewModel.setTree(null) }) { Text("Use Downloads folder") }
                } }
                item { SettingsGroup("RELIABILITY") {
                    SettingsToggle("Auto-resume interrupted", "Retries when the network returns", autoResume, viewModel::setAutoResume)
                    SettingsToggle("Wi-Fi only", "Pause on mobile data", wifi, viewModel::setWifiOnly)
                    SettingsToggle("Haptic feedback", "On start, finish and errors", haptics, settings::setHaptics)
                    Row(Modifier.fillMaxWidth().clickable(enabled = !allowed) { viewModel.backgroundAccess.requestBattery() }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Battery optimization: ${if (allowed) "Allowed" else "Restricted"}", color = if (allowed) Ok else Warn)
                            Text(if (allowed) "Background downloads allowed" else "Tap to allow background downloads", style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(if (allowed) Icons.Outlined.CheckCircle else Icons.Outlined.ChevronRight, null, tint = if (allowed) Ok else Warn)
                    }
                    if (viewModel.backgroundAccess.hasAutostart) SettingsLink("Autostart", viewModel.backgroundAccess.instruction, viewModel.backgroundAccess::openAutostart)
                } }
                item { SettingsGroup("BROWSER") {
                    SettingsToggle("Block obvious popups", null, browser.blockPopups) { value ->
                        browser.blockPopups = value; browser.settings.blockPopups = value
                        browser.tabs.forEach { it.webView.settings.javaScriptCanOpenWindowsAutomatically = !value }
                    }
                    SettingsToggle("Desktop mode", null, browser.desktop, browser::setDesktopMode)
                    SettingsToggle("Clipboard suggestions", "While the app is in the foreground", browser.clipboardEnabled) { browser.clipboardEnabled = it; browser.settings.clipboard = it }
                    SettingsToggle("Media candidates", "URL based, not MIME-confirmed", browser.mediaEnabled) { browser.mediaEnabled = it; browser.settings.media = it }
                    SettingsLink("Download extensions", browser.extensions, { extensions = true })
                } }
                item { SettingsGroup("APPEARANCE") {
                    Text("Theme", Modifier.padding(start = 14.dp, top = 14.dp))
                    val effectiveTheme = if (context.getSharedPreferences("download_settings", 0).contains("theme")) theme else "system"
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(14.dp)) {
                        listOf("system", "dark", "light").forEachIndexed { index, value ->
                            SegmentedButton(selected = effectiveTheme == value, onClick = { viewModel.setTheme(value) }, shape = SegmentedButtonDefaults.itemShape(index, 3)) {
                                Text(value.replaceFirstChar { it.uppercase() })
                            }
                        }
                    }
                    if (Build.VERSION.SDK_INT >= 31) SettingsToggle("Dynamic color", "Use colors from your wallpaper", dynamic, setDynamic)
                } }
                item { SettingsGroup("ABOUT") {
                    SettingsLink("Last crash log", "View, copy or share the saved report", {
                        scope.launch {
                            crashReport = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.alal.downloader.CrashLog.read(context) }
                            showCrash = true
                        }
                    })
                    Text("Alal Downloader ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", Modifier.padding(14.dp))
                    SettingsLink("Open-source licenses", "AndroidX · Kotlin · OkHttp · Hilt", { licenses = true })
                    SettingsLink("GitHub repository", "Alalkipgen/AlalDownloader", { open("https://github.com/Alalkipgen/AlalDownloader") })
                } }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    if (showCrash) CrashLogDialog("Last crash log", crashReport) { showCrash = false }
    if (extensions) AlertDialog(onDismissRequest = { extensions = false }, title = { Text("Download extensions") }, text = {
        OutlinedTextField(browser.extensions, { browser.extensions = it; browser.settings.extensions = it }, label = { Text("Space-separated extensions") })
    }, confirmButton = { TextButton(onClick = { extensions = false }) { Text("Done") } })
    if (licenses) AlertDialog(onDismissRequest = { licenses = false }, title = { Text("Open-source licenses") }, text = {
        Column {
            Text("AndroidX, Kotlin, kotlinx.coroutines, OkHttp and Dagger/Hilt are distributed under the Apache License 2.0.")
            TextButton(onClick = { open("https://www.apache.org/licenses/LICENSE-2.0") }) { Text("Read Apache License 2.0") }
        }
    }, confirmButton = { TextButton(onClick = { licenses = false }) { Text("Close") } })
}

@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, Modifier.padding(start = 4.dp, bottom = 9.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Card(shape = SettingsShape, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
internal fun SettingsToggle(title: String, subtitle: String?, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(value, change, colors = SwitchDefaults.colors(checkedThumbColor = androidx.compose.ui.graphics.Color.White, checkedTrackColor = MaterialTheme.colorScheme.primary))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

@Composable
internal fun SettingsLink(title: String, subtitle: String, click: () -> Unit, monospace: Boolean = false) {
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

@Composable
internal fun TransferControls(viewModel: DownloadsViewModel, applied: () -> Unit = {}) {
    val savedSegments by viewModel.segments.collectAsStateWithLifecycle()
    val savedConcurrent by viewModel.concurrent.collectAsStateWithLifecycle()
    val savedSpeed by viewModel.speed.collectAsStateWithLifecycle()
    var segments by remember(savedSegments) { mutableIntStateOf(savedSegments) }
    var concurrent by remember(savedConcurrent) { mutableIntStateOf(savedConcurrent) }
    var speed by remember(savedSpeed) { mutableStateOf(savedSpeed.takeIf { it > 0 }?.toString() ?: "512") }
    var limited by remember(savedSpeed) { mutableStateOf(savedSpeed > 0) }
    var pending by remember { mutableStateOf<Triple<Int, Int, Long>?>(null) }
    val onApplied by rememberUpdatedState(applied)
    val currentPending by rememberUpdatedState(pending)
    DisposableEffect(viewModel) {
        onDispose { currentPending?.let { viewModel.setTransfer(it.first, it.second, it.third) } }
    }
    fun submit() {
        val rate = if (limited) speed.toLongOrNull()?.takeIf { it in 1..Long.MAX_VALUE / 1024 } ?: run { pending = null; return } else 0L
        pending = Triple(segments, concurrent, rate)
    }
    LaunchedEffect(pending) {
        val change = pending ?: return@LaunchedEffect
        delay(300)
        viewModel.setTransfer(change.first, change.second, change.third)
    }
    LaunchedEffect(savedSegments, savedConcurrent, savedSpeed) {
        if (pending == Triple(savedSegments, savedConcurrent, savedSpeed)) { pending = null; onApplied() }
    }
    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
        SettingSlider("Concurrent downloads", concurrent, 10, { concurrent = it }, ::submit)
        SettingSlider("Segments per file", segments, 32, { segments = it }, ::submit)
        SettingsToggle("Speed limit", if (limited) "$speed KiB/s" else "Unlimited · shared by all downloads", limited) { limited = it; submit() }
        if (limited) {
            OutlinedTextField(speed, { speed = it; submit() }, label = { Text("KiB/s") }, singleLine = true,
                isError = speed.toLongOrNull()?.let { it in 1..Long.MAX_VALUE / 1024 } != true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(256, 512, 1024, 2048).forEach { rate ->
                    SuggestionChip(onClick = { speed = rate.toString(); submit() }, label = { Text("$rate", style = MaterialTheme.typography.bodySmall) })
                }
            }
        }
    }
}

@Composable
private fun SettingSlider(label: String, value: Int, maximum: Int, change: (Int) -> Unit, finished: () -> Unit) {
    val tick = rememberUiTick()
    Row { Text(label, Modifier.weight(1f)); Text("$value", color = MaterialTheme.colorScheme.onPrimaryContainer) }
    Slider(value.toFloat(), { val next = it.roundToInt(); if (next != value) { tick(); change(next) } }, valueRange = 1f..maximum.toFloat(), steps = maximum - 2, onValueChangeFinished = finished)
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(1, maximum / 2, maximum).forEach { Text("$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
