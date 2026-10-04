package com.actionanand.localtell.app.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationLinksTest {
    @Test
    fun `coordinate text is locale independent and has six decimals`() {
        assertEquals("12.971598,77.594566", MapLinkBuilder.coordinateText(12.971598, 77.594566))
    }

    @Test
    fun `google maps search URL retains its trailing path slash`() {
        val url = MapLinkBuilder.googleMapsUrl(12.848793, 77.711493)

        assertEquals("https://www.google.com/maps/search/?api=1&query=12.848793%2C77.711493", url)
        assertTrue(url.contains("/maps/search/?"))
        assertFalse(url.contains("/maps/search?"))
    }

    @Test
    fun `ride destination keeps the canonical coordinate`() {
        val destination = RideDestination(8.181910, 77.352330, "LocalTell location", "8.181910,77.352330")
        assertEquals("8.181910,77.352330", MapLinkBuilder.coordinateText(destination.latitude, destination.longitude))
    }
}
