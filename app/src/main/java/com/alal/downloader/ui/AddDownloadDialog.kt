package com.alal.downloader.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.text.format.Formatter
import android.webkit.WebSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alal.downloader.feature.downloads.*
import com.alal.downloader.ui.components.*
import com.alal.downloader.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun clipboardDownloadLink(context: Context): String {
    val text = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
    return text.takeIf { it.startsWith("magnet:", true) || it.startsWith("http://", true) ||
        it.startsWith("https://", true) || Regex("[^\\s/]+\\.[^\\s/]+(?:/[^\\s]*)?").matches(it) }.orEmpty()
}

/** Download form with explicit Connect and guarded asynchronous submission. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddDownloadDialog(downloads: DownloadsViewModel, initialLink: String, openBrowser: (String) -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    val form = remember { AddDownloadViewModel(downloads.prober).apply { link(initialLink) } }
    val state by form.state.collectAsStateWithLifecycle()
    val defaultWifi by downloads.wifiOnly.collectAsStateWithLifecycle()
    val segments by downloads.segments.collectAsStateWithLifecycle()
    val folder by downloads.treeUri.collectAsStateWithLifecycle()
    var wifi by remember { mutableStateOf(defaultWifi) }
    var retry by remember { mutableStateOf(true) }
    var advanced by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var storage by remember { mutableStateOf("\u2014") }
    var lowSpace by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val agent = remember { WebSettings.getDefaultUserAgent(context) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) downloads.setTree(uri)
    }
    LaunchedEffect(folder) {
        val result = withContext(Dispatchers.IO) {
            try {
                val id = folder?.let { DocumentsContract.getTreeDocumentId(Uri.parse(it)) }
                val volume = id?.substringBefore(':')
                val root = if (volume == null || volume == "primary") Environment.getExternalStorageDirectory()
                    else java.io.File("/storage", volume)
                check(root.exists()) { "Storage information unavailable for this provider" }
                val stat = StatFs(root.absolutePath)
                val free = stat.availableBytes
                val total = stat.totalBytes
                "${Formatter.formatFileSize(context, free)} free of ${Formatter.formatFileSize(context, total)}" to (free < 500L * 1024 * 1024)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { (failure.message ?: "Storage information unavailable") to false }
        }
        storage = result.first; lowSpace = result.second
    }
    /** Adds the download (the engine starts it as soon as a slot is free) and closes the sheet. */
    val submit: () -> Unit = {
        if (state.link.startsWith("magnet:", true)) { localError = "Torrent not supported yet" }
        else if (!submitting) {
            submitting = true
            scope.launch {
                try {
                    downloads.addFile(state.link, state.referrer, state.name, state.extension, wifi, retry, agent)
                    dismiss()
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { localError = failure.message ?: "Cannot add download" }
                finally { submitting = false }
            }
        }
    }
    val fileName = listOf(state.name, state.extension).filter { it.isNotBlank() }.joinToString(".")
    ModalBottomSheet(onDismissRequest = { if (!submitting) dismiss() }, sheetState = sheet, shape = SheetShape,
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Add link", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { if (!submitting) dismiss() }, modifier = Modifier.size(34.dp)) { Icon(Icons.Outlined.Close, "Close") }
            }
            TextField(state.link, { form.link(it); localError = null }, modifier = Modifier.fillMaxWidth(), shape = FieldShape,
                placeholder = { Text("https://\u2026 paste or type a URL") }, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Link, null) },
                trailingIcon = {
                    TextButton(onClick = { form.link(clipboardDownloadLink(context)) }) { Text("Paste") }
                },
                colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
            if (fileName.isNotBlank()) Row(Modifier.fillMaxWidth().clip(CardShape).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FileTypeTile(fileName)
                Column(Modifier.weight(1f)) {
                    Text(fileName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val size = state.size?.let { if (it < 0) "unknown size" else Formatter.formatFileSize(context, it) } ?: "size unknown"
                    val resume = state.resume?.let { if (it) "supports resume" else "no resume" } ?: "resume unknown"
                    Text("$size \u00b7 $resume", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionChip("Wi-Fi only", wifi, Icons.Outlined.Wifi) { wifi = !wifi }
                OptionChip("Retry on failure", retry, Icons.Outlined.Autorenew) { retry = !retry }
                OptionChip("$segments segments", false, Icons.Outlined.Layers) { advanced = true }
            }
            Row(Modifier.fillMaxWidth().clip(FieldShape).background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { picker.launch(folder?.let(Uri::parse)) }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Folder, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text("Save to: " + (folder?.let(Uri::decode) ?: "Download/Alal"), style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(storage, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        color = if (lowSpace) Warn else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Outlined.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { advanced = !advanced }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text(if (advanced) "Hide options" else "More options", style = MaterialTheme.typography.labelLarge)
                Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
            }
            AnimatedVisibility(advanced) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(state.referrer, form::referrer, label = { Text("Referrer page link") }, shape = FieldShape,
                        placeholder = { Text("leave empty if not sure") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(state.name, form::name, label = { Text("File name") }, shape = FieldShape, modifier = Modifier.weight(2f), singleLine = true)
                        OutlinedTextField(state.extension, form::extension, label = { Text("Extension") }, shape = FieldShape, modifier = Modifier.weight(1f), singleLine = true)
                    }
                    state.finalUrl?.let { Text("Final URL: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text("Segments and speed limits come from Settings \u00b7 Transfers.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            (localError ?: state.error)?.let { reason ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(reason, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("Alal error", "${state.link}\n$reason"))
                    }) { Icon(Icons.Outlined.ContentCopy, "Copy error") }
                }
            }
            // 1DM parity: a server with a broken certificate can still be downloaded from if the user says so.
            state.insecureHost?.let { host ->
                SecondaryAction("Ignore certificate for $host", !state.busy && !submitting, icon = Icons.Outlined.Warning) {
                    scope.launch { form.probeIgnoringCertificate(agent) }
                }
            }
            if (state.insecure) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Certificate check is off for this host (unsafe)", Modifier.weight(1f), color = Warn, style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !state.busy && !submitting, onClick = form::restoreCertificateCheck) { Text("Restore") }
            }
            if (state.html) SecondaryAction("Open in browser", icon = Icons.Outlined.Language) { openBrowser(state.finalUrl ?: state.link); dismiss() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SecondaryAction("Cancel", !submitting) { dismiss() }
                PrimaryAction(
                    if (state.busy) "Checking\u2026" else if (state.probed) "Start download" else "Connect",
                    !state.busy && !submitting && !state.html && state.link.isNotBlank(), Modifier.weight(1f),
                ) { if (state.probed) submit() else scope.launch { form.probe(agent) } }
            }
        }
    }
}
