package tech.ssemaj.bridge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Deliberately static palette (no dynamic color): the showcase keeps a single mint
 * accent so consoles, tags, and controls read as one system on any device.
 */
private val DarkColorScheme = darkColorScheme(
    primary = Mint300,
    onPrimary = MintInk,
    primaryContainer = MintContainerDark,
    onPrimaryContainer = Mint200,
    secondary = Gray500,
    onSecondary = Ink900,
    secondaryContainer = Ink750,
    onSecondaryContainer = Gray300,
    tertiary = Mint200,
    background = Ink900,
    onBackground = Gray300,
    surface = Ink850,
    onSurface = Gray300,
    surfaceVariant = Ink750,
    onSurfaceVariant = Gray500,
    surfaceContainer = Ink800,
    surfaceContainerHigh = Ink750,
    outline = Gray700,
    outlineVariant = Gray700,
)

private val LightColorScheme = lightColorScheme(
    primary = Mint700,
    onPrimary = Paper,
    primaryContainer = MintContainerLight,
    onPrimaryContainer = MintInk,
    secondary = InkSubtle,
    onSecondary = Paper,
    secondaryContainer = PaperConsole,
    onSecondaryContainer = InkText,
    tertiary = Mint700,
    background = Paper,
    onBackground = InkText,
    surface = PaperCard,
    onSurface = InkText,
    surfaceVariant = PaperConsole,
    onSurfaceVariant = InkSubtle,
    surfaceContainer = PaperCard,
    surfaceContainerHigh = PaperConsole,
    outline = PaperOutline,
    outlineVariant = PaperOutline,
)

@Composable
fun BridgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content,
    )
}
