package com.actionanand.localtell.app.ui

import com.actionanand.localtell.app.model.RadioCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignalVisualTest {
    @Test
    fun `signal quality uses the documented boundaries and clamps progress`() {
        assertVisual(-120, SignalQuality.VERY_WEAK, 0f)
        assertVisual(-111, SignalQuality.VERY_WEAK, 0.225f)
        assertVisual(-110, SignalQuality.WEAK, 0.25f)
        assertVisual(-101, SignalQuality.WEAK, 0.475f)
        assertVisual(-100, SignalQuality.FAIR, 0.5f)
        assertVisual(-91, SignalQuality.FAIR, 0.725f)
        assertVisual(-90, SignalQuality.GOOD, 0.75f)
        assertVisual(-81, SignalQuality.GOOD, 0.975f)
        assertVisual(-80, SignalQuality.EXCELLENT, 1f)
        assertVisual(-70, SignalQuality.EXCELLENT, 1f)
    }

    @Test
    fun `rsrp is preferred and absent signal does not create a visual`() {
        assertEquals(-95, signalVisual(cell(rsrp = -95, dbm = -70))?.dbm)
        assertEquals(-105, signalVisual(cell(dbm = -105))?.dbm)
        assertNull(signalVisual(cell()))
    }

    private fun assertVisual(dbm: Int, quality: SignalQuality, progress: Float) {
        val visual = signalVisual(cell(rsrp = dbm))!!
        assertEquals(quality, visual.quality)
        assertEquals(progress, visual.progress, 0.0001f)
    }

    private fun cell(rsrp: Int? = null, dbm: Int? = null) = RadioCell(
        radio = "LTE",
        mcc = "405",
        mnc = "869",
        areaCode = 1,
        cellId = 1,
        dbm = dbm,
        registered = true,
        rsrp = rsrp,
    )
}
