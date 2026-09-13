package com.alal.downloader.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alal.downloader.core.data.HistoryEntry
import com.alal.downloader.feature.browser.*
import com.alal.downloader.ui.components.HistoryFavicon
import com.alal.downloader.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(repository: HistoryRepository, session: BrowserSession, modifier: Modifier = Modifier, back: () -> Unit) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var clearDialog by remember { mutableStateOf(false) }
    val entries by remember(repository, query) { repository.entries(query) }.collectAsStateWithLifecycle(emptyList())
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val tick = rememberUiTick()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(30_000) } }
    val groups = entries.groupBy { HistoryPolicy.group(it.visitedAt, now) }
    fun remove(entry: HistoryEntry, undo: Boolean) {
        tick()
        scope.launch {
            try {
                repository.delete(entry)
                if (snackbar.showSnackbar("Removed", if (undo) "Undo" else null) == SnackbarResult.ActionPerformed) repository.restore(entry)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { snackbar.showSnackbar("Could not update history") }
        }
    }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.Outlined.ArrowBack, "Back to Browser") }
                if (searching) OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Search history") })
                else Text("History", Modifier.weight(1f), fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                    Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, "Search history")
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "History menu") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Clear history") }, onClick = { menu = false; clearDialog = true })
                    }
                }
            }
            if (entries.isEmpty()) Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(112.dp).background(RunBg, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.History, null, Modifier.size(72.dp), tint = Accent2)
                }
                Text(if (query.isBlank()) "No history yet" else "No matching history", Modifier.padding(top = 18.dp), fontWeight = FontWeight.SemiBold)
                Text(if (query.isBlank()) "Pages you visit will appear here" else "Try another search", color = Dim)
            } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                groups.forEach { (label, rows) ->
                    stickyHeader(key = label) {
                        Text(label, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp, vertical = 10.dp),
                            fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold, color = Accent2)
                    }
                    items(rows, key = { it.id }) { entry ->
                        val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                            if (value != SwipeToDismissBoxValue.Settled) remove(entry, true)
                            false
                        })
                        SwipeToDismissBox(dismiss, backgroundContent = {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(16.dp), contentAlignment = Alignment.CenterEnd) {
                                Icon(Icons.Outlined.Delete, "Delete")
                            }
                        }) {
                            HistoryRow(entry, open = { tick(); session.navigate(entry.url); back() }, newTab = { session.newTab(entry.url); back() },
                                copy = { session.copyLink(entry.url) }, delete = { remove(entry, false) })
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    if (clearDialog) AlertDialog(onDismissRequest = { clearDialog = false }, title = { Text("Clear history") }, text = {
        Column {
            listOf("Last hour", "Today", "Everything").forEach { range ->
                TextButton(onClick = {
                    clearDialog = false
                    snackbar.currentSnackbarData?.dismiss()
                    scope.launch {
                        try {
                            val time = System.currentTimeMillis()
                            repository.clear(when (range) { "Last hour" -> time - 3_600_000; "Today" -> HistoryPolicy.startOfDay(time); else -> null })
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { snackbar.showSnackbar("Could not clear history") }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(range) }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("Cancel") } })
}

@Composable
private fun HistoryRow(entry: HistoryEntry, open: () -> Unit, newTab: () -> Unit, copy: () -> Unit, delete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).clickable(onClick = open).padding(start = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        HistoryFavicon(entry.faviconUrl)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(entry.title, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${entry.host} · ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(entry.visitedAt))}", fontSize = 12.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "History entry actions") }
            DropdownMenu(menu, { menu = false }) {
                listOf("Open in new tab" to newTab, "Copy link" to copy, "Delete" to delete).forEach { (label, action) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; action() })
                }
            }
        }
    }
}