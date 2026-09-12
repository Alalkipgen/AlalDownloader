package com.alal.downloader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun FileTypeIcon(fileName: String, modifier: Modifier = Modifier) {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    val (icon, tint) = when (extension) {
        "mp4", "mkv", "avi", "mov", "webm", "m4v", "flv" -> Icons.Default.Videocam to Color(0xFFE91E63)
        "mp3", "m4a", "flac", "wav", "ogg", "aac" -> Icons.Default.AudioFile to Color(0xFF9C27B0)
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg" -> Icons.Default.Image to Color(0xFF2196F3)
        "pdf" -> Icons.Default.PictureAsPdf to Color(0xFFF44336)
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz" -> Icons.Default.FolderZip to Color(0xFFFF9800)
        "apk", "apks", "xapk" -> Icons.Default.Android to Color(0xFF4CAF50)
        "doc", "docx", "txt", "rtf" -> Icons.Default.Description to Color(0xFF2196F3)
        "xls", "xlsx", "csv" -> Icons.Default.TableChart to Color(0xFF4CAF50)
        "ppt", "pptx" -> Icons.Default.Slideshow to Color(0xFFFF5722)
        "exe", "msi", "dmg", "pkg" -> Icons.Default.InstallDesktop to Color(0xFF607D8B)
        "iso", "img" -> Icons.Default.Album to Color(0xFF9E9E9E)
        else -> Icons.Default.InsertDriveFile to MaterialTheme.colorScheme.onSurfaceVariant
    }
    
    Box(
        modifier = modifier
            .size(40.dp)
            .background(tint.copy(alpha = 0.15f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
    }
}
