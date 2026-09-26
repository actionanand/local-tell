package com.actionanand.localtell.app.survey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioBandResolverTest {
    @Test fun `maps known NR ARFCNs`() {
        assertEquals("n78", RadioBandResolver.nrBand(634_080))
        assertEquals("n28", RadioBandResolver.nrBand(156_510))
    }

    @Test fun `maps known LTE EARFCNs`() {
        assertEquals(40, RadioBandResolver.lteBand(39_150))
        assertEquals(40, RadioBandResolver.lteBand(38_948))
        assertEquals(40, RadioBandResolver.lteBand(38_750))
        assertEquals(3, RadioBandResolver.lteBand(1_615))
        assertEquals(5, RadioBandResolver.lteBand(2_520))
        assertEquals(1, RadioBandResolver.lteBand(390))
    }

    @Test fun `decomposes LTE cell IDs`() {
        assertEquals(LteCellGrouping(558_120, 20), lteCellGrouping(142_878_740))
        assertEquals(LteCellGrouping(558_120, 21), lteCellGrouping(142_878_741))
    }

    @Test fun `treats saturated LTE timing advance as unusable`() {
        assertFalse(isUsableLteTimingAdvance(null))
        assertTrue(isUsableLteTimingAdvance(1))
        assertTrue(isUsableLteTimingAdvance(25))
        assertFalse(isUsableLteTimingAdvance(1_282))
    }
}
