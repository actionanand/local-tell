package com.actionanand.localtell.app.ui

import com.actionanand.localtell.app.model.RadioCell

enum class SignalQuality { EXCELLENT, GOOD, FAIR, WEAK, VERY_WEAK }

data class SignalVisual(val dbm: Int, val quality: SignalQuality, val progress: Float)

fun signalVisual(cell: RadioCell): SignalVisual? {
    val dbm = cell.rsrp ?: cell.dbm ?: return null
    val quality = when {
        dbm >= -80 -> SignalQuality.EXCELLENT
        dbm >= -90 -> SignalQuality.GOOD
        dbm >= -100 -> SignalQuality.FAIR
        dbm >= -110 -> SignalQuality.WEAK
        else -> SignalQuality.VERY_WEAK
    }
    return SignalVisual(
        dbm = dbm,
        quality = quality,
        progress = ((dbm + 120f) / 40f).coerceIn(0f, 1f),
    )
}
