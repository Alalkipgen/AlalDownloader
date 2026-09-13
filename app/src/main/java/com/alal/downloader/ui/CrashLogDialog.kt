package com.alal.downloader.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.alal.downloader.CrashLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local-only crash report viewer; sharing requires an explicit user action. */
@Composable
fun CrashLogDialog(title: String, report: String?, dismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            Text("Reports may contain sensitive page or device details. Review before sharing.")
            Text(report ?: "No crash log saved.")
        }
    }, confirmButton = {
        TextButton(onClick = dismiss) { Text("Dismiss") }
    }, dismissButton = {
        TextButton(enabled = report != null, onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("Last crash log", report))
        }) { Text("Copy") }
        TextButton(enabled = report != null, onClick = {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Alal Downloader crash log")
                putExtra(Intent.EXTRA_TEXT, report)
            }, "Share crash log"))
        }) { Text("Share") }
    })
}

@Composable
fun PreviousCrashNotice() {
    val context = LocalContext.current
    var report by remember { mutableStateOf<String?>(null) }
    var dismissed by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(context) { report = withContext(Dispatchers.IO) { CrashLog.read(context) } }
    if (!dismissed && report != null) CrashLogDialog("The app crashed last time", report) { dismissed = true }
}
