package com.actionanand.localtell.app.journey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class JourneyTrackingTest {
    @Test fun `same locality across repeated checks has one identity`() {
        assertEquals(
            journeyLocalityKey("Example locality", "Example subdistrict", "Example district", "Example state"),
            journeyLocalityKey("Example locality", "Example subdistrict", "Example district", "Example state"),
        )
    }

    @Test fun `different locality has a different journey identity`() {
        assertNotEquals(
            journeyLocalityKey("Example locality A", "Example subdistrict", "Example district", "Example state"),
            journeyLocalityKey("Example locality B", "Example subdistrict", "Example district", "Example state"),
        )
    }
}
