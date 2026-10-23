package com.pisophone.kiosk.overlay

import com.pisophone.kiosk.overlay.ui.AnimationDensity
import com.pisophone.kiosk.overlay.ui.MarqueeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarqueeRuleUnitTest {
    private fun scrolls(
        density: AnimationDensity = AnimationDensity.Normal,
        thermal: Int = 0,
        powerSave: Boolean = false,
        battery: Int = 80,
        charging: Boolean = false,
        animationsOn: Boolean = true,
        screenInFront: Boolean = true,
    ) = MarqueeRule.shouldScroll(density, thermal, powerSave, battery, charging, animationsOn, screenInFront)

    @Test
    fun `density off stills the name and the other levels let it scroll`() {
        assertFalse(scrolls(density = AnimationDensity.Off))
        for (d in listOf(AnimationDensity.Low, AnimationDensity.Normal, AnimationDensity.High)) assertTrue("$d", scrolls(density = d))
        assertFalse("the ladder still wins at high", scrolls(density = AnimationDensity.High, thermal = 2))
    }

    @Test
    fun `a cool charged phone with animations on and the screen in front scrolls`() {
        assertTrue(scrolls())
    }

    @Test
    fun `it scrolls at thermal none and light only`() {
        assertTrue(scrolls(thermal = 0))
        assertTrue(scrolls(thermal = 1))
        for (thermal in 2..6) assertFalse("thermal $thermal", scrolls(thermal = thermal))
    }

    @Test
    fun `power save, animations off or a screen not in front stop it`() {
        assertFalse(scrolls(powerSave = true))
        assertFalse(scrolls(animationsOn = false))
        assertFalse(scrolls(screenInFront = false))
    }

    @Test
    fun `below 15 percent while draining it stops, charging or unknown does not`() {
        assertFalse(scrolls(battery = 14))
        assertFalse(scrolls(battery = 0))
        assertTrue(scrolls(battery = 15))
        assertTrue("charging at 10% keeps going", scrolls(battery = 10, charging = true))
        assertTrue("an unknown battery (-1) is not a low battery", scrolls(battery = -1))
        assertEquals(15, MarqueeRule.LOW_BATTERY_PERCENT)
    }

    @Test
    fun `the unknown or unsupported thermal value 0 on old phones still scrolls`() {
        // phones before Android 10 report no thermal status; the lock screen passes 0 for them
        assertTrue(scrolls(thermal = 0, battery = -1))
    }
}
