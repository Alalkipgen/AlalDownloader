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
    
    data class IconStyle(val icon: ImageVector, val tint: Color)
    
    val style = when (extension) {
        "mp4", "mkv", "avi", "mov", "webm", "m4v", "flv" -> IconStyle(Icons.Default.PlayArrow, Color(0xFFE91E63))
        "mp3", "m4a", "flac", "wav", "ogg", "aac" -> IconStyle(Icons.Default.MusicNote, Color(0xFF9C27B0))
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg" -> IconStyle(Icons.Default.Image, Color(0xFF2196F3))
        "pdf" -> IconStyle(Icons.Default.PictureAsPdf, Color(0xFFF44336))
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz" -> IconStyle(Icons.Default.Folder, Color(0xFFFF9800))
        "apk", "apks", "xapk" -> IconStyle(Icons.Default.Android, Color(0xFF4CAF50))
        "doc", "docx", "txt", "rtf" -> IconStyle(Icons.Default.Description, Color(0xFF2196F3))
        "xls", "xlsx", "csv" -> IconStyle(Icons.Default.TableChart, Color(0xFF4CAF50))
        "ppt", "pptx" -> IconStyle(Icons.Default.Slideshow, Color(0xFFFF5722))
        "exe", "msi", "dmg", "pkg" -> IconStyle(Icons.Default.Build, Color(0xFF607D8B))
        "iso", "img" -> IconStyle(Icons.Default.Album, Color(0xFF9E9E9E))
        else -> IconStyle(Icons.Default.InsertDriveFile, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    
    Box(
        modifier = modifier
            .size(40.dp)
            .background(style.tint.copy(alpha = 0.15f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = style.icon,
            contentDescription = null,
            tint = style.tint,
            modifier = Modifier.size(24.dp)
        )
    }
}
