package com.actionanand.localtell.app.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssFixEligibilityTest {
    @Test
    fun `recent precise fixes include the accuracy and age boundaries`() {
        assertTrue(eligible(ageMillis = 60_000L, accuracyMetres = 49.9f))
        assertTrue(eligible(ageMillis = 60_000L, accuracyMetres = 50f))
    }

    @Test
    fun `imprecise stale future and missing-accuracy fixes are rejected`() {
        assertFalse(eligible(ageMillis = 1L, accuracyMetres = 50.1f))
        assertFalse(eligible(ageMillis = 60_001L, accuracyMetres = 10f))
        assertFalse(eligible(ageMillis = -1L, accuracyMetres = 10f))
        assertFalse(eligible(ageMillis = null, accuracyMetres = 10f))
        assertFalse(eligible(ageMillis = 1L, accuracyMetres = 10f, hasAccuracy = false))
    }

    private fun eligible(ageMillis: Long?, accuracyMetres: Float, hasAccuracy: Boolean = true): Boolean =
        GnssFixEligibility.isRecentPreciseFix(
            ageMillis = ageMillis,
            hasAccuracy = hasAccuracy,
            accuracyMetres = accuracyMetres,
            maxAccuracyMetres = OneShotGnssLocator.MAX_ACCURACY_METRES,
            maxAgeMillis = OneShotGnssLocator.RECENT_FIX_MAX_AGE_MS,
        )
}
