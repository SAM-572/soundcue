package com.soundcue.babycare.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val MintPrimary = Color(0xFF4DB6AC)
val MintPrimaryDark = Color(0xFF26A69A)
val MintSurface = Color(0xFFE0F2F1)
val WarmBackground = Color(0xFFFAFAFA)
val SoftGray = Color(0xFFE0E0E0)
val TextPrimary = Color(0xFF212121)
val TextSecondary = Color(0xFF616161)
val DangerRed = Color(0xFFE53935)
val AccentYellow = Color(0xFFFFB300)

private val LightColors = lightColorScheme(
    primary = MintPrimary,
    onPrimary = Color.White,
    primaryContainer = MintSurface,
    onPrimaryContainer = MintPrimaryDark,
    secondary = MintPrimaryDark,
    background = WarmBackground,
    onBackground = TextPrimary,
    surface = Color.White,
    onSurface = TextPrimary,
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = TextSecondary,
    error = DangerRed
)

private val DarkColors = darkColorScheme(
    primary = MintPrimary,
    onPrimary = Color.Black,
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E)
)

@Composable
fun SoundCueTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
