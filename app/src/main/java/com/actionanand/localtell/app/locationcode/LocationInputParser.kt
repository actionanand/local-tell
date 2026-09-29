package com.actionanand.localtell.app.locationcode

sealed interface LocationInputResult {
    data class Coordinate(val latitude: Double, val longitude: Double, val source: Source) : LocationInputResult
    data class Error(val reason: LocationCodeDecodeResult? = null) : LocationInputResult
    enum class Source { NUMERIC_CODE, SHORT_CODE, COORDINATE }
}

object LocationInputParser {
    private val coordinatePattern = Regex("^([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*(?:,|\\s+)\\s*([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))$")

    fun parse(input: String): LocationInputResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return LocationInputResult.Error()
        coordinatePattern.matchEntire(trimmed)?.let { match ->
            val latitude = match.groupValues[1].toDoubleOrNull() ?: return LocationInputResult.Error()
            val longitude = match.groupValues[2].toDoubleOrNull() ?: return LocationInputResult.Error()
            return if (latitude in -90.0..90.0 && longitude in -180.0..180.0) {
                LocationInputResult.Coordinate(latitude, longitude, LocationInputResult.Source.COORDINATE)
            } else {
                LocationInputResult.Error(LocationCodeDecodeResult.OutOfRange)
            }
        }

        val normalized = trimmed.replace(Regex("[\\s-]+"), "").uppercase()
        return when {
            normalized.length == LocalTellLocationCode.NUMERIC_CODE_LENGTH && normalized.all { it in '0'..'9' } ->
                LocalTellLocationCode.decodeNumeric(normalized).toInputResult(LocationInputResult.Source.NUMERIC_CODE)
            normalized.length == LocalTellLocationCode.SHORT_CODE_LENGTH ->
                LocalTellLocationCode.decodeShort(normalized).toInputResult(LocationInputResult.Source.SHORT_CODE)
            else -> LocationInputResult.Error()
        }
    }

    private fun LocationCodeDecodeResult.toInputResult(source: LocationInputResult.Source) = when (this) {
        is LocationCodeDecodeResult.Success -> LocationInputResult.Coordinate(latitude, longitude, source)
        else -> LocationInputResult.Error(this)
    }
}
