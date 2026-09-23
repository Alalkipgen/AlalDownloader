package com.alal.downloader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alal.downloader.ui.LocalAppearance
import com.alal.downloader.ui.theme.*

fun fileCategory(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "torrent" -> "Torrents"
    "zip", "rar", "7z", "tar", "gz", "bz2", "xz" -> "Compressed"
    "pdf", "doc", "docx", "txt", "rtf", "xls", "xlsx", "csv", "ppt", "pptx" -> "Documents"
    "mp3", "m4a", "flac", "wav", "ogg", "aac" -> "Music"
    "mp4", "mkv", "avi", "mov", "webm", "m4v", "flv" -> "Videos"
    "srt", "vtt", "ass", "ssa", "sub" -> "Subtitles"
    "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg" -> "Photos"
    "apk", "apks", "xapk", "exe", "msi", "dmg", "pkg" -> "Programs"
    else -> "Others"
}

/** Brand colour of a drawer category, also used by the card tiles. */
fun categoryColor(category: String): Color = when (category) {
    "Everything" -> TypeViolet
    "Torrents" -> TypeIndigo
    "Compressed" -> TypeAmber
    "Documents" -> TypePink
    "Music" -> TypeGreen
    "Videos" -> TypeCoral
    "Subtitles" -> TypeTeal
    "Photos" -> TypePurple
    "Programs" -> TypeBlue
    else -> TypeSlate
}

fun categoryIcon(category: String): ImageVector = when (category) {
    "Everything" -> Icons.Filled.Layers
    "Torrents" -> Icons.Filled.SwapVert
    "Compressed" -> Icons.Filled.FolderZip
    "Documents" -> Icons.Filled.Description
    "Music" -> Icons.Filled.MusicNote
    "Videos" -> Icons.Filled.Movie
    "Subtitles" -> Icons.Filled.ClosedCaption
    "Photos" -> Icons.Filled.Image
    "Programs" -> Icons.Filled.Android
    else -> Icons.Filled.InsertDriveFile
}

/** Rounded category tile: a saturated gradient square with a white glyph. */
@Composable
fun CategoryTile(category: String, size: Dp = 46.dp, corner: Dp = 15.dp) {
    val colorful = LocalAppearance.current.colorfulIcons
    val tint = categoryColor(category)
    val background = if (colorful) Brush.linearGradient(listOf(tint.copy(alpha = 0.95f), tint.copy(alpha = 0.72f)))
        else Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant))
    Box(Modifier.size(size).background(background, RoundedCornerShape(corner)), contentAlignment = Alignment.Center) {
        Icon(categoryIcon(category), category, Modifier.size(size * 0.52f),
            tint = if (colorful) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun FileTypeTile(fileName: String, size: Dp = 46.dp) = CategoryTile(fileCategory(fileName), size)

/** Compact circular variant used by lists that already carry their own card background. */
@Composable
fun FileTypeIcon(fileName: String, modifier: Modifier = Modifier) {
    val colorful = LocalAppearance.current.colorfulIcons
    val category = fileCategory(fileName)
    val tint = if (colorful) categoryColor(category) else MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier.size(40.dp).background(tint.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(categoryIcon(category), category, Modifier.size(22.dp), tint = tint)
    }
}
