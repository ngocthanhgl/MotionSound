package com.motionsound.ui.theme

import android.app.Activity
import android.graphics.Color
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private fun comicScheme(c: ComicColors): ColorScheme = lightColorScheme().copy(
    primary = c.yellow,
    onPrimary = ComicInk,
    primaryContainer = c.yellow.copy(alpha = 0.28f),
    onPrimaryContainer = c.ink,
    secondary = c.blue,
    onSecondary = ComposeColor.White,
    secondaryContainer = c.blue.copy(alpha = 0.22f),
    onSecondaryContainer = c.ink,
    tertiary = c.red,
    onTertiary = ComposeColor.White,
    tertiaryContainer = c.red.copy(alpha = 0.22f),
    onTertiaryContainer = c.ink,
    background = c.background,
    onBackground = c.ink,
    surface = c.surface,
    onSurface = c.ink,
    surfaceVariant = c.surfaceAlt,
    onSurfaceVariant = c.textMuted,
    surfaceContainerLowest = c.background,
    surfaceContainerLow = c.surfaceAlt,
    surfaceContainer = c.surface,
    surfaceContainerHigh = c.surface,
    surfaceContainerHighest = c.surfaceAlt,
    outline = c.ink,
    outlineVariant = c.ink.copy(alpha = 0.35f),
    error = c.red,
    onError = ComposeColor.White,
    errorContainer = c.red.copy(alpha = 0.2f),
    onErrorContainer = c.ink
)

@Composable
fun MotionSoundTheme(
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colors = remember { comicColors() }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
        }
    }

    CompositionLocalProvider(LocalComicColors provides colors) {
        MaterialTheme(
            colorScheme = comicScheme(colors),
            typography = Typography,
            content = content
        )
    }
}