package com.alal.downloader.feature.downloads

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alal.downloader.BuildConfig
import com.alal.downloader.core.engine.*
import com.alal.downloader.ui.*
import com.alal.downloader.ui.components.*
import com.alal.downloader.ui.theme.PillShape
import kotlinx.coroutines.*

/** Download dashboard using the existing state and command boundary. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(viewModel: DownloadsViewModel, reopen: (DownloadState) -> Unit, modifier: Modifier = Modifier, chooseFolder: () -> Unit = {}, openBrowser: () -> Unit, openSettings: () -> Unit, addLink: (String) -> Unit) {
    val live by viewModel.downloads.collectAsStateWithLifecycle()
    val latest by rememberUpdatedState(live)
    var downloads by remember { mutableStateOf(live) }
    LaunchedEffect(Unit) { while (isActive) { downloads = latest; delay(500) } }
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
    ModalNavigationDrawer(drawerState = drawer, modifier = modifier, drawerContent = {
        ModalDrawerSheet(Modifier.widthIn(max = 330.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text("Alal Downloader", style = MaterialTheme.typography.titleLarge)
                Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall)
                Text("TYPES", Modifier.padding(vertical = 14.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                listOf("Everything", "Torrents", "Compressed", "Documents", "Music", "Videos", "Subtitles", "Photos", "Programs", "Others").forEach { type ->
                    NavigationDrawerItem(selected = category == type, label = { Text(type) }, badge = { Text("${downloads.count { type == "Everything" || fileCategory(it.fileName) == type }}") },
                        icon = { Icon(when (type) { "Videos" -> Icons.Outlined.Videocam; "Music" -> Icons.Outlined.MusicNote; "Photos" -> Icons.Outlined.Image; "Compressed" -> Icons.Outlined.FolderZip; else -> Icons.Outlined.InsertDriveFile }, null) },
                        onClick = { category = type; tick(); scope.launch { drawer.close() } })
                }
                SettingsGroup("DOWNLOAD") {
                    SettingsLink("Download location", tree ?: "/storage/emulated/0/Download/Alal", chooseFolder)
                    TransferControls(viewModel)
                }
                NavigationDrawerItem(selected = false, label = { Text("Settings") }, icon = { Icon(Icons.Outlined.Settings, null) },
                    onClick = { scope.launch { drawer.close(); openSettings() } })
            }
        }
    }) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (selected.isNotEmpty()) selected = emptySet() else scope.launch { drawer.open() } }, modifier = Modifier.size(40.dp)) { Icon(if (selected.isEmpty()) Icons.Outlined.Menu else Icons.Outlined.Close, "Navigation") }
                    Text(if (selected.isEmpty()) "Downloads" else "${selected.size} selected", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    if (selected.isNotEmpty()) {
                        IconButton(onClick = { selected.forEach { viewModel.pause(it) }; selected = emptySet() }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.Pause, "Pause selected") }
                        IconButton(onClick = { selected.forEach { viewModel.resume(it) }; selected = emptySet() }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.PlayArrow, "Resume selected") }
                        IconButton(onClick = { deletion = selected }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.Delete, "Delete selected") }
                    } else {
                        IconButton(onClick = { tick(); searching = !searching; if (!searching) search = "" }, modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant, com.alal.downloader.ui.theme.IconShape)) { Icon(Icons.Outlined.Search, "Search downloads") }
                        Box {
                            IconButton(onClick = { tick(); sortMenu = true }, modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant, com.alal.downloader.ui.theme.IconShape)) { Icon(Icons.Outlined.Sort, "Sort") }
                            DropdownMenu(sortMenu, { sortMenu = false }) { listOf("Date added", "Name", "Size", "Status").forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { sort = value; sortMenu = false }) } }
                        }
                        IconButton(onClick = openBrowser, modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant, com.alal.downloader.ui.theme.IconShape)) { Icon(Icons.Outlined.Language, "Browser") }
                        Box {
                            IconButton(onClick = { tick(); more = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.MoreVert, "More") }
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
                if (searching) OutlinedTextField(search, { search = it }, singleLine = true, placeholder = { Text("Filter by filename") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("All", "Active", "Queued", "Done", "Failed"), key = { it }) { label ->
                        val color by animateColorAsState(if (filter == label) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, tween(150), label = "filter")
                        Surface(onClick = { filter = label; tick() }, shape = PillShape, color = color) {
                            Row(Modifier.padding(horizontal = 15.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                val foreground = if (filter == label) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                Text(label, color = foreground, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                Text("${downloads.count { matches(it, label) }}", color = foreground.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    if (category != "Everything") item { AssistChip(onClick = { category = "Everything" }, label = { Text("$category × Clear", maxLines = 1) }) }
                }
                if (shown.isEmpty()) Column(Modifier.fillMaxWidth().weight(1f).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(Modifier.size(112.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Text("No downloads yet", Modifier.padding(top = 18.dp), style = MaterialTheme.typography.titleMedium)
                    Text("Paste a link or open the browser to start", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { addLink(clipboardDownloadLink(context)) }, shape = PillShape) { Text("Add link") }
                } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(shown, key = { it.id }) { item ->
                        DownloadCard(item, queue[item.id] ?: 1, item.id in selected,
                            click = { if (selected.isEmpty()) details = item.id else selected = if (item.id in selected) selected - item.id else selected + item.id },
                            longClick = { if (haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress); selected = selected + item.id }, action = { action(item) })
                    }
                }
            }
            AddSpeedDial(dial, { dial = !dial }, add = { dial = false; addLink(clipboardDownloadLink(context)) }, clipboard = {
                dial = false; addLink(clipboardDownloadLink(context))
            }, importFile = { dial = false; importFile.launch(arrayOf("text/plain")) },
                batch = { dial = false; input = ""; batch = true })
        }
    }
    deletion?.let { ids ->
        var deleteFile by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { deletion = null }, title = { Text("Delete ${ids.size} downloads?") }, text = {
            Column { Text("Files are kept unless you choose to delete them too."); Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(deleteFile, { deleteFile = it }); Text("Delete with file") } }
        }, confirmButton = { TextButton(onClick = { ids.forEach { viewModel.delete(it, deleteFile) }; selected = selected - ids; deletion = null }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deletion = null }) { Text("Cancel") } })
    }
    downloads.find { it.id == details }?.let { item ->
        ModalBottomSheet(onDismissRequest = { details = null }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(item.fileName, style = MaterialTheme.typography.titleLarge)
                SelectionContainer { Text("URL: ${item.request.url}\nFinal URL: ${item.finalUrl}\nPath: ${item.destinationUri ?: java.io.File(java.io.File(item.request.targetDir, item.id), item.fileName).absolutePath}\nReferrer: ${item.request.referrerPageUrl ?: "—"}\nETag: ${item.eTag ?: "—"}\nLast-Modified: ${item.lastModified ?: "—"}\nHeaders:\n${DownloadPresentation.headers(item.request.headers)}", style = MaterialTheme.typography.bodySmall) }
                SegmentBars(item)
                item.segments.chunked(4).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { part -> Text("#${part.index + 1}: ${if (part.length > 0) (part.downloaded * 100 / part.length).coerceIn(0, 100) else 0}%", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) } } }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("URL", item.request.url)) }) { Text("Copy URL") }
                    TextButton(onClick = { runCatching { context.startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)) }.onFailure { message = "No file manager available" } }) { Text("Open folder") }
                    TextButton(onClick = { runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, item.request.url), "Share download link")) }.onFailure { message = "No sharing app available" } }) { Text("Share") }
                }
                Row {
                    TextButton(onClick = { action(item) }) { Text(if (item.status == DownloadStatus.COMPLETED) "Open" else if (item.status == DownloadStatus.RUNNING) "Pause" else "Resume") }
                    TextButton(onClick = { deletion = setOf(item.id); details = null }) { Text("Delete") }
                    if (item.canRefreshLink()) TextButton(onClick = { details = null; reopen(item) }) { Text("Reopen page") }
                }
                item.error?.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    message?.let { value -> AlertDialog(onDismissRequest = { message = null }, title = { Text("Alal Downloader") }, text = { Text(value) }, confirmButton = { TextButton(onClick = { message = null }) { Text("Close") } }) }
    if (batch) BatchDialog(viewModel, input) { batch = false }
}
@Composable
private fun SegmentBars(state: DownloadState) {
    val foreground = MaterialTheme.colorScheme.primary
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
            drawRect(foreground, offset, Size(width * fraction, size.height))
        }
    }
}

@Composable
private fun BatchDialog(viewModel: DownloadsViewModel, initialInput: String, dismiss: () -> Unit) {
    var input by remember { mutableStateOf(initialInput) }
    var validation by remember { mutableStateOf("Enter one HTTP(S) URL per line") }
    var valid by remember { mutableStateOf(false) }
    val imported by viewModel.importedText.collectAsStateWithLifecycle()
    val busy by viewModel.batchBusy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) viewModel.importText(uri) }
    LaunchedEffect(imported) { if (imported.isNotEmpty()) { input = imported; viewModel.clearImport() } }
    LaunchedEffect(input) {
        valid = false
        val result = withContext(Dispatchers.Default) { runCatching { DownloadPresentation.batch(input) } }
        result.fold(onSuccess = { (urls, invalid) ->
            valid = urls.isNotEmpty() && invalid.isEmpty()
            validation = if (invalid.isEmpty()) "${urls.size} URLs ready (duplicates kept)" else "Invalid lines: ${invalid.take(20).joinToString()}"
        }, onFailure = { validation = it.message ?: "Invalid input" })
    }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text("Add multiple URLs") }, text = {
        Column {
            OutlinedTextField(input, { if (it.length <= DownloadPresentation.MAX_INPUT) { input = it; valid = false } },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 260.dp), minLines = 6, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), label = { Text("One URL per line") }, enabled = !busy)
            Text(validation)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Up to 1000 URLs / 1 MiB UTF-8 text. Current folder and segment defaults apply. Magnets are skipped: Torrent not supported yet.")
            TextButton(onClick = { picker.launch(arrayOf("text/plain")) }, enabled = !busy) { Icon(Icons.Outlined.UploadFile, null); Spacer(Modifier.width(8.dp)); Text("Import .txt file") }
        }
    }, confirmButton = { TextButton(onClick = { viewModel.addBatch(input, dismiss) }, enabled = valid && !busy) { Text(if (busy) "Adding…" else "Add") } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("Cancel") } })
}
