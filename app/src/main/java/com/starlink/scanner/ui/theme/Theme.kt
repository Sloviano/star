package com.starlink.scanner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic status colors that live outside the Material [androidx.compose.material3.ColorScheme].
 * Exposed via [LocalAppColors] so any composable can read "success/pending/error" consistently
 * in both light and dark mode.
 */
@Immutable
data class AppColors(
    val success: Color,
    val pending: Color,
    val error: Color,
)

val LocalAppColors = staticCompositionLocalOf {
    AppColors(success = StatusGreen, pending = StatusAmber, error = StatusRed)
}

private val LightColors = lightColorScheme(
    primary = StarlinkBlue,
    onPrimary = Color.White,
    surface = SurfaceLight,
    error = StatusRed,
)

private val DarkColors = darkColorScheme(
    primary = StarlinkBlueDark,
    onPrimary = Color.Black,
    surface = SurfaceDark,
    error = StatusRed,
)

/**
 * App theme. Deliberately does NOT use Material You dynamic color: this is a field tool that
 * must present a single, predictable accent regardless of device wallpaper.
 */
@Composable
fun StarlinkScannerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content,
    )
}
