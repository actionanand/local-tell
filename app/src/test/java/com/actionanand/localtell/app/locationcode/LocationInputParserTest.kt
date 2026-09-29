package com.actionanand.localtell.app.locationcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationInputParserTest {
    @Test
    fun `parses numeric and short LocalTell codes`() {
        val encoded = LocalTellLocationCode.encode(12.971598, 77.594566)
        assertCoordinate(LocationInputParser.parse(encoded.numericCode), LocationInputResult.Source.NUMERIC_CODE)
        assertCoordinate(LocationInputParser.parse(encoded.shortCode), LocationInputResult.Source.SHORT_CODE)
    }

    @Test
    fun `parses comma and whitespace coordinate pairs including negatives`() {
        assertCoordinate(LocationInputParser.parse(" 12.971598, 77.594566 "), LocationInputResult.Source.COORDINATE)
        assertCoordinate(LocationInputParser.parse("-8.181910  -77.352330"), LocationInputResult.Source.COORDINATE)
        listOf("12 77", "-12 77", "12 -77", "-12 -77").forEach { value ->
            assertCoordinate(LocationInputParser.parse(value), LocationInputResult.Source.COORDINATE)
        }
    }

    @Test
    fun `structural code detection handles numeric and all digit short forms`() {
        assertCoordinate(LocationInputParser.parse("0370697756405661642"), LocationInputResult.Source.NUMERIC_CODE)
        assertCoordinate(LocationInputParser.parse("222223846299449"), LocationInputResult.Source.SHORT_CODE)
        assertCoordinate(LocationInputParser.parse("22222-38462-99449"), LocationInputResult.Source.SHORT_CODE)
    }

    @Test
    fun `rejects invalid partial and ambiguous input`() {
        listOf("91, 0", "0, 181", "hello", "1234", "ABCDE-OFGHI-KMNPQ").forEach {
            assertTrue(LocationInputParser.parse(it) is LocationInputResult.Error)
        }
    }

    private fun assertCoordinate(result: LocationInputResult, source: LocationInputResult.Source) {
        result as LocationInputResult.Coordinate
        assertEquals(source, result.source)
    }
}
