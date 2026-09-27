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
}
