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
    fun `Uber destination keeps the rider pickup and supplies LocalTell dropoff`() {
        val destination = RideDestination(8.181910, 77.352330, "LocalTell location", "8.181910,77.352330")
        val url = RideLinkBuilder.uberAsDestinationUrl(destination)

        assertTrue(url.contains("pickup=my_location"))
        assertTrue(url.contains("dropoff%5Blatitude%5D=8.18191"))
        assertTrue(url.contains("dropoff%5Blongitude%5D=77.35233"))
        assertTrue(url.contains("dropoff%5Bnickname%5D=LocalTell+location"))
        assertTrue(url.contains("dropoff%5Bformatted_address%5D=8.181910%2C77.352330"))
        assertFalse(url.contains("pickup%5Blatitude%5D"))
    }

    @Test
    fun `Uber pickup supplies LocalTell pickup without a dropoff`() {
        val location = RideDestination(8.181910, 77.352330, "LocalTell location", "8.181910,77.352330")
        val url = RideLinkBuilder.uberAsPickupUrl(location)

        assertTrue(url.contains("pickup%5Blatitude%5D=8.18191"))
        assertTrue(url.contains("pickup%5Blongitude%5D=77.35233"))
        assertTrue(url.contains("pickup%5Bnickname%5D=LocalTell+location"))
        assertTrue(url.contains("pickup%5Bformatted_address%5D=8.181910%2C77.352330"))
        assertFalse(url.contains("pickup=my_location"))
        assertFalse(url.contains("dropoff%5Blatitude%5D"))
    }
}
