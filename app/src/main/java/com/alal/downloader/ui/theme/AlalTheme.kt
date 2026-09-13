package com.alal.downloader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
val Accent = Color(0xFF7C5CFF)
val Accent2 = Color(0xFFB39BFF)
val AccentDeep = Color(0xFF5B3BE0)
val Ok = Color(0xFF3DDC97)
val Warn = Color(0xFFFFB84D)
val Err = Color(0xFFFF5C7A)
val QueuedBg = Color(0xFF2A2A38)
val QueuedFg = Color(0xFF9A9AB5)
val RunBg = Color(0xFF1B1638)

val CardShape = RoundedCornerShape(18.dp)
val SettingsShape = RoundedCornerShape(20.dp)
val IconShape = RoundedCornerShape(12.dp)
val PillShape = RoundedCornerShape(100.dp)
val FabShape = RoundedCornerShape(22.dp)
val ScreenPadding = 16.dp
val CardGap = 10.dp
val SectionGap = 14.dp

private val Dark = darkColorScheme(
    primary = Accent, onPrimary = Color.White, primaryContainer = RunBg, onPrimaryContainer = Accent2,
    background = Bg, onBackground = OnBg, surface = Surface, surfaceVariant = Surface2,
    onSurface = OnBg, onSurfaceVariant = Dim, outline = Outline, outlineVariant = Outline,
    secondary = Accent2, onSecondary = RunBg, secondaryContainer = RunBg, onSecondaryContainer = Accent2,
    surfaceContainer = Surface, surfaceContainerHigh = Surface2, surfaceContainerHighest = Surface2,
    error = Err, errorContainer = Color(0xFF33161F), onErrorContainer = Err,
)
private val Light = lightColorScheme(
    primary = Accent, onPrimary = Color.White, primaryContainer = Color(0xFFE8E0FF), onPrimaryContainer = AccentDeep,
    background = Color(0xFFF7F6FB), onBackground = Color(0xFF111118), surface = Color.White,
    surfaceVariant = Color(0xFFEFEDF6), onSurface = Color(0xFF111118), onSurfaceVariant = Color(0xFF6E6E85),
    outline = Outline, outlineVariant = Outline, secondary = AccentDeep, onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E0FF), onSecondaryContainer = AccentDeep,
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFEFEDF6), surfaceContainerHighest = Color(0xFFEFEDF6),
    error = Color(0xFFB51E42), errorContainer = Color(0xFFFFE0E7), onErrorContainer = Color(0xFF80132E),
)
private val Type = Typography(
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

/** Brand palette with explicit system, dark and light modes. */
@Composable
fun AlalTheme(theme: String = "system", dynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
    val context = LocalContext.current
    val colors = if (dynamic && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) Dark else Light
    MaterialTheme(colorScheme = colors, typography = Type,
        shapes = Shapes(small = IconShape, medium = CardShape, large = SettingsShape, extraLarge = RoundedCornerShape(26.dp)),
        content = content)
}