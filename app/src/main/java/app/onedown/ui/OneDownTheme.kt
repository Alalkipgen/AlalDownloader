package app.onedown.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

@Composable
internal fun OneDownTheme(theme: String = "system", content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (theme == "dark" || (theme == "system" && isSystemInDarkTheme())) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}