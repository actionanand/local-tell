package com.actionanand.localtell.app.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationQualityTest {
    @Test
    fun `accuracy boundaries classify precise approximate and unusable locations`() {
        assertEquals(LocationQuality.PRECISE, LocationAccuracy.classify(true, 49.9f))
        assertEquals(LocationQuality.PRECISE, LocationAccuracy.classify(true, 50f))
        assertEquals(LocationQuality.APPROXIMATE, LocationAccuracy.classify(true, 50.1f))
        assertEquals(LocationQuality.APPROXIMATE, LocationAccuracy.classify(true, 100f))
        assertEquals(LocationQuality.APPROXIMATE, LocationAccuracy.classify(true, 250f))
        assertEquals(null, LocationAccuracy.classify(true, 250.1f))
        assertEquals(null, LocationAccuracy.classify(false, 20f))
    }

    @Test
    fun `better approximate candidates replace weaker candidates only`() {
        assertTrue(LocationAccuracy.isBetterApproximate(180f, null))
        assertTrue(LocationAccuracy.isBetterApproximate(120f, 180f))
        assertFalse(LocationAccuracy.isBetterApproximate(140f, 120f))
        assertTrue(LocationAccuracy.isBetterApproximate(80f, 120f))
        assertFalse(LocationAccuracy.isBetterApproximate(40f, 80f))
    }
}
