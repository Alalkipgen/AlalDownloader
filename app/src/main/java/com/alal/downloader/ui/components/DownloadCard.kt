package com.alal.downloader.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alal.downloader.core.engine.*
import com.alal.downloader.feature.downloads.DownloadPresentation
import com.alal.downloader.ui.theme.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadCard(item: DownloadState, queuePosition: Int, selected: Boolean, click: () -> Unit, longClick: () -> Unit, action: () -> Unit) {
    val brand = LocalBrandColors.current
    val percent by remember(item) { derivedStateOf { DownloadPresentation.percent(item) ?: 0 } }
    val progress by animateFloatAsState(percent / 100f, tween(250), label = "download progress")
    val running = item.status == DownloadStatus.RUNNING
    val done = item.status == DownloadStatus.COMPLETED
    val failed = item.status == DownloadStatus.FAILED
    val size = DownloadPresentation.bytes(item.totalBytes)
    val meta = when (item.status) {
        DownloadStatus.RUNNING -> "${DownloadPresentation.bytes(item.downloadedBytes)} of $size \u00b7 ${DownloadPresentation.bytes(item.speedBytesPerSecond)}/s \u00b7 ${DownloadPresentation.eta(item)} left"
        DownloadStatus.QUEUED -> "Queued \u00b7 #$queuePosition \u00b7 $size"
        DownloadStatus.COMPLETED -> "Done \u00b7 $size"
        DownloadStatus.FAILED -> item.error?.message ?: "Failed \u00b7 tap to retry"
        DownloadStatus.NEEDS_BROWSER -> "Web page \u00b7 open in browser"
        else -> "${item.status.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }} \u00b7 ${DownloadPresentation.bytes(item.downloadedBytes)} of $size"
    }
    Card(Modifier.fillMaxWidth().combinedClickable(onClick = click, onLongClick = longClick), shape = CardShape,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.CenterVertically) {
            FileTypeTile(item.fileName)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(item.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Text(meta, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    done -> Unit
                    item.status == DownloadStatus.QUEUED -> DashedTrack()
                    else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(brand.horizontal()))
                        }
                        Text("$percent%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (done) Box(Modifier.size(34.dp).background(Ok.copy(alpha = 0.16f), CircleShape).clickable(onClick = action), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, "Open", Modifier.size(20.dp), tint = Ok)
            } else {
                val icon = when (item.status) {
                    DownloadStatus.RUNNING, DownloadStatus.WAITING_FOR_NETWORK, DownloadStatus.WAITING_FOR_WIFI -> Icons.Outlined.Pause
                    DownloadStatus.NEEDS_BROWSER -> Icons.Outlined.OpenInNew
                    DownloadStatus.FAILED -> Icons.Outlined.Refresh
                    else -> Icons.Outlined.PlayArrow
                }
                val label = when (item.status) {
                    DownloadStatus.NEEDS_BROWSER -> "Open in browser"
                    DownloadStatus.FAILED -> "Retry"
                    DownloadStatus.RUNNING -> "Pause"
                    else -> "Resume"
                }
                IconButton(onClick = action, modifier = Modifier.size(34.dp).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)) {
                    Icon(icon, label, Modifier.size(19.dp), tint = if (running) brand.bright else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Queued rows show a dashed track instead of a filled bar: nothing has transferred yet. */
@Composable
private fun DashedTrack() {
    val color = MaterialTheme.colorScheme.outline
    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
        drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 7.dp.toPx())))
    }
}
