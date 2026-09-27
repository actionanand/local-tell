package com.actionanand.localtell.app.data

import com.actionanand.localtell.app.model.CellularFingerprint
import com.actionanand.localtell.app.model.LocalityMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalityArchitectureTest {
    private val fingerprint = CellularFingerprint(1, "405", "869", "NR", 89, 4367237120)
    private val match = LocalityMatch("Example", "village", null, "District", "State", "ST", "polygon", "state-pack", 3)

    @Test fun `fingerprints are equal only for the same serving identity`() {
        assertEquals(fingerprint, fingerprint.copy())
        assertFalse(fingerprint == fingerprint.copy(cellId = 4367237121))
        assertFalse(fingerprint == fingerprint.copy(areaCode = 90))
    }

    @Test fun `cache reuse requires freshness and unchanged fingerprint`() {
        val now = 1_000_000L
        val cached = CachedLocality(match, now - LocalityCachePolicy.MAX_AGE_MS + 1, 12f, fingerprint)
        assertTrue(LocalityCachePolicy.canReuse(cached, fingerprint, true, now))
        assertFalse(LocalityCachePolicy.canReuse(cached, fingerprint.copy(cellId = 1), true, now))
        assertFalse(LocalityCachePolicy.canReuse(cached.copy(resolvedAt = now - LocalityCachePolicy.MAX_AGE_MS - 1), fingerprint, true, now))
        assertFalse(LocalityCachePolicy.canReuse(cached, fingerprint, false, now))
    }

    @Test fun `schema dispatch distinguishes geographic packs`() {
        assertEquals(OfflinePackSchema.LEGACY_CELL, OfflinePackSchema.fromVersion(1))
        assertEquals(OfflinePackSchema.TOWER_CELL, OfflinePackSchema.fromVersion(2))
        assertEquals(OfflinePackSchema.GEOGRAPHIC_LOCALITY, OfflinePackSchema.fromVersion(3))
        assertNull(OfflinePackSchema.fromVersion(4))
    }

    @Test fun `point in polygon handles an interior and exterior coordinate`() {
        val square = "0,0;0,10;10,10;10,0"
        assertTrue(PointInPolygon.contains(5.0, 5.0, square))
        assertFalse(PointInPolygon.contains(12.0, 5.0, square))
    }

    @Test fun `neighbourhood wins over village district and state polygons`() {
        val resolved = firstContaining(
            Candidate("state", "state", 1000.0, 4),
            Candidate("district", "administrative_boundary", 100.0, 3),
            Candidate("village", "village", 10.0, 2),
            Candidate("neighbourhood", "neighbourhood", 1.0, 1),
        )
        assertEquals("neighbourhood", resolved?.name)
    }

    @Test fun `village wins over district and state when no neighbourhood contains coordinate`() {
        val resolved = firstContaining(
            Candidate("state", "state", 1000.0, 3),
            Candidate("district", "administrative_boundary", 100.0, 2),
            Candidate("village", "village", 10.0, 1),
        )
        assertEquals("village", resolved?.name)
    }

    @Test fun `nearest fallback excludes administrative boundaries`() {
        assertFalse(LocalityLookupRules.nearestPlaceTypes.contains("administrative_boundary"))
        assertFalse(LocalityLookupRules.nearestPlaceTypes.contains("district"))
        assertTrue(LocalityLookupRules.nearestPlaceTypes.contains("city"))
    }

    @Test fun `smaller administrative polygon is ordered before larger one`() {
        val smaller = LocalityLookupRules.polygonOrder("administrative_boundary", 10.0, 2, 2)
        val larger = LocalityLookupRules.polygonOrder("administrative_boundary", 100.0, 1, 1)
        assertTrue(smaller < larger)
    }

    private fun firstContaining(vararg candidates: Candidate): Candidate? = candidates
        .filter { PointInPolygon.contains(5.0, 5.0, it.geometry) }
        .minByOrNull { LocalityLookupRules.polygonOrder(it.type, it.area, it.id, it.id) }

    private data class Candidate(
        val name: String,
        val type: String,
        val area: Double,
        val id: Long,
        val geometry: String = "0,0;0,10;10,10;10,0",
    )
}
