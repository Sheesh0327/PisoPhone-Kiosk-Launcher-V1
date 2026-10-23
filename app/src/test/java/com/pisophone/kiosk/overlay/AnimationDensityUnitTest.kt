package com.pisophone.kiosk.overlay

import com.pisophone.kiosk.overlay.ui.AmbientBudget
import com.pisophone.kiosk.overlay.ui.AnimationDensity
import com.pisophone.kiosk.overlay.ui.EffectsLevel
import com.pisophone.kiosk.overlay.ui.PhoneState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationDensityUnitTest {
    private val everyDensity = AnimationDensity.values().toList()

    /** The whole ladder for one phone state; the defaults are a cool, charged phone with animations on and the screen in front. */
    private fun budget(
        density: AnimationDensity,
        thermal: Int = 0,
        powerSave: Boolean = false,
        battery: Int = 80,
        charging: Boolean = false,
        animationsOn: Boolean = true,
        screenInFront: Boolean = true,
    ) = AmbientBudget.of(
        density = density,
        phone = PhoneState(
            thermalStatus = thermal,
            videoSwitchOn = true,
            lowRamDevice = false,
            powerSaveMode = powerSave,
            batteryPercent = battery,
            charging = charging,
            animationsOn = animationsOn,
            screenInFront = screenInFront,
        ),
    )

    @Test
    fun `words map to levels and anything unknown or missing is normal`() {
        assertEquals(AnimationDensity.Off, AnimationDensity.of("off"))
        assertEquals(AnimationDensity.Low, AnimationDensity.of("low"))
        assertEquals(AnimationDensity.Normal, AnimationDensity.of("normal"))
        assertEquals(AnimationDensity.High, AnimationDensity.of("high"))
        assertEquals(AnimationDensity.Normal, AnimationDensity.of(null))
        assertEquals(AnimationDensity.Normal, AnimationDensity.of("turbo"))
        assertEquals(AnimationDensity.Normal, AnimationDensity.of(""))
    }

    @Test
    fun `normal on a cool phone reproduces today's screen exactly`() {
        val b = budget(AnimationDensity.Normal)
        assertEquals(10, b.stickers)
        assertTrue(b.stickersMove)
        assertEquals(12, b.bubbles)
        assertEquals(20, b.sparkles)
        assertTrue(b.fallingCoins)
        assertTrue(b.shootingStar)
        assertTrue(b.cloudsDrift)
        assertTrue(b.raysTurn)
        assertEquals(60_000, b.loopMs)
        assertTrue(b.art)
    }

    @Test
    fun `off has no ambient motion at any temperature but keeps the picture`() {
        for (thermal in 0..4) {
            val b = budget(AnimationDensity.Off, thermal = thermal)
            assertFalse("moves at thermal $thermal", b.anythingMoves)
            assertEquals(0, b.bubbles)
            assertEquals(0, b.sparkles)
            if (thermal < EffectsLevel.THERMAL_SEVERE) assertEquals("the picture stays", 10, b.stickers)
        }
    }

    @Test
    fun `low shows 4 stickers, no bubbles and no twinkle, with a slow drift only`() {
        val b = budget(AnimationDensity.Low)
        assertEquals(4, b.stickers)
        assertEquals(0, b.bubbles)
        assertEquals(0, b.sparkles)
        assertFalse(b.fallingCoins)
        assertFalse(b.shootingStar)
        assertTrue(b.cloudsDrift)
        assertTrue("slower than normal", b.loopMs > 60_000)
        assertTrue(b.art)
    }

    @Test
    fun `high is livelier than normal but never has more particles`() {
        val n = budget(AnimationDensity.Normal)
        val h = budget(AnimationDensity.High)
        assertTrue(h.loopMs < n.loopMs)
        assertTrue(h.bubbles <= n.bubbles)
        assertTrue(h.sparkles <= n.sparkles)
        assertTrue(h.ambientParticles <= n.ambientParticles)
        assertTrue(h.stickers <= n.stickers)
        assertEquals(16, AmbientBudget.BURST_PARTICLE_CAP)
    }

    @Test
    fun `no level is busier than normal`() {
        val n = budget(AnimationDensity.Normal)
        for (d in everyDensity) {
            for (thermal in 0..4) {
                val b = budget(d, thermal = thermal)
                assertTrue("$d at $thermal", b.ambientParticles <= n.ambientParticles)
                assertTrue("$d at $thermal", b.stickers <= n.stickers)
            }
        }
    }

    @Test
    fun `moderate heat drops the ambient motion at every level including high`() {
        for (d in everyDensity) {
            val b = budget(d, thermal = EffectsLevel.THERMAL_MODERATE)
            assertFalse("$d moves while moderate", b.anythingMoves)
            assertEquals(0, b.bubbles)
            assertEquals(0, b.sparkles)
            assertFalse(b.fallingCoins)
            assertFalse(b.shootingStar)
        }
    }

    @Test
    fun `severe heat flattens the art at every level including high`() {
        for (d in everyDensity) {
            for (thermal in listOf(EffectsLevel.THERMAL_SEVERE, 4)) {
                val b = budget(d, thermal = thermal)
                assertFalse("$d art at $thermal", b.art)
                assertEquals(0, b.stickers)
                assertFalse(b.anythingMoves)
                assertEquals(0, b.ambientParticles)
            }
        }
    }

    @Test
    fun `power save, a low draining battery, animations off or a screen not in front all calm the screen at every level`() {
        for (d in everyDensity) {
            assertFalse("$d power save", budget(d, powerSave = true).anythingMoves)
            assertFalse("$d 14% draining", budget(d, battery = 14).anythingMoves)
            assertFalse("$d animations off", budget(d, animationsOn = false).anythingMoves)
            assertFalse("$d screen not in front", budget(d, screenInFront = false).anythingMoves)
        }
        assertTrue("charging at 10% may still move", budget(AnimationDensity.Normal, battery = 10, charging = true).anythingMoves)
        assertTrue("15% is not low", budget(AnimationDensity.Normal, battery = 15).anythingMoves)
        assertTrue("unknown battery (-1) is not low", budget(AnimationDensity.Normal, battery = -1).anythingMoves)
    }

    @Test
    fun `the ladder is a cap, never an override`() {
        // for every phone state, no density yields more motion than the same state at normal
        for (d in everyDensity) {
            for (thermal in 0..4) {
                for (powerSave in listOf(false, true)) {
                    for (battery in listOf(-1, 10, 14, 15, 80)) {
                        for (charging in listOf(false, true)) {
                            for (animationsOn in listOf(false, true)) {
                                val b = budget(d, thermal, powerSave, battery, charging, animationsOn)
                                val n = budget(AnimationDensity.Normal, thermal, powerSave, battery, charging, animationsOn)
                                if (!n.anythingMoves) assertFalse("$d moves where normal is held still", b.anythingMoves)
                                if (!n.art) assertFalse("$d draws where normal is flat", b.art)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `density cannot reach the coin feedback`() {
        // The coin rain, sparkle, pop-over and spoken confirmation are gated by EffectsLevel.events (and the speech by its own
        // switch). The density reaches only AmbientBudget; EffectsLevel.of has no density parameter and a budget carries no
        // field about events, so `off` cannot take a payment's feedback away.
        val levelOf = EffectsLevel.Companion::class.java.declaredMethods.single { it.name == "of" && !it.isSynthetic }
        assertEquals(3, levelOf.parameterTypes.size)
        val fields = AmbientBudget::class.java.declaredFields.map { it.name.lowercase() }
        assertNull(fields.firstOrNull { "coinrain" in it || "popover" in it || "speech" in it || "event" in it })
        for (thermal in 0..2) {
            assertTrue(EffectsLevel.of(thermal, true, false).events)
        }
    }
}
