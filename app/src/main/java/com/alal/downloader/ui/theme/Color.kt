package com.alal.downloader.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** One selectable accent: the base tone plus the lighter and deeper tones used by gradients. */
@Immutable
data class AccentOption(val key: String, val label: String, val hex: String, val base: Color, val bright: Color, val deep: Color)

val AccentOptions = listOf(
    AccentOption("indigo", "Indigo", "#4F46E5", Color(0xFF4F46E5), Color(0xFF8B7BFF), Color(0xFF3730A3)),
    AccentOption("violet", "Violet", "#7C3AED", Color(0xFF7C3AED), Color(0xFFB794FF), Color(0xFF5B21B6)),
    AccentOption("coral", "Coral", "#FF6B4A", Color(0xFFFF6B4A), Color(0xFFFFA98F), Color(0xFFC2410C)),
    AccentOption("emerald", "Emerald", "#10B981", Color(0xFF10B981), Color(0xFF5EEAD4), Color(0xFF047857)),
)

fun accentFor(key: String): AccentOption = AccentOptions.firstOrNull { it.key == key } ?: AccentOptions[0]

// File-type palette shared by the drawer, the cards and the add sheet.
val TypeIndigo = Color(0xFF4F46E5)
val TypeViolet = Color(0xFF7C3AED)
val TypeCoral = Color(0xFFFF6B4A)
val TypeAmber = Color(0xFFF59E0B)
val TypeTeal = Color(0xFF14B8A6)
val TypePink = Color(0xFFF43F5E)
val TypePurple = Color(0xFFA855F7)
val TypeBlue = Color(0xFF3B82F6)
val TypeGreen = Color(0xFF10B981)
val TypeSlate = Color(0xFF6B7280)
