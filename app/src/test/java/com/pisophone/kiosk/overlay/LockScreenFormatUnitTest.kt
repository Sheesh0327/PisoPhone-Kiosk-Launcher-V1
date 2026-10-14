package com.pisophone.kiosk.overlay

import com.pisophone.kiosk.overlay.ui.BackdropTint
import com.pisophone.kiosk.overlay.ui.EffectsLevel
import com.pisophone.kiosk.overlay.ui.formatAdded
import com.pisophone.kiosk.overlay.ui.formatClock
import com.pisophone.kiosk.overlay.ui.formatMinutes
import com.pisophone.kiosk.overlay.ui.formatPesos
import com.pisophone.kiosk.overlay.ui.formatRate
import com.pisophone.kiosk.overlay.ui.rateRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockScreenFormatUnitTest {
    @Test
    fun `minutes read as min under an hour and hr from an hour`() {
        assertEquals("30 min", formatMinutes(30))
        assertEquals("59 min", formatMinutes(59))
        assertEquals("1 hr", formatMinutes(60))
        assertEquals("1 hr 30 min", formatMinutes(90))
        assertEquals("2 hr", formatMinutes(120))
        assertEquals("0 min", formatMinutes(0))
        assertEquals("0 min", formatMinutes(-5))
    }

    @Test
    fun `pesos drop the decimals when the amount is whole`() {
        assertEquals("₱5", formatPesos(5.0))
        assertEquals("₱10", formatPesos(10.0))
        assertEquals("₱2.50", formatPesos(2.5))
        assertEquals("₱0", formatPesos(0.0))
    }

    @Test
    fun `a rate reads peso equals minutes`() {
        assertEquals("₱5 = 30 min", formatRate(5.0, 30))
        assertEquals("₱10 = 1 hr", formatRate(10.0, 60))
    }

    @Test
    fun `rate rows are one two and four coins at the same rate`() {
        assertEquals(listOf("₱5 = 30 min"), rateRows(5.0, 30, 1))
        assertEquals(listOf("₱5 = 30 min", "₱10 = 1 hr"), rateRows(5.0, 30, 2))
        assertEquals(listOf("₱5 = 30 min", "₱10 = 1 hr", "₱20 = 2 hr"), rateRows(5.0, 30, 3))
        assertEquals(3, rateRows(5.0, 30, 9).size)
        assertEquals(1, rateRows(5.0, 30, 0).size)
    }

    @Test
    fun `effects follow the temperature, the animation scale and the video policy`() {
        assertEquals(EffectsLevel.Full, EffectsLevel.of(thermalStatus = 0, policyAllowsMotion = true, animationsOff = false))
        assertEquals(EffectsLevel.Events, EffectsLevel.of(thermalStatus = 2, policyAllowsMotion = true, animationsOff = false))
        assertEquals(EffectsLevel.Events, EffectsLevel.of(thermalStatus = 0, policyAllowsMotion = false, animationsOff = false))
        assertEquals(EffectsLevel.Still, EffectsLevel.of(thermalStatus = 0, policyAllowsMotion = true, animationsOff = true))
        assertEquals(EffectsLevel.Flat, EffectsLevel.of(thermalStatus = 3, policyAllowsMotion = true, animationsOff = false))
        assertEquals(EffectsLevel.Flat, EffectsLevel.of(thermalStatus = 4, policyAllowsMotion = false, animationsOff = true))
        assertTrue(EffectsLevel.Full.ambient && EffectsLevel.Full.events && EffectsLevel.Full.art)
        assertTrue(!EffectsLevel.Events.ambient && EffectsLevel.Events.events)
        assertFalse(EffectsLevel.Still.events)
        assertFalse(EffectsLevel.Flat.art)
    }

    @Test
    fun `old theme numbers map onto the three backdrop colours`() {
        assertEquals(BackdropTint.Dagat, BackdropTint.of(0))
        assertEquals(BackdropTint.Tropiko, BackdropTint.of(1))
        assertEquals(BackdropTint.Ginto, BackdropTint.of(2))
        assertEquals(BackdropTint.Dagat, BackdropTint.of(3))
        assertEquals(BackdropTint.Tropiko, BackdropTint.of(7))
        assertEquals(BackdropTint.Ginto, BackdropTint.of(-1))
    }

    @Test
    fun `added time reads plus minutes, plus seconds under a minute`() {
        assertEquals("+30 min", formatAdded(1800))
        assertEquals("+1 hr", formatAdded(3600))
        assertEquals("+1 min", formatAdded(60))
        assertEquals("+45 sec", formatAdded(45))
    }

    @Test
    fun `the clock reads MM SS and H MM SS from one hour`() {
        assertEquals("00:00", formatClock(0))
        assertEquals("07:57", formatClock(477))
        assertEquals("59:59", formatClock(3599))
        assertEquals("1:00:00", formatClock(3600))
        assertEquals("1:01:05", formatClock(3665))
        assertEquals("00:00", formatClock(-3))
    }
}
