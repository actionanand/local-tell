package com.actionanand.localtell.app.ui.theme

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    /** Resolves the user's Light/Dark/System choice against the device's current dark setting. */
    fun isDark(systemInDarkTheme: Boolean): Boolean = when (this) {
        SYSTEM -> systemInDarkTheme
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromPreference(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}
