package com.electrodig.voidmusic.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color.Black,
    secondary = Cyan,
    onSecondary = Color.Black,
    tertiary = Lime,
    background = BackgroundDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceMuted
)

private val LightColors = lightColorScheme(
    primary = Amber,
    onPrimary = Color.Black,
    secondary = Cyan,
    onSecondary = Color.Black,
    tertiary = Lime,
    background = Color(0xFFF6F6F8),
    onBackground = Color(0xFF15151E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF15151E)
)

/**
 * App theme. A viewfinder app spends most of its time on a dark surface, so the
 * dark scheme is used whenever the system is in dark mode; otherwise light.
 */
@Composable
fun VoidMusicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Make camera preview extend behind system bars for full-bleed viewfinder.
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
