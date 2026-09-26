package com.actionanand.localtell.app.survey

/** Known 3GPP downlink NR-ARFCN and LTE EARFCN ranges used for survey exports. */
object RadioBandResolver {
    fun derivedBand(radio: String, channelNumber: Int?): String? = when (radio) {
        "NR" -> channelNumber?.let(::nrBand)
        "LTE" -> channelNumber?.let(::lteBand)?.toString()
        else -> null
    }

    fun nrBand(nrarfcn: Int): String? = when (nrarfcn) {
        in 151_600..160_600 -> "n28"
        in 620_000..680_000 -> "n78"
        else -> null
    }

    fun lteBand(earfcn: Int): Int? = when (earfcn) {
        in 0..599 -> 1
        in 1_200..1_949 -> 3
        in 2_400..2_649 -> 5
        in 38_650..39_649 -> 40
        else -> null
    }
}

data class LteCellGrouping(val enbId: Long, val sectorId: Int)

fun lteCellGrouping(cellId: Long): LteCellGrouping? =
    cellId.takeIf { it >= 0 }?.let { LteCellGrouping(it shr 8, (it and 0xFF).toInt()) }

fun isUsableLteTimingAdvance(value: Int?): Boolean = value != null && value in 0 until 1_282
