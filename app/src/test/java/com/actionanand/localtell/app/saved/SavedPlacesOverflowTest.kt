package com.actionanand.localtell.app.saved

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedPlacesOverflowTest {
    private fun place(id: String, savedAt: Long) = SavedPlace(
        id = id, displayName = "Place $id", localityName = "Locality $id", subDistrict = null,
        district = null, state = null, countryCode = "IN", latitude = 8.0 + savedAt / 1000.0, longitude = 77.0,
        savedAt = savedAt, source = SavedPlaceSource.EASY,
    )

    @Test fun withinLimitHasNoExcessAndRemovesNothing() {
        val places = listOf(place("a", 1), place("b", 2))
        assertEquals(0, SavedPlacesOverflow.excess(places.size, limit = 2))
        assertTrue(SavedPlacesOverflow.oldestToRemove(places, limit = 2).isEmpty())
    }

    @Test fun overLimitRemovesOnlyTheOldestExtras() {
        val places = listOf(place("new", 50), place("old", 10), place("mid", 30), place("oldest", 5))
        assertEquals(2, SavedPlacesOverflow.excess(places.size, limit = 2))
        assertEquals(listOf("oldest", "old"), SavedPlacesOverflow.oldestToRemove(places, limit = 2).map { it.id })
    }

    @Test fun equalTimestampsMatchTheLastEntriesOfTheNewestFirstList() {
        // Saved Places lists by saved_at DESC, id DESC, so the last entry has the smallest id.
        val places = listOf(place("b", 10), place("a", 10), place("c", 20))
        assertEquals(listOf("a"), SavedPlacesOverflow.oldestToRemove(places, limit = 2).map { it.id })
    }
}
