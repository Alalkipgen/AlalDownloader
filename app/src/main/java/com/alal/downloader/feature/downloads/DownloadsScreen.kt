package com.alal.downloader.feature.downloads

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alal.downloader.core.engine.DownloadState
import com.alal.downloader.core.engine.DownloadStatus
import com.alal.downloader.core.engine.canRefreshLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** Filtered transfer list with explicit confirmation for destructive actions. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DownloadsScreen(viewModel: DownloadsViewModel, reopen: (DownloadState) -> Unit, modifier: Modifier = Modifier) {
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("All") }
    var details by remember { mutableStateOf<DownloadState?>(null) }
    var deletion by remember { mutableStateOf<DownloadState?>(null) }
    var batch by rememberSaveable { mutableStateOf(false) }
    var openError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Downloads", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(12.dp))
            TextButton(onClick = { batch = true }) { Text("Add URLs") }
        }
        TabRow(selectedTabIndex = DownloadPresentation.filters.indexOf(filter)) {
            DownloadPresentation.filters.forEach { label ->
                Tab(selected = filter == label, onClick = { filter = label }, text = { Text(label) })
            }
        }
        val shown = downloads.filter { DownloadPresentation.matches(it, filter) }
        if (shown.isEmpty()) Text("No downloads in $filter", Modifier.padding(24.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.id }) { item ->
                val pause = item.status in setOf(DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.WAITING_FOR_NETWORK)
                val resume = item.status in setOf(DownloadStatus.PAUSED, DownloadStatus.FAILED, DownloadStatus.CANCELLED)
                fun toggle() { if (pause) viewModel.pause(item.id) else if (resume) viewModel.resume(item.id) }
                val currentToggle by rememberUpdatedState(newValue = { toggle() })
                val currentItem by rememberUpdatedState(item)
                val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                    when (value) {
                        SwipeToDismissBoxValue.StartToEnd -> currentToggle()
                        SwipeToDismissBoxValue.EndToStart -> deletion = currentItem
                        else -> Unit
                    }
                    false
                })
                SwipeToDismissBox(state = swipe, enableDismissFromStartToEnd = pause || resume,
                    backgroundContent = {
                        Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (pause) "Pause" else if (resume) "Resume" else "")
                            Text("Delete")
                        }
                    }) {
                    Card(Modifier.fillMaxWidth().combinedClickable(onLongClick = { details = item }, onClick = {
                        if (item.status == DownloadStatus.COMPLETED) {
                            scope.launch {
                                try { DownloadFiles.open(context, item) }
                                catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    openError = failure.message ?: "Cannot open file"
                                }
                            }
                        } else details = item
                    })) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(item.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Text("${DownloadPresentation.bytes(item.downloadedBytes)} / ${DownloadPresentation.bytes(item.totalBytes)} · ${DownloadPresentation.percent(item)?.let { "$it%" } ?: "—"}")
                            Text("${DownloadPresentation.bytes(item.speedBytesPerSecond)}/s · ETA ${DownloadPresentation.eta(item)}", style = MaterialTheme.typography.bodySmall)
                            SegmentBars(item)
                            SuggestionChip(onClick = { details = item }, label = { Text(item.status.name.replace('_', ' ')) })
                            item.error?.message?.let { Text(it, color = MaterialTheme.colorScheme.error, maxLines = 3) }
                            Row {
                                if (pause || resume) TextButton(onClick = { toggle() }) { Text(if (pause) "Pause" else "Resume") }
                                TextButton(onClick = { deletion = item }) { Text("Delete") }
                                TextButton(onClick = { details = item }) { Text("Details") }
                            }
                            if (item.canRefreshLink()) TextButton(onClick = { reopen(item) }) { Text("Reopen page") }
                        }
                    }
                }
            }
        }
    }
    deletion?.let { item ->
        var deleteFile by remember(item.id) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { deletion = null }, title = { Text("Delete download?") }, text = {
            Column {
                Text(item.fileName)
                Row { Checkbox(deleteFile, { deleteFile = it }); Text("Delete file too", Modifier.padding(top = 12.dp)) }
                Text("Without this option, the file is kept. An unfinished file may be incomplete.")
            }
        }, confirmButton = { TextButton(onClick = { viewModel.delete(item.id, deleteFile); deletion = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deletion = null }) { Text("Cancel") } })
    }
    details?.let { selected ->
        val item = downloads.find { it.id == selected.id } ?: selected
        AlertDialog(onDismissRequest = { details = null }, title = { Text("Download details") }, text = {
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Filename: ${item.fileName}\nURL: ${item.request.url}\nFinal URL: ${item.finalUrl}\nReferrer: ${item.request.referrerPageUrl ?: "—"}\nDestination: ${item.destinationUri ?: java.io.File(java.io.File(item.request.targetDir, item.id), item.fileName).absolutePath}\nETag: ${item.eTag ?: "—"}\nLast-Modified: ${item.lastModified ?: "—"}\nHeaders:\n${DownloadPresentation.headers(item.request.headers)}")
                }
            }
        }, confirmButton = { TextButton(onClick = { details = null }) { Text("Close") } })
    }
    openError?.let { message -> AlertDialog(onDismissRequest = { openError = null }, title = { Text("Cannot open download") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = { openError = null }) { Text("Close") } }) }
    if (batch) BatchDialog(viewModel) { batch = false }
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
private fun BatchDialog(viewModel: DownloadsViewModel, dismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
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
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp), label = { Text("One URL per line") }, enabled = !busy)
            Text(validation)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Up to 1000 URLs / 1 MiB UTF-8 text. Current folder and segment defaults apply.")
            TextButton(onClick = { picker.launch(arrayOf("text/plain")) }, enabled = !busy) { Text("Import .txt file") }
        }
    }, confirmButton = { TextButton(onClick = { viewModel.addBatch(input, dismiss) }, enabled = valid && !busy) { Text(if (busy) "Adding…" else "Add") } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("Cancel") } })
}