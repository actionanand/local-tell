package com.actionanand.localtell.app.ui.theme

/** ARGB values for the Easy number and Short code text, chosen per light or dark theme. */
data class CodeValueArgb(val easyNumber: Long, val shortCode: Long)

/**
 * Plain ARGB definitions so they can be verified without Android. Each value keeps at least 7:1
 * contrast against [lightCardSurface]/[darkCardSurface], the containers Easy cards are drawn on.
 */
object CodeValuePalette {
    val light = CodeValueArgb(easyNumber = 0xFF153C79, shortCode = 0xFF582165)
    val dark = CodeValueArgb(easyNumber = 0xFFBED6FF, shortCode = 0xFFEDC6FF)

    /** surfaceContainerLow, the default ElevatedCard container colour in each theme. */
    const val lightCardSurface = 0xFFF0F7F2
    const val darkCardSurface = 0xFF101A14

    fun forTheme(darkTheme: Boolean): CodeValueArgb = if (darkTheme) dark else light
}
