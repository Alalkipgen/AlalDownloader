package com.alal.downloader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.alal.downloader.R

private val Poppins = FontFamily(
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
)

private val AlalTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = Poppins, fontWeight = FontWeight.Bold),
        displayMedium = base.displayMedium.copy(fontFamily = Poppins, fontWeight = FontWeight.Bold),
        displaySmall = base.displaySmall.copy(fontFamily = Poppins, fontWeight = FontWeight.Bold),
        headlineLarge = base.headlineLarge.copy(fontFamily = Poppins, fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontFamily = Poppins, fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontFamily = Poppins, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontFamily = Poppins, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = Poppins, fontWeight = FontWeight.Medium),
        titleSmall = base.titleSmall.copy(fontFamily = Poppins, fontWeight = FontWeight.Medium),
    )
}

private val AlalDark = darkColorScheme(
    primary = Color(0xFFC7A4FF), onPrimary = Color(0xFF260052),
    primaryContainer = Color(0xFF54268A), onPrimaryContainer = Color(0xFFF0DBFF),
    secondary = Color(0xFFD0BDE5), onSecondary = Color(0xFF382A48),
    background = Color.Black, onBackground = Color(0xFFF2EDF7),
    surface = Color(0xFF100D14), onSurface = Color(0xFFF2EDF7),
    surfaceVariant = Color(0xFF302638), onSurfaceVariant = Color(0xFFD4C9DF),
)

private val AlalLight = lightColorScheme(
    primary = Color(0xFF7038AD), onPrimary = Color.White,
    primaryContainer = Color(0xFFEEDDFF), onPrimaryContainer = Color(0xFF260052),
    background = Color(0xFFFFF8FF), surface = Color(0xFFFFF8FF),
)

@Composable
internal fun AlalTheme(theme: String = "dark", content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (theme == "dark" || (theme == "system" && isSystemInDarkTheme())) AlalDark else AlalLight,
        typography = AlalTypography,
        content = content,
    )
}