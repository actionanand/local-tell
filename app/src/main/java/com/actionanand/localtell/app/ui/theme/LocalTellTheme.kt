package com.actionanand.localtell.app.ui.theme

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

private val LightColors = lightColorScheme(
    primary = Color(0xFF126B3B), onPrimary = Color.White,
    primaryContainer = Color(0xFFD8F5E2), onPrimaryContainer = Color(0xFF06351D),
    secondary = Color(0xFF456B54), background = Color(0xFFF6FAF7), surface = Color.White,
    surfaceVariant = Color(0xFFE8F1EB), onBackground = Color(0xFF17221B),
    onSurface = Color(0xFF17221B), outline = Color(0xFF758579),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF78DFA0), onPrimary = Color(0xFF00391D),
    primaryContainer = Color(0xFF09552E), onPrimaryContainer = Color(0xFFA0F5BD),
    secondary = Color(0xFFA9D0B7), background = Color(0xFF09120D), surface = Color(0xFF101A14),
    surfaceVariant = Color(0xFF1B2920), onBackground = Color(0xFFE2ECE5),
    onSurface = Color(0xFFE2ECE5), outline = Color(0xFF89988E),
)

@Composable
fun LocalTellTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        window.statusBarColor = colors.background.toArgb()
        window.navigationBarColor = colors.surface.toArgb()
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
