package com.alal.downloader.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alal.downloader.ui.theme.*

/** Filled brand-gradient button used for the primary action of every sheet. */
@Composable
fun PrimaryAction(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val brand = LocalBrandColors.current
    Box(
        modifier.clip(PillShape)
            .background(if (enabled) brand.horizontal() else SolidColor(MaterialTheme.colorScheme.surfaceVariant))
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick)
            .heightIn(min = 52.dp).padding(horizontal = 22.dp, vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1,
            color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Outlined companion action; reads as secondary next to [PrimaryAction]. */
@Composable
fun SecondaryAction(label: String, enabled: Boolean = true, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        modifier.clip(PillShape).border(1.dp, MaterialTheme.colorScheme.outline, PillShape)
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick)
            .heightIn(min = 46.dp).padding(horizontal = 18.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Small selectable pill used for the option row of the add sheet. */
@Composable
fun OptionChip(label: String, selected: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    val brand = LocalBrandColors.current
    Row(
        Modifier.clip(PillShape)
            .background(if (selected) brand.horizontal() else SolidColor(MaterialTheme.colorScheme.surfaceVariant))
            .clickable(onClickLabel = label, role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        val content = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = content)
        Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

/** Transfer summary strip: combined speed, an optional sparkline and the active count. */
@Composable
fun SpeedStrip(speed: String, active: Int, history: List<Float>, showGraph: Boolean, modifier: Modifier = Modifier) {
    val brand = LocalBrandColors.current
    Row(
        modifier.clip(PillShape).background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.Bolt, null, Modifier.size(16.dp), tint = brand.bright)
        Text(speed, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        if (showGraph) Sparkline(history, brand.bright, Modifier.weight(1f).height(22.dp))
        else Spacer(Modifier.weight(1f))
        Text(if (active == 1) "1 active" else "$active active", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Rolling transfer-rate line; a flat baseline is drawn while nothing is running. */
@Composable
fun Sparkline(points: List<Float>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val count = points.size
        val peak = points.maxOrNull() ?: 0f
        if (count < 2 || peak <= 0f) {
            drawLine(color.copy(alpha = 0.35f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
            return@Canvas
        }
        val step = size.width / (count - 1)
        val line = Path()
        points.forEachIndexed { index, value ->
            val x = index * step
            val y = size.height - (value / peak).coerceIn(0f, 1f) * (size.height - 2.dp.toPx()) - 1.dp.toPx()
            if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        val area = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent)))
        drawPath(line, color, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Free-space ring for the drawer header. */
@Composable
fun StorageRing(fraction: Float, label: String, diameter: Dp = 46.dp) {
    val brand = LocalBrandColors.current
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2
            val box = Size(size.width - stroke, size.height - stroke)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), box, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(Brush.sweepGradient(listOf(brand.base, brand.bright, brand.base)), -90f,
                360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), box, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** Neutral round avatar for drawer and sheet headers. */
@Composable
fun BrandBadge(icon: ImageVector, size: Dp = 44.dp) {
    val brand = LocalBrandColors.current
    Box(Modifier.size(size).clip(CircleShape).background(brand.diagonal()), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.5f), tint = Color.White)
    }
}
