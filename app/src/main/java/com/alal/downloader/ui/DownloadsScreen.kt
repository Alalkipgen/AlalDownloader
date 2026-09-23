package com.alal.downloader.feature.downloads

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alal.downloader.BuildConfig
import com.alal.downloader.core.engine.*
import com.alal.downloader.ui.*
import com.alal.downloader.ui.components.*
import com.alal.downloader.ui.theme.*
import kotlinx.coroutines.*

private val CATEGORIES = listOf("Everything", "Torrents", "Compressed", "Documents", "Music", "Videos", "Subtitles", "Photos", "Programs", "Others")
private val FILTERS = listOf("All", "Active", "Queued", "Done", "Failed")

/** Download dashboard using the existing state and command boundary. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(viewModel: DownloadsViewModel, reopen: (DownloadState) -> Unit, modifier: Modifier = Modifier, chooseFolder: () -> Unit = {}, openBrowser: () -> Unit, openSettings: () -> Unit, addLink: (String) -> Unit) {
    val live by viewModel.downloads.collectAsStateWithLifecycle()
    val latest by rememberUpdatedState(live)
    var downloads by remember { mutableStateOf(live) }
    val speedHistory = remember { mutableStateListOf<Float>() }
    LaunchedEffect(Unit) {
        while (isActive) {
            downloads = latest
            speedHistory.add(latest.filter { it.status == DownloadStatus.RUNNING }.sumOf { it.speedBytesPerSecond }.toFloat())
            if (speedHistory.size > 48) speedHistory.removeAt(0)
            delay(500)
        }
    }
    var filter by rememberSaveable { mutableStateOf("All") }
    var category by rememberSaveable { mutableStateOf("Everything") }
    var search by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf("Date added") }
    var sortMenu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var dial by remember { mutableStateOf(false) }
    var batch by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var details by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var deletion by remember { mutableStateOf<Set<String>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tick = rememberUiTick()
    val appearance = LocalAppearance.current
    val haptic = LocalHapticFeedback.current
    val haptics = LocalHapticsEnabled.current
    val tree by viewModel.treeUri.collectAsStateWithLifecycle()
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { input = ""; viewModel.importText(uri); batch = true }
    }
    val exportText by rememberUpdatedState(downloads.joinToString("\n") { it.request.url })
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            try { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(exportText) } ?: error("Cannot open destination") }; message = "List exported" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { message = failure.message }
        }
    }
    fun matches(item: DownloadState, label: String): Boolean = when (label) {
        "Active" -> item.status == DownloadStatus.RUNNING
        "Queued" -> item.status == DownloadStatus.QUEUED
        "Done" -> item.status == DownloadStatus.COMPLETED
        "Failed" -> item.status == DownloadStatus.FAILED
        else -> true
    }
    fun action(item: DownloadState) {
        when (item.status) {
            DownloadStatus.RUNNING, DownloadStatus.WAITING_FOR_NETWORK, DownloadStatus.WAITING_FOR_WIFI -> viewModel.pause(item.id)
            DownloadStatus.NEEDS_BROWSER -> reopen(item)
            DownloadStatus.COMPLETED -> scope.launch {
                try { DownloadFiles.open(context, item) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { message = failure.message ?: "Cannot open file" }
            }
            else -> viewModel.resume(item.id)
        }
    }
    val shown by remember(downloads, filter, category, search, sort) { derivedStateOf {
        val list = downloads.filter { matches(it, filter) && (category == "Everything" || fileCategory(it.fileName) == category) && it.fileName.contains(search, true) }
        when (sort) { "Name" -> list.sortedBy { it.fileName.lowercase() }; "Size" -> list.sortedByDescending { it.totalBytes }; "Status" -> list.sortedBy { it.status.ordinal }; else -> list }
    } }
    val queue = remember(downloads) { downloads.filter { it.status == DownloadStatus.QUEUED }.mapIndexed { index, item -> item.id to index + 1 }.toMap() }
    val running = remember(downloads) { downloads.count { it.status == DownloadStatus.RUNNING } }
    val speed = remember(downloads) { downloads.filter { it.status == DownloadStatus.RUNNING }.sumOf { it.speedBytesPerSecond } }
    ModalNavigationDrawer(drawerState = drawer, modifier = modifier, drawerContent = {
        ModalDrawerSheet(Modifier.widthIn(max = 336.dp), drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)) {
            DrawerContent(downloads, category, tree, chooseFolder,
                select = { type -> category = type; tick(); scope.launch { drawer.close() } },
                settings = { scope.launch { drawer.close(); openSettings() } },
                browser = { scope.launch { drawer.close(); openBrowser() } })
        }
    }) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (selected.isNotEmpty()) selected = emptySet() else scope.launch { drawer.open() } }, modifier = Modifier.size(40.dp)) {
                        Icon(if (selected.isEmpty()) Icons.Outlined.Menu else Icons.Outlined.Close, "Navigation")
                    }
                    Spacer(Modifier.weight(1f))
                    if (selected.isNotEmpty()) {
                        Text("${selected.size} selected", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = { selected.forEach { viewModel.pause(it) }; selected = emptySet() }, modifier = Modifier.size(38.dp)) { Icon(Icons.Outlined.Pause, "Pause selected") }
                        IconButton(onClick = { selected.forEach { viewModel.resume(it) }; selected = emptySet() }, modifier = Modifier.size(38.dp)) { Icon(Icons.Outlined.PlayArrow, "Resume selected") }
                        IconButton(onClick = { deletion = selected }, modifier = Modifier.size(38.dp)) { Icon(Icons.Outlined.Delete, "Delete selected") }
                    } else {
                        Row(Modifier.clip(PillShape).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { tick(); searching = !searching; if (!searching) search = "" }, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.Search, "Search downloads", Modifier.size(20.dp)) }
                            Box {
                                IconButton(onClick = { tick(); sortMenu = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.Sort, "Sort", Modifier.size(20.dp)) }
                                DropdownMenu(sortMenu, { sortMenu = false }) { listOf("Date added", "Name", "Size", "Status").forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { sort = value; sortMenu = false }) } }
                            }
                            IconButton(onClick = openBrowser, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.Language, "Browser", Modifier.size(20.dp)) }
                            Box {
                                IconButton(onClick = { tick(); more = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.MoreVert, "More", Modifier.size(20.dp)) }
                                DropdownMenu(more, { more = false }) {
                                    listOf("Pause all", "Resume all", "Clear finished", "Download location", "Export list", "Import list", "Settings", "About").forEach { label ->
                                        DropdownMenuItem(text = { Text(label) }, onClick = {
                                            more = false
                                            when (label) {
                                                "Pause all" -> downloads.filter { it.status in setOf(DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.WAITING_FOR_NETWORK, DownloadStatus.WAITING_FOR_WIFI) }.forEach { viewModel.pause(it.id) }
                                                "Resume all" -> downloads.filter { it.status in setOf(DownloadStatus.PAUSED, DownloadStatus.FAILED, DownloadStatus.CANCELLED) }.forEach { viewModel.resume(it.id) }
                                                "Clear finished" -> deletion = downloads.filter { it.status == DownloadStatus.COMPLETED }.map { it.id }.toSet().takeIf { it.isNotEmpty() }
                                                "Download location" -> chooseFolder()
                                                "Export list" -> export.launch("alal-downloads.txt")
                                                "Import list" -> importFile.launch(arrayOf("text/plain"))
                                                "Settings" -> openSettings()
                                                else -> message = "Alal Downloader ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                                            }
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
                Text("Downloads", Modifier.padding(start = 18.dp, top = 2.dp, bottom = 10.dp), style = MaterialTheme.typography.headlineMedium)
                AnimatedVisibility(searching) {
                    TextField(search, { search = it }, singleLine = true, placeholder = { Text("Filter by filename") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = FieldShape,
                        colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                }
                LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(FILTERS, key = { it }) { label ->
                        FilterPill(label, "${downloads.count { matches(it, label) }}", filter == label) { filter = label; tick() }
                    }
                    if (category != "Everything") item {
                        Row(Modifier.clip(PillShape).background(categoryColor(category).copy(alpha = 0.18f))
                            .clickable { category = "Everything" }.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(category, style = MaterialTheme.typography.labelLarge, color = categoryColor(category), maxLines = 1)
                            Icon(Icons.Outlined.Close, "Clear category filter", Modifier.size(15.dp), tint = categoryColor(category))
                        }
                    }
                }
                if (running > 0 || speedHistory.any { it > 0f }) SpeedStrip(
                    "${DownloadPresentation.bytes(speed)}/s", running, speedHistory.toList(), appearance.speedGraph,
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                if (shown.isEmpty()) EmptyDownloads(Modifier.fillMaxWidth().weight(1f)) { addLink(clipboardDownloadLink(context)) }
                else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(shown, key = { it.id }) { item ->
                        DownloadCard(item, queue[item.id] ?: 1, item.id in selected,
                            click = { if (selected.isEmpty()) details = item.id else selected = if (item.id in selected) selected - item.id else selected + item.id },
                            longClick = { if (haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress); selected = selected + item.id }, action = { action(item) })
                    }
                }
            }
            AddSpeedDial(dial, { dial = !dial }, add = { dial = false; addLink(clipboardDownloadLink(context)) },
                clipboard = { dial = false; addLink(clipboardDownloadLink(context)) },
                importFile = { dial = false; importFile.launch(arrayOf("text/plain")) },
                batch = { dial = false; input = ""; batch = true },
                torrent = { dial = false; addLink(clipboardDownloadLink(context)) })
        }
    }
    deletion?.let { ids ->
        var deleteFile by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { deletion = null }, shape = SettingsShape, title = { Text("Delete ${ids.size} downloads?") }, text = {
            Column { Text("Files are kept unless you choose to delete them too."); Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(deleteFile, { deleteFile = it }); Text("Delete with file") } }
        }, confirmButton = { TextButton(onClick = { ids.forEach { viewModel.delete(it, deleteFile) }; selected = selected - ids; deletion = null }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deletion = null }) { Text("Cancel") } })
    }
    downloads.find { it.id == details }?.let { item ->
        DetailSheet(item, speedHistory.toList(), appearance.speedGraph, dismiss = { details = null },
            action = { action(item) }, delete = { deletion = setOf(item.id); details = null },
            reopen = { details = null; reopen(item) }, notify = { message = it })
    }
    message?.let { value -> AlertDialog(onDismissRequest = { message = null }, shape = SettingsShape, title = { Text("Alal Downloader") }, text = { Text(value) }, confirmButton = { TextButton(onClick = { message = null }) { Text("Close") } }) }
    if (batch) BatchSheet(viewModel, input) { batch = false }
}

/** Pill filter with its live count, filled with the brand gradient while selected. */
@Composable
private fun FilterPill(label: String, count: String, selected: Boolean, click: () -> Unit) {
    val brand = LocalBrandColors.current
    Row(Modifier.clip(PillShape)
        .background(if (selected) brand.horizontal() else SolidColor(MaterialTheme.colorScheme.surfaceVariant))
        .clickable(onClick = click).padding(horizontal = 15.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        val foreground = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
        Text(label, color = foreground, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        Text(count, color = foreground.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun EmptyDownloads(modifier: Modifier, add: () -> Unit) {
    val brand = LocalBrandColors.current
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(112.dp).background(brand.base.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.CloudDownload, null, Modifier.size(56.dp), tint = brand.bright)
        }
        Text("No downloads yet", Modifier.padding(top = 18.dp), style = MaterialTheme.typography.titleMedium)
        Text("Paste a link or open the browser to start", Modifier.padding(top = 6.dp, bottom = 18.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PrimaryAction("Add link", true, onClick = add)
    }
}

/** Navigation drawer: identity, free space, type filters and the two global destinations. */
@Composable
private fun DrawerContent(downloads: List<DownloadState>, category: String, tree: String?, chooseFolder: () -> Unit,
                          select: (String) -> Unit, settings: () -> Unit, browser: () -> Unit) {
    val brand = LocalBrandColors.current
    val context = LocalContext.current
    var storage by remember { mutableStateOf(Triple(0f, "Storage", "")) }
    LaunchedEffect(Unit) {
        storage = withContext(Dispatchers.IO) {
            runCatching {
                val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
                val free = stat.availableBytes
                val total = stat.totalBytes.coerceAtLeast(1)
                Triple((total - free).toFloat() / total,
                    "${Formatter.formatShortFileSize(context, free)} free of ${Formatter.formatShortFileSize(context, total)}",
                    "${(total - free) * 100 / total}%")
            }.getOrElse { Triple(0f, "Storage unavailable", "") }
        }
    }
    Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BrandBadge(Icons.Outlined.Download, 46.dp)
            Column {
                Text("Alal Downloader", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().clip(CardShape).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = chooseFolder).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StorageRing(storage.first, storage.third)
            Column(Modifier.weight(1f)) {
                Text(storage.second, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tree ?: "Downloads folder \u00b7 /Download/Alal", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text("TYPES", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CATEGORIES.forEach { type ->
            val active = category == type
            val count = downloads.count { type == "Everything" || fileCategory(it.fileName) == type }
            Row(Modifier.fillMaxWidth().clip(PillShape)
                .background(if (active) brand.base.copy(alpha = 0.16f) else Color.Transparent)
                .clickable { select(type) }.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CategoryTile(type, 30.dp, 10.dp)
                Text(type, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                    color = if (active) brand.bright else MaterialTheme.colorScheme.onSurface)
                Text("$count", Modifier.clip(PillShape).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryAction("Settings", true, Modifier.weight(1f), settings)
            SecondaryAction("Browser", icon = Icons.Outlined.Language, onClick = browser)
        }
    }
}

/** Per-download detail sheet: live ring, raw metadata and the row-level actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailSheet(item: DownloadState, history: List<Float>, showGraph: Boolean, dismiss: () -> Unit,
                        action: () -> Unit, delete: () -> Unit, reopen: () -> Unit, notify: (String) -> Unit) {
    val context = LocalContext.current
    val brand = LocalBrandColors.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val percent = DownloadPresentation.percent(item) ?: 0
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet, shape = SheetShape,
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                FileTypeTile(item.fileName, 52.dp)
                Column(Modifier.weight(1f)) {
                    Text(item.fileName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${DownloadPresentation.bytes(item.downloadedBytes)} of ${DownloadPresentation.bytes(item.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StorageRing(percent / 100f, "$percent%", 58.dp)
            }
            if (showGraph && item.status == DownloadStatus.RUNNING) Sparkline(history, brand.bright, Modifier.fillMaxWidth().height(40.dp))
            SegmentBars(item)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryAction(if (item.status == DownloadStatus.COMPLETED) "Open" else if (item.status == DownloadStatus.RUNNING) "Pause" else "Resume",
                    true, Modifier.weight(1f), action)
                SecondaryAction("Delete", icon = Icons.Outlined.Delete, onClick = delete)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryAction("Copy URL", icon = Icons.Outlined.ContentCopy) {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("URL", item.request.url))
                }
                SecondaryAction("Open folder", icon = Icons.Outlined.FolderOpen) {
                    runCatching { context.startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)) }.onFailure { notify("No file manager available") }
                }
                SecondaryAction("Share", icon = Icons.Outlined.Share) {
                    runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, item.request.url), "Share download link")) }.onFailure { notify("No sharing app available") }
                }
                if (item.canRefreshLink()) SecondaryAction("Reopen page", icon = Icons.Outlined.Language, onClick = reopen)
            }
            SelectionContainer {
                Text("URL: ${item.request.url}\nFinal URL: ${item.finalUrl}\nPath: ${item.destinationUri ?: java.io.File(java.io.File(item.request.targetDir, item.id), item.fileName).absolutePath}\nReferrer: ${item.request.referrerPageUrl ?: "\u2014"}\nETag: ${item.eTag ?: "\u2014"}\nLast-Modified: ${item.lastModified ?: "\u2014"}\nParts: ${item.segments.size.takeIf { it > 0 } ?: item.request.segmentCount ?: 1} \u00b7 Resume: ${if (item.acceptsRanges) "Yes" else "No"}\nHeaders:\n${DownloadPresentation.headers(item.request.headers)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item.error?.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun SegmentBars(state: DownloadState) {
    val brand = LocalBrandColors.current
    val background = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        val count = state.segments.size.coerceAtLeast(1)
        val gap = 2.dp.toPx()
        val width = ((size.width - gap * (count - 1)) / count).coerceAtLeast(0f)
        repeat(count) { index ->
            val segment = state.segments.getOrNull(index)
            val fraction = if (state.status == DownloadStatus.COMPLETED) 1f else
                if (segment != null && segment.length > 0) (segment.downloaded.toDouble() / segment.length).toFloat().coerceIn(0f, 1f) else 0f
            val offset = Offset(index * (width + gap), 0f)
            drawRect(background, offset, Size(width, size.height))
            drawRect(brand.base, offset, Size(width * fraction, size.height))
        }
    }
}

/** Multi-URL intake: paste or import a list, then queue every valid line at once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatchSheet(viewModel: DownloadsViewModel, initialInput: String, dismiss: () -> Unit) {
    var input by remember { mutableStateOf(initialInput) }
    var validation by remember { mutableStateOf("Enter one HTTP(S) URL per line") }
    var count by remember { mutableIntStateOf(0) }
    var valid by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val imported by viewModel.importedText.collectAsStateWithLifecycle()
    val busy by viewModel.batchBusy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) viewModel.importText(uri) }
    LaunchedEffect(imported) { if (imported.isNotEmpty()) { input = imported; viewModel.clearImport() } }
    LaunchedEffect(input) {
        valid = false
        val result = withContext(Dispatchers.Default) { runCatching { DownloadPresentation.batch(input) } }
        result.fold(onSuccess = { (urls, invalid) ->
            count = urls.size
            valid = urls.isNotEmpty() && invalid.isEmpty()
            validation = if (invalid.isEmpty()) "${urls.size} links detected" else "Invalid lines: ${invalid.take(20).joinToString()}"
        }, onFailure = { count = 0; validation = it.message ?: "Invalid input" })
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) dismiss() }, sheetState = sheet, shape = SheetShape,
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Add multiple URLs", style = MaterialTheme.typography.titleLarge)
            TextField(input, { if (it.length <= DownloadPresentation.MAX_INPUT) { input = it; valid = false } },
                modifier = Modifier.fillMaxWidth().heightIn(min = 170.dp, max = 260.dp), minLines = 6, shape = FieldShape,
                placeholder = { Text("https://example.com/file1.zip") }, enabled = !busy,
                colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(validation, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SecondaryAction("Paste from clipboard", !busy, icon = Icons.Outlined.ContentPaste) {
                    val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    if (clip.isNotBlank()) input = if (input.isBlank()) clip else input.trimEnd() + "\n" + clip
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text("Up to 1000 URLs / 1 MiB UTF-8 text. Current folder and segment defaults apply. Magnets are skipped: Torrent not supported yet.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SecondaryAction("Import .txt", !busy, icon = Icons.Outlined.UploadFile) { picker.launch(arrayOf("text/plain")) }
                PrimaryAction(if (busy) "Adding\u2026" else if (count > 0) "Add $count downloads" else "Add downloads",
                    valid && !busy, Modifier.weight(1f)) { viewModel.addBatch(input, dismiss) }
            }
        }
    }
}
