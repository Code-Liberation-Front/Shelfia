package app.shelfie.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ShelfieOrange = Color(0xFFFC7E0F)
val ShelfieOrangeDim = Color(0xFFB85C0A)
val ShelfieBackground = Color(0xFF14181C)
val ShelfieSurface = Color(0xFF1C2127)
val ShelfieSurfaceHigh = Color(0xFF262C34)

private val DarkColors = darkColorScheme(
    primary = ShelfieOrange,
    onPrimary = Color.White,
    primaryContainer = ShelfieOrangeDim,
    onPrimaryContainer = Color.White,
    secondary = ShelfieOrange,
    onSecondary = Color.White,
    background = ShelfieBackground,
    onBackground = Color(0xFFE8EAED),
    surface = ShelfieSurface,
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = ShelfieSurfaceHigh,
    onSurfaceVariant = Color(0xFFAEB4BC),
)

@Composable
fun ShelfieTheme(content: @Composable () -> Unit) {
    // Overcast-style: always dark.
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
