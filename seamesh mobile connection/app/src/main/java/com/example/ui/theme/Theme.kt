package com.example.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val CoastalLagoonColorScheme = darkColorScheme(
    primary = SeafoamCrest,
    onPrimary = AbyssalKelp,
    primaryContainer = SeaGlassElevated,
    onPrimaryContainer = SunlitSand,
    secondary = CoralBuoy,
    onSecondary = Color.White,
    tertiary = LighthouseGold,
    background = AbyssalKelp,
    onBackground = SunlitSand,
    surface = SeaGlassSurface,
    onSurface = SunlitSand,
    surfaceVariant = SeaGlassElevated,
    onSurfaceVariant = DriftwoodCream,
    outline = SeaGlassBorder,
    error = CoralBuoy,
    onError = Color.White
)

private val SunlitSandColorScheme = lightColorScheme(
    primary = LagoonTealDim,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5F5EE),
    onPrimaryContainer = AbyssalKelp,
    secondary = CoralBuoy,
    onSecondary = Color.White,
    background = Color(0xFFF6F1E6),
    onBackground = AbyssalKelp,
    surface = Color(0xFFFFFCF5),
    onSurface = AbyssalKelp,
    surfaceVariant = Color(0xFFEBE3D3),
    onSurfaceVariant = TidalLagoon,
    outline = Color(0xFFB5C9C3),
    error = CoralBuoy,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) CoastalLagoonColorScheme else SunlitSandColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = Color.Transparent.toArgb()
                window.navigationBarColor = AbyssalKelp.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
