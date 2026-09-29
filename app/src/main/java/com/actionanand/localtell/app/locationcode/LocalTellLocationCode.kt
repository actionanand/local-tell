package com.actionanand.localtell.app.locationcode

import java.nio.ByteBuffer
import kotlin.math.roundToLong

data class EncodedLocation(
    val latitude: Double,
    val longitude: Double,
    val numericCode: String,
    val shortCode: String,
)

sealed interface LocationCodeDecodeResult {
    data class Success(val latitude: Double, val longitude: Double) : LocationCodeDecodeResult
    data object InvalidLength : LocationCodeDecodeResult
    data object InvalidCharacter : LocationCodeDecodeResult
    data object InvalidChecksum : LocationCodeDecodeResult
    data object UnsupportedVersion : LocationCodeDecodeResult
    data object OutOfRange : LocationCodeDecodeResult
    data object MalformedNumericCode : LocationCodeDecodeResult
    data object MalformedShortCode : LocationCodeDecodeResult
}

/**
 * LocalTell Location Code V1 encodes a quantized coordinate entirely on-device.
 *
 * latitude and longitude are rounded to six decimals, packed into a Long location key, then
 * presented as either an 18-digit decimal payload plus Verhoeff check digit or a 13-character
 * base-31 payload plus two CRC-8/ATM characters. Both forms decode the same canonical payload;
 * this representation preserves a coordinate, not the original GNSS accuracy.
 */
object LocalTellLocationCode {
    const val VERSION = 1
    const val LAT_SCALE = 1_000_000L
    const val LON_SCALE = 1_000_000L
    const val LON_BUCKETS = 360_000_001L
    const val LOCATION_SPACE = 180_000_001L * LON_BUCKETS
    const val NUMERIC_PAYLOAD_LENGTH = 18
    const val NUMERIC_CODE_LENGTH = NUMERIC_PAYLOAD_LENGTH + 1
    const val SHORT_PAYLOAD_LENGTH = 13
    const val SHORT_CODE_LENGTH = SHORT_PAYLOAD_LENGTH + 2
    const val SAFE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    private const val MAX_VERSION = 15

    fun encode(latitude: Double, longitude: Double): EncodedLocation {
        val locationKey = coordinateToKey(latitude, longitude) ?: throw IllegalArgumentException("Coordinate out of range")
        val payload = payloadFor(VERSION, locationKey)
        return EncodedLocation(
            latitude = latitudeForKey(locationKey),
            longitude = longitudeForKey(locationKey),
            numericCode = formatNumeric(numericPayload(payload) + verhoeffCheckDigit(numericPayload(payload))),
            shortCode = formatShort(shortPayload(payload) + shortChecksum(payload)),
        )
    }

    fun decodeNumeric(value: String): LocationCodeDecodeResult {
        val normalized = normalize(value)
        if (normalized.length != NUMERIC_CODE_LENGTH) return LocationCodeDecodeResult.InvalidLength
        if (!normalized.all(Char::isDigit)) return LocationCodeDecodeResult.MalformedNumericCode
        if (!verhoeffValid(normalized)) return LocationCodeDecodeResult.InvalidChecksum
        return decodePayload(normalized.dropLast(1).toLongOrNull() ?: return LocationCodeDecodeResult.MalformedNumericCode)
    }

    fun decodeShort(value: String): LocationCodeDecodeResult {
        val normalized = normalize(value)
        if (normalized.length != SHORT_CODE_LENGTH) return LocationCodeDecodeResult.InvalidLength
        if (normalized.any { it !in SAFE_ALPHABET }) return LocationCodeDecodeResult.InvalidCharacter
        val payload = decodeBase31(normalized.take(SHORT_PAYLOAD_LENGTH)) ?: return LocationCodeDecodeResult.MalformedShortCode
        if (shortChecksum(payload) != normalized.takeLast(2)) return LocationCodeDecodeResult.InvalidChecksum
        return decodePayload(payload)
    }

    fun formatNumeric(value: String): String {
        val firstGroupLength = value.length % 3
        return buildList {
            if (firstGroupLength != 0) add(value.take(firstGroupLength))
            addAll(value.drop(firstGroupLength).chunked(3))
        }.joinToString(" ")
    }

    fun formatShort(value: String): String = value.chunked(5).joinToString("-")

    internal fun numericCodeForPayloadForTest(payload: Long): String =
        formatNumeric(numericPayload(payload) + verhoeffCheckDigit(numericPayload(payload)))

    private fun normalize(value: String) = value.trim().replace(Regex("[\\s-]+"), "").uppercase()

    private fun coordinateToKey(latitude: Double, longitude: Double): Long? {
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        val latIndex = ((latitude + 90.0) * LAT_SCALE).roundToLong()
        val lonIndex = ((longitude + 180.0) * LON_SCALE).roundToLong()
        if (latIndex !in 0L..180_000_000L || lonIndex !in 0L..360_000_000L) return null
        return Math.addExact(Math.multiplyExact(latIndex, LON_BUCKETS), lonIndex)
    }

    private fun payloadFor(version: Int, locationKey: Long): Long {
        require(version in 1..MAX_VERSION)
        require(locationKey in 0 until LOCATION_SPACE)
        return Math.addExact(Math.multiplyExact((version - 1).toLong(), LOCATION_SPACE), locationKey)
    }

    private fun decodePayload(payload: Long): LocationCodeDecodeResult {
        if (payload < 0) return LocationCodeDecodeResult.OutOfRange
        val version = payload / LOCATION_SPACE + 1
        if (version !in 1L..MAX_VERSION.toLong()) return LocationCodeDecodeResult.UnsupportedVersion
        if (version != VERSION.toLong()) return LocationCodeDecodeResult.UnsupportedVersion
        val locationKey = payload % LOCATION_SPACE
        return LocationCodeDecodeResult.Success(latitudeForKey(locationKey), longitudeForKey(locationKey))
    }

    private fun latitudeForKey(locationKey: Long): Double = (locationKey / LON_BUCKETS - 90_000_000L) / LAT_SCALE.toDouble()

    private fun longitudeForKey(locationKey: Long): Double = (locationKey % LON_BUCKETS - 180_000_000L) / LON_SCALE.toDouble()

    private fun numericPayload(payload: Long): String = payload.toString().padStart(NUMERIC_PAYLOAD_LENGTH, '0')

    private fun shortPayload(payload: Long): String {
        var remaining = payload
        val characters = CharArray(SHORT_PAYLOAD_LENGTH)
        for (index in characters.lastIndex downTo 0) {
            characters[index] = SAFE_ALPHABET[(remaining % SAFE_ALPHABET.length).toInt()]
            remaining /= SAFE_ALPHABET.length
        }
        require(remaining == 0L) { "Payload exceeds short-code capacity" }
        return characters.concatToString()
    }

    private fun decodeBase31(value: String): Long? = runCatching {
        value.fold(0L) { result, character ->
            Math.addExact(Math.multiplyExact(result, SAFE_ALPHABET.length.toLong()), SAFE_ALPHABET.indexOf(character).toLong())
        }
    }.getOrNull()

    /** CRC-8/ATM: polynomial 0x07, init 0x00, no reflection, xorout 0x00. */
    private fun shortChecksum(payload: Long): String {
        var crc = 0
        ByteBuffer.allocate(Long.SIZE_BYTES).putLong(payload).array().forEach { byte ->
            crc = crc xor (byte.toInt() and 0xff)
            repeat(8) { crc = if ((crc and 0x80) != 0) (crc shl 1 xor 0x07) and 0xff else (crc shl 1) and 0xff }
        }
        return "${SAFE_ALPHABET[crc / SAFE_ALPHABET.length]}${SAFE_ALPHABET[crc % SAFE_ALPHABET.length]}"
    }

    private fun verhoeffCheckDigit(value: String): Char {
        var checksum = 0
        value.reversed().forEachIndexed { index, character -> checksum = VERHOEFF_D[checksum][VERHOEFF_P[(index + 1) % 8][character.digitToInt()]] }
        return VERHOEFF_INV[checksum].digitToChar()
    }

    private fun verhoeffValid(value: String): Boolean {
        var checksum = 0
        value.reversed().forEachIndexed { index, character -> checksum = VERHOEFF_D[checksum][VERHOEFF_P[index % 8][character.digitToInt()]] }
        return checksum == 0
    }

    private val VERHOEFF_D = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), intArrayOf(1, 2, 3, 4, 0, 6, 7, 8, 9, 5),
        intArrayOf(2, 3, 4, 0, 1, 7, 8, 9, 5, 6), intArrayOf(3, 4, 0, 1, 2, 8, 9, 5, 6, 7),
        intArrayOf(4, 0, 1, 2, 3, 9, 5, 6, 7, 8), intArrayOf(5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
        intArrayOf(6, 5, 9, 8, 7, 1, 0, 4, 3, 2), intArrayOf(7, 6, 5, 9, 8, 2, 1, 0, 4, 3),
        intArrayOf(8, 7, 6, 5, 9, 3, 2, 1, 0, 4), intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
    )
    private val VERHOEFF_P = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), intArrayOf(1, 5, 7, 6, 2, 8, 3, 0, 9, 4),
        intArrayOf(5, 8, 0, 3, 7, 9, 6, 1, 4, 2), intArrayOf(8, 9, 1, 6, 0, 4, 3, 5, 2, 7),
        intArrayOf(9, 4, 5, 3, 1, 2, 6, 8, 7, 0), intArrayOf(4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
        intArrayOf(2, 7, 9, 3, 8, 0, 6, 4, 1, 5), intArrayOf(7, 0, 4, 6, 9, 1, 3, 2, 5, 8),
    )
    private val VERHOEFF_INV = intArrayOf(0, 4, 3, 2, 1, 5, 6, 7, 8, 9)
}
