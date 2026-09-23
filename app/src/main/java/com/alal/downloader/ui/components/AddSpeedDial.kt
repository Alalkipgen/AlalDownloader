package com.alal.downloader.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.alal.downloader.ui.theme.*

private data class DialAction(val label: String, val color: Color, val icon: ImageVector, val run: () -> Unit)

/** Bottom-right speed dial: one labelled, colour-coded pill per way of adding a download. */
@Composable
fun AddSpeedDial(
    expanded: Boolean,
    toggle: () -> Unit,
    add: () -> Unit,
    clipboard: () -> Unit,
    importFile: () -> Unit,
    batch: () -> Unit,
    torrent: () -> Unit,
) {
    val brand = LocalBrandColors.current
    val rotation by animateFloatAsState(if (expanded) 135f else 0f, tween(200), label = "add rotation")
    val actions = listOf(
        DialAction("Add torrent / magnet", TypeAmber, Icons.Outlined.Bolt, torrent),
        DialAction("Import .txt file", TypeGreen, Icons.Outlined.UploadFile, importFile),
        DialAction("From clipboard", TypeCoral, Icons.Outlined.ContentPaste, clipboard),
        DialAction("Add multiple URLs", TypeViolet, Icons.Outlined.PlaylistAdd, batch),
        DialAction("Add link", TypeIndigo, Icons.Outlined.Link, add),
    )
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(expanded, enter = fadeIn(tween(160)), exit = fadeOut(tween(120))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = toggle))
        }
        Column(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
            horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            actions.forEachIndexed { index, action ->
                AnimatedVisibility(expanded, enter = fadeIn(tween(180, index * 45)) + scaleIn(tween(180, index * 45)),
                    exit = fadeOut(tween(90)) + scaleOut(tween(90))) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(onClick = action.run, shape = PillShape, color = action.color) {
                            Text(action.label, Modifier.padding(horizontal = 16.dp, vertical = 9.dp), color = Color.White,
                                style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                        Surface(onClick = action.run, shape = CircleShape, color = action.color, modifier = Modifier.size(44.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(action.icon, action.label, tint = Color.White) }
                        }
                    }
                }
            }
            Surface(onClick = toggle, shape = if (expanded) CircleShape else FabShape, color = Color.Transparent,
                modifier = Modifier.size(62.dp).shadow(18.dp, if (expanded) CircleShape else FabShape,
                    ambientColor = brand.base, spotColor = brand.base)) {
                Box(Modifier.background(if (expanded) SolidColor(Color(0xFF17171F)) else brand.diagonal()),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Add, if (expanded) "Close add actions" else "Add download",
                        Modifier.size(28.dp).rotate(rotation), tint = Color.White)
                }
            }
        }
    }
}
