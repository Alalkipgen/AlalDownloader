package com.alal.downloader.ui

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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alal.downloader.feature.downloads.*
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
@Composable
internal fun AddDownloadDialog(downloads: DownloadsViewModel, initialLink: String, openBrowser: (String) -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    val form = remember { AddDownloadViewModel(downloads.prober).apply { link(initialLink) } }
    val state by form.state.collectAsStateWithLifecycle()
    val defaultWifi by downloads.wifiOnly.collectAsStateWithLifecycle()
    val folder by downloads.treeUri.collectAsStateWithLifecycle()
    var wifi by remember { mutableStateOf(defaultWifi) }
    var retry by remember { mutableStateOf(true) }
    var submitting by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var storage by remember { mutableStateOf("—") }
    var lowSpace by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
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
                "${Formatter.formatFileSize(context, free)}/${Formatter.formatFileSize(context, total)}, ${if (total > 0) free * 100 / total else 0}% free" to (free < 500L * 1024 * 1024)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { (failure.message ?: "Storage information unavailable") to false }
        }
        storage = result.first; lowSpace = result.second
    }
    Dialog(onDismissRequest = { if (!submitting) dismiss() }) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Download file", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(state.link, { form.link(it); localError = null }, label = { Text("Link") }, modifier = Modifier.fillMaxWidth(), trailingIcon = {
                    Row {
                        IconButton(onClick = { form.link(clipboardDownloadLink(context)) }) { Icon(Icons.Outlined.ContentPaste, "Paste") }
                        IconButton(onClick = {
                            try { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, state.link), "Share link")) }
                            catch (failure: android.content.ActivityNotFoundException) { localError = "No sharing app available" }
                        }) { Icon(Icons.Outlined.Share, "Share") }
                    }
                })
                OutlinedTextField(state.referrer, form::referrer, label = { Text("Referrer page link") }, placeholder = { Text("leave empty if not sure") }, modifier = Modifier.fillMaxWidth())
                Text("Save as")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(state.name, form::name, label = { Text("File name") }, modifier = Modifier.weight(2f))
                    OutlinedTextField(state.extension, form::extension, label = { Text("Extension") }, modifier = Modifier.weight(1f))
                }
                Text("Size: " + (state.size?.let { if (it < 0) "unknown" else Formatter.formatFileSize(context, it) } ?: "—"))
                state.resume?.let { Text("Resume: ${if (it) "Yes" else "No"}") }
                state.finalUrl?.let { Text("Final URL: $it", style = MaterialTheme.typography.bodySmall) }
                Text("Storage: $storage", color = if (lowSpace) com.alal.downloader.ui.theme.Warn else MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(folder?.let(Uri::decode) ?: "Downloads/Alal", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { picker.launch(folder?.let(Uri::parse)) }) { Icon(Icons.Outlined.FolderOpen, "Choose folder") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(wifi, { wifi = it }); Text("Wi-Fi only") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(retry, { retry = it }); Text("Retry on failure") }
                (localError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.html) TextButton(onClick = { openBrowser(state.finalUrl ?: state.link); dismiss() }) { Text("Open in browser") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !submitting && !state.busy && !state.html && state.link.isNotBlank(), onClick = {
                        if (state.link.startsWith("magnet:", true)) { localError = "Torrent not supported yet" }
                        else {
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
                    }) { Text("ADD") }
                    TextButton(onClick = dismiss, enabled = !submitting) { Text("CANCEL") }
                    TextButton(enabled = !state.busy && !submitting && !state.html && state.link.isNotBlank(), onClick = { scope.launch { form.probe(agent) } }) { Text(if (state.busy) "…" else "CONNECT") }
                }
            }
        }
    }
}