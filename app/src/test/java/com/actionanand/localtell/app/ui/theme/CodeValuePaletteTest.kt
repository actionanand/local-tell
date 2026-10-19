package com.actionanand.localtell.app.ui.theme

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeValuePaletteTest {
    @Test fun lightAndDarkDefinitionsMatchApprovedColours() {
        assertEquals(CodeValueArgb(easyNumber = 0xFF153C79, shortCode = 0xFF582165), CodeValuePalette.light)
        assertEquals(CodeValueArgb(easyNumber = 0xFFBED6FF, shortCode = 0xFFEDC6FF), CodeValuePalette.dark)
    }

    @Test fun easyNumberAndShortCodeAreDistinctInBothThemes() {
        listOf(CodeValuePalette.light, CodeValuePalette.dark).forEach { assertNotEquals(it.easyNumber, it.shortCode) }
    }

    @Test fun paletteFollowsResolvedTheme() {
        assertEquals(CodeValuePalette.dark, CodeValuePalette.forTheme(darkTheme = true))
        assertEquals(CodeValuePalette.light, CodeValuePalette.forTheme(darkTheme = false))
    }

    @Test fun themeModeSelectionHonoursLightDarkAndSystem() {
        assertFalse(ThemeMode.LIGHT.isDark(systemInDarkTheme = true))
        assertTrue(ThemeMode.DARK.isDark(systemInDarkTheme = false))
        assertTrue(ThemeMode.SYSTEM.isDark(systemInDarkTheme = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemInDarkTheme = false))
        assertEquals(CodeValuePalette.dark, CodeValuePalette.forTheme(ThemeMode.SYSTEM.isDark(true)))
        assertEquals(CodeValuePalette.light, CodeValuePalette.forTheme(ThemeMode.LIGHT.isDark(true)))
    }

    @Test fun codeValuesMeetSevenToOneContrastOnCardSurfaces() {
        listOf(
            CodeValuePalette.light to CodeValuePalette.lightCardSurface,
            CodeValuePalette.dark to CodeValuePalette.darkCardSurface,
        ).forEach { (palette, surface) ->
            assertTrue(contrast(palette.easyNumber, surface) >= 7.0)
            assertTrue(contrast(palette.shortCode, surface) >= 7.0)
        }
    }

    private fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun contrast(a: Long, b: Long): Double {
        val (high, low) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (high + 0.05) / (low + 0.05)
    }
}
