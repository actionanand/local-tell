package com.actionanand.localtell.app.saved

import com.actionanand.localtell.app.HomeStatus
import com.actionanand.localtell.app.LocalityState
import com.actionanand.localtell.app.backup.AppBackupCodec
import com.actionanand.localtell.app.backup.AppBackupData
import com.actionanand.localtell.app.journey.JourneyPoint
import com.actionanand.localtell.app.locationcode.LocalTellLocationCode
import com.actionanand.localtell.app.locationcode.LocationCodeDecodeResult
import com.actionanand.localtell.app.locationcode.LocationInputParser
import com.actionanand.localtell.app.locationcode.LocationInputResult
import com.actionanand.localtell.app.external.MapLinkBuilder
import com.actionanand.localtell.app.external.RideDestination
import com.actionanand.localtell.app.external.RideLinkBuilder
import org.junit.Assert.*
import org.junit.Test

class SavedPlaceLocationsTest {
    @Test fun `worldwide bookmarks reopen and decode offline without locality or pack`() {
        val coordinates = listOf(
            12.971598 to 77.594566, // India
            6.927079 to 79.861244, // Sri Lanka
            25.204849 to 55.270783, // UAE
            -33.868820 to 151.209296, // Australia
            48.856613 to 2.352222, // Europe
            -1.292066 to 36.821946, // Africa
            40.712776 to -74.005974, // North America
            -23.550520 to -46.633308, // South America
            90.0 to 180.0, -90.0 to -180.0, 0.0 to 0.0,
        )
        coordinates.forEach { (lat, lon) ->
            val bookmark = requireNotNull(savedPlaceFor(lat, lon, null, null, SavedPlaceSource.LOOKUP))
            val restored = AppBackupCodec.decode(AppBackupCodec.encode(AppBackupData(listOf(bookmark), emptyList()))).savedPlaces.single()
            val result = restored.toEasyLocation()
            assertNull(result.locality)
            assertEquals(restored, result.savedPlace)
            assertEquals(lat, result.originalLatitude, 0.0)
            assertEquals(lon, result.originalLongitude, 0.0)
            assertEquals(19, result.encoded.numericCode.count { it in '0'..'9' })
            assertEquals(15, result.encoded.shortCode.count { it != '-' })
            listOf(result.encoded.numericCode, result.encoded.shortCode).forEach { code ->
                val coordinate = LocationInputParser.parse(code) as LocationInputResult.Coordinate
                assertEquals(lat, coordinate.latitude, 0.0000001)
                assertEquals(lon, coordinate.longitude, 0.0000001)
            }
        }
    }

    @Test fun `previously shared codes including leading zeros remain compatible`() {
        val encoded = LocalTellLocationCode.encode(-90.0, -180.0)
        assertEquals("0 000 000 000 000 000 009", encoded.numericCode)
        assertEquals("22222-22222-22222", encoded.shortCode)
        assertTrue(LocalTellLocationCode.decodeNumeric(encoded.numericCode) is LocationCodeDecodeResult.Success)
        assertTrue(LocalTellLocationCode.decodeShort(encoded.shortCode) is LocationCodeDecodeResult.Success)
    }

    @Test fun `legacy Home and Journey results do not fabricate bookmark coordinates`() {
        val home = HomeStatus.Ready(emptyList(), null, LocalityState.NO_LOCALITY_MATCH)
        assertNull(savedPlaceFor(home.latitude, home.longitude, null, null, SavedPlaceSource.HOME))
        val point = JourneyPoint(1, 1, "Old locality", null, null, "LTE", "404-45", 1, 70)
        assertNull(savedPlaceFor(point.latitude, point.longitude, null, null, SavedPlaceSource.JOURNEY))
    }

    @Test fun `original measurement is preserved independently of canonical codes`() {
        val place = requireNotNull(savedPlaceFor(25.204849123, 55.270783456, 14.25f, null, SavedPlaceSource.EASY))
        val reopened = place.toEasyLocation()
        assertEquals(25.204849123, reopened.originalLatitude, 0.0)
        assertEquals(25.204849, reopened.encoded.latitude, 0.0)
        assertEquals(14.25f, requireNotNull(reopened.accuracyMetres), 0f)
        assertEquals(place, reopened.bookmark(SavedPlaceSource.LOOKUP))
    }

    @Test fun `invalid or incomplete coordinates never create a bookmark`() {
        assertNull(savedPlaceFor(null, 0.0, null, null, SavedPlaceSource.HOME))
        assertNull(savedPlaceFor(0.0, null, null, null, SavedPlaceSource.JOURNEY))
        assertNull(savedPlaceFor(91.0, 0.0, null, null, SavedPlaceSource.LOOKUP))
        assertNull(savedPlaceFor(0.0, Double.NaN, null, null, SavedPlaceSource.LOOKUP))
    }

    @Test fun `saved coordinates use the existing Maps and Uber builders`() {
        val location = requireNotNull(savedPlaceFor(25.204849, 55.270783, null, null, SavedPlaceSource.LOOKUP)).toEasyLocation()
        assertEquals("https://www.google.com/maps/search/?api=1&query=25.204849%2C55.270783",
            MapLinkBuilder.googleMapsUrl(location.encoded.latitude, location.encoded.longitude))
        val ride = RideDestination(location.encoded.latitude, location.encoded.longitude, "LocalTell", "Dubai")
        assertTrue(RideLinkBuilder.uberAsPickupUrl(ride).contains("pickup%5Blatitude%5D=25.204849"))
        assertTrue(RideLinkBuilder.uberAsDestinationUrl(ride).contains("dropoff%5Blongitude%5D=55.270783"))
    }
}
