package com.alal.downloader.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.alal.downloader.ui.theme.*

@Composable
fun AddSpeedDial(expanded: Boolean, toggle: () -> Unit, add: () -> Unit, clipboard: () -> Unit, importFile: () -> Unit, batch: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 45f else 0f, tween(200), label = "add rotation")
    Box(Modifier.fillMaxSize()) {
        if (expanded) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable(onClick = toggle))
        Column(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val actions = listOf(Triple("Import from text file", Color(0xFF4CAF50), importFile), Triple("From clipboard", Color(0xFFFF7043), clipboard), Triple("Add multiple URLs", Accent, batch), Triple("Add link", Color(0xFF4A6CF7), add))
            actions.forEachIndexed { index, (label, color, action) ->
                AnimatedVisibility(expanded, enter = fadeIn(tween(180, index * 60)) + scaleIn(tween(180, index * 60)), exit = fadeOut(tween(100)) + scaleOut(tween(100))) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(onClick = action, shape = PillShape, color = color) { Text(label, Modifier.padding(horizontal = 15.dp, vertical = 9.dp), color = Color.White) }
                        SmallFloatingActionButton(onClick = action, containerColor = color, contentColor = Color.White) {
                            Icon(when (index) { 0 -> Icons.Outlined.UploadFile; 1 -> Icons.Outlined.ContentPaste; else -> Icons.Outlined.Link }, label)
                        }
                    }
                }
            }
            Surface(onClick = toggle, shape = FabShape, color = Color.Transparent,
                modifier = Modifier.size(62.dp).shadow(16.dp, FabShape, ambientColor = Accent, spotColor = Accent)) {
                Box(Modifier.background(Brush.linearGradient(listOf(Accent, AccentDeep))), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Add, if (expanded) "Close add actions" else "Add download", Modifier.rotate(rotation), tint = Color.White)
                }
            }
        }
    }
}