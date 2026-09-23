package com.alal.downloader.feature.downloads

import android.os.Build
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alal.downloader.BuildConfig
import com.alal.downloader.ui.*
import com.alal.downloader.ui.components.PrimaryAction
import com.alal.downloader.ui.theme.*
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
    val appearance = LocalAppearance.current
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
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = navigateBack, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.ArrowBack, "Back to Downloads") }
            }
            Text("Settings", Modifier.padding(start = 18.dp, bottom = 12.dp), style = MaterialTheme.typography.headlineMedium)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { SettingsGroup("TRANSFERS") { TransferControls(viewModel) { scope.launch { snackbar.showSnackbar("Applied") } } } }
                item { SettingsGroup("STORAGE") {
                    SettingsLink("Download location", tree ?: "/storage/emulated/0/Download/Alal", chooseFolder, monospace = true, icon = Icons.Outlined.Folder)
                    if (tree != null && Build.VERSION.SDK_INT >= 29) TextButton(onClick = { viewModel.setTree(null) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Use Downloads folder") }
                } }
                item { SettingsGroup("APPEARANCE") { AppearanceControls(theme, viewModel::setTheme, appearance) } }
                item { SettingsGroup("RELIABILITY") {
                    SettingsToggle("Auto-resume interrupted", "Retries when the network returns", autoResume, viewModel::setAutoResume, Icons.Outlined.Autorenew)
                    SettingsToggle("Wi-Fi only", "Pause on mobile data", wifi, viewModel::setWifiOnly, Icons.Outlined.Wifi)
                    SettingsToggle("Haptic feedback", "On start, finish and errors", haptics, settings::setHaptics, Icons.Outlined.Vibration)
                    StatusRow("Battery optimization", if (allowed) "Allowed" else "Action needed", allowed,
                        enabled = !allowed) { viewModel.backgroundAccess.requestBattery() }
                    if (viewModel.backgroundAccess.hasAutostart) StatusRow("Autostart", "Action needed", false, enabled = true) { viewModel.backgroundAccess.openAutostart() }
                    TextButton(onClick = background, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Background download help") }
                } }
                item { SettingsGroup("BROWSER") {
                    SettingsToggle("Block obvious popups", null, browser.blockPopups, { value ->
                        browser.blockPopups = value; browser.settings.blockPopups = value
                        browser.tabs.forEach { it.webView.settings.javaScriptCanOpenWindowsAutomatically = !value }
                    }, Icons.Outlined.Block)
                    SettingsToggle("Desktop mode", null, browser.desktop, browser::setDesktopMode, Icons.Outlined.DesktopWindows)
                    SettingsToggle("Clipboard suggestions", "While the app is in the foreground", browser.clipboardEnabled, { browser.clipboardEnabled = it; browser.settings.clipboard = it }, Icons.Outlined.ContentPaste)
                    SettingsToggle("Media candidates", "URL based, not MIME-confirmed", browser.mediaEnabled, { browser.mediaEnabled = it; browser.settings.media = it }, Icons.Outlined.Movie)
                    SettingsLink("Download extensions", browser.extensions, { extensions = true }, icon = Icons.Outlined.Extension)
                } }
                item { SettingsGroup("ABOUT") {
                    SettingsLink("Last crash log", "View, copy or share the saved report", {
                        scope.launch {
                            crashReport = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.alal.downloader.CrashLog.read(context) }
                            showCrash = true
                        }
                    }, icon = Icons.Outlined.BugReport)
                    SettingsLink("Open-source licenses", "AndroidX \u00b7 Kotlin \u00b7 OkHttp \u00b7 Hilt", { licenses = true }, icon = Icons.Outlined.Policy)
                    SettingsLink("GitHub repository", "Alalkipgen/AlalDownloader", { open("https://github.com/Alalkipgen/AlalDownloader") }, icon = Icons.Outlined.Code)
                    Text("Alal Downloader ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    if (showCrash) CrashLogDialog("Last crash log", crashReport) { showCrash = false }
    if (extensions) AlertDialog(onDismissRequest = { extensions = false }, shape = SettingsShape, title = { Text("Download extensions") }, text = {
        OutlinedTextField(browser.extensions, { browser.extensions = it; browser.settings.extensions = it }, label = { Text("Space-separated extensions") })
    }, confirmButton = { TextButton(onClick = { extensions = false }) { Text("Done") } })
    if (licenses) AlertDialog(onDismissRequest = { licenses = false }, shape = SettingsShape, title = { Text("Open-source licenses") }, text = {
        Column {
            Text("AndroidX, Kotlin, kotlinx.coroutines, OkHttp and Dagger/Hilt are distributed under the Apache License 2.0.")
            TextButton(onClick = { open("https://www.apache.org/licenses/LICENSE-2.0") }) { Text("Read Apache License 2.0") }
        }
    }, confirmButton = { TextButton(onClick = { licenses = false }) { Text("Close") } })
}

/** Theme mode, preview tiles, accent choice and the three appearance switches. */
@Composable
private fun AppearanceControls(theme: String, setTheme: (String) -> Unit, appearance: AppearanceState) {
    val context = LocalContext.current
    val brand = LocalBrandColors.current
    val effectiveTheme = if (context.getSharedPreferences("download_settings", 0).contains("theme")) theme else "system"
    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().clip(PillShape).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("light", "dark", "system").forEach { value ->
                val active = effectiveTheme == value
                Box(Modifier.weight(1f).clip(PillShape)
                    .background(if (active) brand.horizontal() else SolidColor(Color.Transparent))
                    .clickable { setTheme(value) }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Text(value.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge,
                        color = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ThemePreview("Light", Color(0xFFF5F4FA), Color.White, Color(0xFF111118), effectiveTheme == "light" && !appearance.amoled, Modifier.weight(1f)) {
                setTheme("light"); appearance.updateAmoled(false)
            }
            ThemePreview("Dark", Bg, Surface, OnBg, effectiveTheme == "dark" && !appearance.amoled, Modifier.weight(1f)) {
                setTheme("dark"); appearance.updateAmoled(false)
            }
            ThemePreview("AMOLED black", Color.Black, Color(0xFF0D0D11), OnBg, appearance.amoled, Modifier.weight(1f)) {
                setTheme("dark"); appearance.updateAmoled(true)
            }
        }
        Text("Accent color", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AccentOptions.forEach { option ->
                AccentSwatch(option.hex, Brush.linearGradient(listOf(option.base, option.bright)),
                    !appearance.dynamic && appearance.accent == option.key) {
                    appearance.updateDynamic(false); appearance.updateAccent(option.key)
                }
            }
            if (Build.VERSION.SDK_INT >= 31) AccentSwatch("Dynamic\n(Material You)",
                Brush.sweepGradient(listOf(Color(0xFFFF6B4A), Color(0xFFF59E0B), Color(0xFF10B981), Color(0xFF3B82F6), Color(0xFF7C3AED), Color(0xFFFF6B4A))),
                appearance.dynamic) { appearance.updateDynamic(true) }
        }
        SettingsToggle("Pure black background (AMOLED)", null, appearance.amoled, appearance::updateAmoled)
        SettingsToggle("Colorful file icons", null, appearance.colorfulIcons, appearance::updateColorfulIcons)
        SettingsToggle("Show speed graph", null, appearance.speedGraph, appearance::updateSpeedGraph, divider = false)
    }
}

@Composable
private fun ThemePreview(label: String, background: Color, surface: Color, foreground: Color, selected: Boolean, modifier: Modifier, click: () -> Unit) {
    val brand = LocalBrandColors.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.fillMaxWidth().height(86.dp).clip(RoundedCornerShape(14.dp)).background(background)
            .border(if (selected) 2.dp else 1.dp, if (selected) brand.bright else MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .clickable(onClick = click).padding(9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth(0.55f).height(7.dp).clip(PillShape).background(foreground.copy(alpha = 0.75f)))
            repeat(3) {
                Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(5.dp)).background(surface).padding(3.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(3.dp)).background(brand.base))
                    Box(Modifier.fillMaxWidth(0.7f).height(4.dp).clip(PillShape).background(foreground.copy(alpha = 0.35f)))
                }
            }
        }
        Text(label, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}

@Composable
private fun AccentSwatch(caption: String, brush: Brush, selected: Boolean, click: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(brush)
            .border(if (selected) 3.dp else 0.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
            .clickable(onClick = click))
        Text(caption, Modifier.padding(top = 5.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}

/** Permission-style row whose right side states whether the user still has to act. */
@Composable
private fun StatusRow(title: String, status: String, positive: Boolean, enabled: Boolean, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = click).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (positive) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null,
            Modifier.size(20.dp), tint = if (positive) Ok else Warn)
        Text(title, Modifier.weight(1f).padding(start = 12.dp))
        Text(status, Modifier.clip(PillShape).background((if (positive) Ok else Warn).copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall, color = if (positive) Ok else Warn, maxLines = 1)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, Modifier.padding(start = 6.dp, bottom = 9.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(shape = SettingsShape, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
internal fun SettingsToggle(title: String, subtitle: String?, value: Boolean, change: (Boolean) -> Unit,
                            icon: androidx.compose.ui.graphics.vector.ImageVector? = null, divider: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp).padding(end = 0.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(start = if (icon != null) 12.dp else 0.dp)) {
            Text(title)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(value, change, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = MaterialTheme.colorScheme.primary))
    }
    if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

@Composable
internal fun SettingsLink(title: String, subtitle: String, click: () -> Unit, monospace: Boolean = false,
                          icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(start = if (icon != null) 12.dp else 0.dp)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        SettingsToggle("Speed limit", if (limited) "$speed KiB/s" else "Unlimited \u00b7 shared by all downloads", limited, { limited = it; submit() }, divider = false)
        if (limited) {
            OutlinedTextField(speed, { speed = it; submit() }, label = { Text("KiB/s") }, singleLine = true, shape = FieldShape,
                isError = speed.toLongOrNull()?.let { it in 1..Long.MAX_VALUE / 1024 } != true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(256, 512, 1024, 2048).forEach { rate ->
                    SuggestionChip(onClick = { speed = rate.toString(); submit() }, label = { Text("$rate", style = MaterialTheme.typography.bodySmall) }, shape = PillShape)
                }
            }
        }
    }
}

@Composable
private fun SettingSlider(label: String, value: Int, maximum: Int, change: (Int) -> Unit, finished: () -> Unit) {
    val tick = rememberUiTick()
    val brand = LocalBrandColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Text("$value", Modifier.clip(PillShape).background(brand.base.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall, color = brand.bright)
    }
    Slider(value.toFloat(), { val next = it.roundToInt(); if (next != value) { tick(); change(next) } }, valueRange = 1f..maximum.toFloat(), steps = maximum - 2, onValueChangeFinished = finished)
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(1, maximum / 2, maximum).forEach { Text("$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
