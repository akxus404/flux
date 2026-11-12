package app.flux.downloader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val FluxOrange = Color(0xFFFF6A00)

private val FluxColors = darkColorScheme(
    primary = FluxOrange,
    onPrimary = Color.Black,
    secondary = FluxOrange,
    background = Color(0xFF0A0A0A),
    onBackground = Color.White,
    surface = Color(0xFF141414),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF222222),
    onSurfaceVariant = Color(0xFFB5B5B5),
    outline = Color(0xFF3A3A3A),
    error = Color(0xFFFF5252),
    secondaryContainer = Color(0xFF3A1F08),
    onSecondaryContainer = FluxOrange,
)

@Composable
fun FluxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FluxColors, content = content)
}
