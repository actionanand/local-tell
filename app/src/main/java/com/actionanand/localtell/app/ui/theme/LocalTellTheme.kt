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

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    companion object {
        fun fromPreference(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF126B3B), onPrimary = Color.White,
    primaryContainer = Color(0xFFD8F5E2), onPrimaryContainer = Color(0xFF06351D),
    secondary = Color(0xFF3F6B50), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8EEDF), onSecondaryContainer = Color(0xFF123B24),
    tertiary = Color(0xFF2E715A), onTertiary = Color.White,
    background = Color(0xFFF7FAF7), surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE4F0E7), surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F7F2), surfaceContainer = Color(0xFFEAF3ED),
    surfaceContainerHigh = Color(0xFFE4EEE7), surfaceContainerHighest = Color(0xFFDDE8E0),
    onBackground = Color(0xFF17221B), onSurface = Color(0xFF17221B), outline = Color(0xFF718377),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF78DFA0), onPrimary = Color(0xFF00391D),
    primaryContainer = Color(0xFF09552E), onPrimaryContainer = Color(0xFFA0F5BD),
    secondary = Color(0xFFA9D0B7), onSecondary = Color(0xFF143923),
    secondaryContainer = Color(0xFF274D34), onSecondaryContainer = Color(0xFFC5EFD1),
    tertiary = Color(0xFF8CD5B4), onTertiary = Color(0xFF073927),
    background = Color(0xFF09120D), surface = Color(0xFF101A14),
    surfaceVariant = Color(0xFF1B2920), surfaceContainerLowest = Color(0xFF06100A),
    surfaceContainerLow = Color(0xFF101A14), surfaceContainer = Color(0xFF152119),
    surfaceContainerHigh = Color(0xFF1B2920), surfaceContainerHighest = Color(0xFF243228),
    onBackground = Color(0xFFE2ECE5), onSurface = Color(0xFFE2ECE5), outline = Color(0xFF89988E),
)

@Composable
fun LocalTellTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
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
