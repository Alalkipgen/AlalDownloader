package com.alal.downloader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

val Bg = Color(0xFF0B0B0F)
val Surface = Color(0xFF14141B)
val Surface2 = Color(0xFF1C1C26)
val Outline = Color(0x40262633)
val OnBg = Color(0xFFF4F4F7)
val Dim = Color(0xFF8C8CA1)
val Accent = Color(0xFF4F46E5)
val Accent2 = Color(0xFF8B7BFF)
val AccentDeep = Color(0xFF3730A3)
val Ok = Color(0xFF10B981)
val Warn = Color(0xFFF59E0B)
val Err = Color(0xFFFF5C7A)
val QueuedBg = Color(0xFF2A2A38)
val QueuedFg = Color(0xFF9A9AB5)
val RunBg = Color(0xFF1B1638)

val CardShape = RoundedCornerShape(20.dp)
val SettingsShape = RoundedCornerShape(22.dp)
val IconShape = RoundedCornerShape(13.dp)
val FieldShape = RoundedCornerShape(16.dp)
val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
val PillShape = RoundedCornerShape(100.dp)
val FabShape = RoundedCornerShape(20.dp)
val ScreenPadding = 16.dp
val CardGap = 10.dp
val SectionGap = 14.dp

/** The three accent tones the current theme paints gradients and highlights with. */
@Immutable
data class BrandColors(val base: Color, val bright: Color, val deep: Color)

val LocalBrandColors = staticCompositionLocalOf { BrandColors(Accent, Accent2, AccentDeep) }

fun BrandColors.horizontal(): Brush = Brush.horizontalGradient(listOf(base, bright))

fun BrandColors.diagonal(): Brush = Brush.linearGradient(listOf(base, bright))

fun BrandColors.soft(surface: Color, amount: Float = 0.16f): Color = lerp(surface, base, amount)

private fun darkScheme(accent: AccentOption, amoled: Boolean): ColorScheme {
    val background = if (amoled) Color.Black else Bg
    val surface = if (amoled) Color(0xFF09090C) else Surface
    val variant = if (amoled) Color(0xFF131318) else Surface2
    return darkColorScheme(
        primary = accent.base, onPrimary = Color.White,
        primaryContainer = lerp(background, accent.base, 0.22f), onPrimaryContainer = accent.bright,
        secondary = accent.bright, onSecondary = Color(0xFF14101F),
        secondaryContainer = lerp(surface, accent.base, 0.18f), onSecondaryContainer = accent.bright,
        tertiary = accent.bright, onTertiary = Color(0xFF14101F),
        background = background, onBackground = OnBg, surface = surface, surfaceVariant = variant,
        onSurface = OnBg, onSurfaceVariant = Dim, outline = Outline, outlineVariant = Outline,
        surfaceContainer = surface, surfaceContainerHigh = variant, surfaceContainerHighest = variant,
        error = Err, onError = Color.White, errorContainer = Color(0xFF33161F), onErrorContainer = Err,
    )
}

private fun lightScheme(accent: AccentOption): ColorScheme = lightColorScheme(
    primary = accent.base, onPrimary = Color.White,
    primaryContainer = lerp(Color.White, accent.base, 0.14f), onPrimaryContainer = accent.deep,
    secondary = accent.deep, onSecondary = Color.White,
    secondaryContainer = lerp(Color.White, accent.base, 0.11f), onSecondaryContainer = accent.deep,
    tertiary = accent.bright, onTertiary = Color.White,
    background = Color(0xFFF5F4FA), onBackground = Color(0xFF111118), surface = Color.White,
    surfaceVariant = Color(0xFFEFEDF6), onSurface = Color(0xFF111118), onSurfaceVariant = Color(0xFF6E6E85),
    outline = Color(0x18111118), outlineVariant = Color(0x12111118),
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFF1EFF8), surfaceContainerHighest = Color(0xFFEFEDF6),
    error = Color(0xFFB51E42), onError = Color.White, errorContainer = Color(0xFFFFE0E7), onErrorContainer = Color(0xFF80132E),
)

private val Type = Typography(
    headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold),
    headlineSmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.ExtraBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.12.em),
    labelSmall = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Bold),
)

/** Brand palette with explicit system, dark, light, AMOLED and accent selection. */
@Composable
fun AlalTheme(
    theme: String = "system",
    dynamic: Boolean = false,
    accent: String = "indigo",
    amoled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
    val context = LocalContext.current
    val option = accentFor(accent)
    val useDynamic = dynamic && Build.VERSION.SDK_INT >= 31
    val colors = when {
        useDynamic -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkScheme(option, amoled)
        else -> lightScheme(option)
    }
    val brand = if (useDynamic) BrandColors(colors.primary, colors.tertiary, colors.onPrimaryContainer)
        else BrandColors(option.base, option.bright, option.deep)
    CompositionLocalProvider(LocalBrandColors provides brand) {
        MaterialTheme(colorScheme = colors, typography = Type,
            shapes = Shapes(small = IconShape, medium = CardShape, large = SettingsShape, extraLarge = RoundedCornerShape(28.dp)),
            content = content)
    }
}
