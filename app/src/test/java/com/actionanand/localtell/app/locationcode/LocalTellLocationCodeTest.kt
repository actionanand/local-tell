package com.actionanand.localtell.app.locationcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LocalTellLocationCodeTest {
    @Test
    fun `V1 golden vectors remain stable`() {
        goldenVectors.forEach { vector ->
            val encoded = LocalTellLocationCode.encode(vector.latitude, vector.longitude)
            assertEquals(vector.latitude, encoded.latitude, 0.0)
            assertEquals(vector.longitude, encoded.longitude, 0.0)
            assertEquals(vector.numericCode, encoded.numericCode)
            assertEquals(vector.shortCode, encoded.shortCode)
            assertCoordinate(vector.latitude, vector.longitude, LocalTellLocationCode.decodeNumeric(vector.numericCode))
            assertCoordinate(vector.latitude, vector.longitude, LocalTellLocationCode.decodeShort(vector.shortCode))
        }
    }

    @Test
    fun `numeric and short codes round trip to the same six decimal coordinate`() {
        val encoded = LocalTellLocationCode.encode(12.9715984, 77.5945656)
        assertCoordinate(encoded.latitude, encoded.longitude, LocalTellLocationCode.decodeNumeric(encoded.numericCode))
        assertCoordinate(encoded.latitude, encoded.longitude, LocalTellLocationCode.decodeShort(encoded.shortCode))
    }

    @Test
    fun `supports global coordinate boundaries and formatting separators`() {
        listOf(0.0 to 0.0, -89.999999 to -179.999999, 90.0 to 180.0, -90.0 to -180.0).forEach { (latitude, longitude) ->
            val encoded = LocalTellLocationCode.encode(latitude, longitude)
            assertCoordinate(encoded.latitude, encoded.longitude, LocalTellLocationCode.decodeNumeric(encoded.numericCode.replace(" ", "-")))
            assertCoordinate(encoded.latitude, encoded.longitude, LocalTellLocationCode.decodeShort(encoded.shortCode.replace("-", " ")))
        }
    }

    @Test
    fun `random deterministic coordinates preserve six decimal canonical values`() {
        val random = Random(7)
        repeat(100) {
            val encoded = LocalTellLocationCode.encode(random.nextDouble(-90.0, 90.0), random.nextDouble(-180.0, 180.0))
            assertCoordinate(encoded.latitude, encoded.longitude, LocalTellLocationCode.decodeNumeric(encoded.numericCode))
            assertCoordinate(encoded.latitude, encoded.longitude, LocalTellLocationCode.decodeShort(encoded.shortCode))
        }
    }

    @Test
    fun `checksums and ambiguous short characters never decode`() {
        val encoded = LocalTellLocationCode.encode(8.181910, 77.352330)
        val numericReplacement = if (encoded.numericCode.last().isDigit() && encoded.numericCode.last() != '0') '0' else '1'
        val shortReplacement = if (encoded.shortCode.last() != '2') '2' else '3'
        assertFalse(LocalTellLocationCode.decodeNumeric(encoded.numericCode.dropLast(1) + numericReplacement) is LocationCodeDecodeResult.Success)
        assertFalse(LocalTellLocationCode.decodeShort(encoded.shortCode.dropLast(1) + shortReplacement) is LocationCodeDecodeResult.Success)
        assertEquals(LocationCodeDecodeResult.InvalidCharacter, LocalTellLocationCode.decodeShort("OOOOO-OOOOO-OOOOO"))
        assertTrue(LocalTellLocationCode.SAFE_ALPHABET.none { it in "01ILO" })
    }

    @Test
    fun `numeric presentation is grouped from the right without changing the raw code`() {
        assertEquals("1 234 567 890 123 456 789", LocalTellLocationCode.formatNumeric("1234567890123456789"))
        assertEquals("123 456 789", LocalTellLocationCode.formatNumeric("123456789"))
    }

    @Test
    fun `a valid all digit short code decodes as a short code`() {
        assertCoordinate(-89.999909, -41.404562, LocalTellLocationCode.decodeShort("22222-38462-99449"))
    }

    @Test
    fun `out of range coordinates are rejected`() {
        runCatching { LocalTellLocationCode.encode(90.1, 0.0) }.onSuccess { throw AssertionError("Expected range rejection") }
        runCatching { LocalTellLocationCode.encode(0.0, -180.1) }.onSuccess { throw AssertionError("Expected range rejection") }
    }

    @Test
    fun `unknown future versions are rejected after a valid checksum`() {
        val versionTwoPayload = LocalTellLocationCode.LOCATION_SPACE
        assertEquals(
            LocationCodeDecodeResult.UnsupportedVersion,
            LocalTellLocationCode.decodeNumeric(LocalTellLocationCode.numericCodeForPayloadForTest(versionTwoPayload)),
        )
    }

    private fun assertCoordinate(latitude: Double, longitude: Double, result: LocationCodeDecodeResult) {
        result as LocationCodeDecodeResult.Success
        assertEquals(latitude, result.latitude, 0.0)
        assertEquals(longitude, result.longitude, 0.0)
    }

    private data class GoldenVector(
        val latitude: Double,
        val longitude: Double,
        val numericCode: String,
        val shortCode: String,
    )

    private companion object {
        val goldenVectors = listOf(
            GoldenVector(0.000000, 0.000000, "0 324 000 002 700 000 002", "23AJF-FP6E3-7U43J"),
            GoldenVector(12.971598, 77.594566, "0 370 697 756 405 661 642", "23G93-PU3ER-X2S3P"),
            GoldenVector(8.088306, 77.538451, "0 353 117 905 156 267 573", "23E4K-GDYHA-PR872"),
            GoldenVector(-33.868820, 151.209296, "0 202 072 251 873 404 765", "22TPA-NB237-H7P3J"),
            GoldenVector(90.000000, 180.000000, "0 648 000 005 400 000 001", "24K3V-WBAT4-DN653"),
            GoldenVector(-90.000000, -180.000000, "0 000 000 000 000 000 009", "22222-22222-22222"),
        )
    }
}
