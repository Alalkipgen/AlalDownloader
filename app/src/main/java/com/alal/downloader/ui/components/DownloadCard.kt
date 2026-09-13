package com.alal.downloader.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alal.downloader.core.engine.*
import com.alal.downloader.feature.downloads.DownloadPresentation
import com.alal.downloader.ui.theme.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadCard(item: DownloadState, queuePosition: Int, selected: Boolean, click: () -> Unit, longClick: () -> Unit, action: () -> Unit) {
    val percent by remember(item) { derivedStateOf { DownloadPresentation.percent(item) ?: 0 } }
    val progress by animateFloatAsState(percent / 100f, tween(250), label = "download progress")
    val badge = when (item.status) {
        DownloadStatus.RUNNING -> Triple("DOWNLOADING", RunBg, Accent2)
        DownloadStatus.QUEUED -> Triple("QUEUED · #$queuePosition", QueuedBg, QueuedFg)
        DownloadStatus.COMPLETED -> Triple("COMPLETED", Color(0xFF0F2E23), Ok)
        DownloadStatus.FAILED -> Triple("FAILED", Color(0xFF33161F), Err)
        else -> Triple(item.status.name.replace('_', ' '), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Card(Modifier.fillMaxWidth().combinedClickable(onClick = click, onLongClick = longClick), shape = CardShape,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.CenterVertically) {
            FileTypeTile(item.fileName)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    val suffix = item.fileName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
                    Text(item.fileName.removeSuffix(suffix), Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (suffix.isNotEmpty()) Text(suffix, maxLines = 1)
                }
                Surface(color = badge.second, shape = RoundedCornerShape(7.dp)) {
                    Text(badge.first, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = badge.third, style = MaterialTheme.typography.labelSmall)
                }
                if (item.status == DownloadStatus.RUNNING) Text("${DownloadPresentation.bytes(item.speedBytesPerSecond)}/s · ETA ${DownloadPresentation.eta(item)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Brush.horizontalGradient(listOf(Accent, Accent2))))
                }
                Text("$percent% · ${DownloadPresentation.bytes(item.downloadedBytes)} / ${DownloadPresentation.bytes(item.totalBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${item.segments.size.takeIf { it > 0 } ?: item.request.segmentCount ?: 1} parts · Resume: ${if (item.acceptsRanges) "Yes" else "No"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val icon = when (item.status) {
                DownloadStatus.RUNNING, DownloadStatus.WAITING_FOR_NETWORK, DownloadStatus.WAITING_FOR_WIFI -> Icons.Outlined.Pause
                DownloadStatus.COMPLETED -> Icons.Outlined.OpenInNew
                DownloadStatus.FAILED -> Icons.Outlined.Refresh
                else -> Icons.Outlined.PlayArrow
            }
            IconButton(onClick = action, modifier = Modifier.size(34.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp))) {
                Icon(icon, when (item.status) { DownloadStatus.COMPLETED -> "Open"; DownloadStatus.FAILED -> "Retry"; DownloadStatus.RUNNING -> "Pause"; else -> "Resume" }, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}