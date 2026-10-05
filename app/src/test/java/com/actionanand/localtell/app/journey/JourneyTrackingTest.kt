package com.actionanand.localtell.app.journey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyTrackingTest {
    @Test fun `no tracking effects are allowed before a run begins`() {
        val guard = JourneyRunGuard()
        assertTrue(guard.stopRequested)
        assertFalse(guard.runIfActive(0L) { error("unexpected tracking effect") })
    }

    @Test fun `stop marks the run inactive before cancellation and acknowledgement`() {
        val guard = JourneyRunGuard()
        val generation = guard.begin()
        var acknowledged = false
        guard.stop {
            assertTrue(guard.stopRequested)
            assertFalse(guard.isActive(generation))
            acknowledged = true
        }
        assertTrue(acknowledged)
    }

    @Test fun `stop before first locality rejects later state and database effects`() {
        val guard = JourneyRunGuard()
        val generation = guard.begin()
        guard.stop { }
        var effects = 0
        repeat(3) { assertFalse(guard.runIfActive(generation) { effects++ }) }
        assertEquals(0, effects)
    }

    @Test fun `restart admits the new run but rejects the stopped generation`() {
        val guard = JourneyRunGuard()
        val previous = guard.begin()
        guard.stop { }
        val current = guard.begin()
        assertFalse(guard.stopRequested)
        assertFalse(guard.runIfActive(previous) { error("stale tracking effect") })
        var effects = 0
        assertTrue(guard.runIfActive(current) { effects++ })
        assertEquals(1, effects)
    }

    @Test fun `active run can publish and record until stopped`() {
        val guard = JourneyRunGuard()
        val generation = guard.begin()
        var effects = 0
        assertTrue(guard.runIfActive(generation) { effects++ })
        guard.stop { }
        assertFalse(guard.runIfActive(generation) { effects++ })
        assertEquals(1, effects)
    }

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
