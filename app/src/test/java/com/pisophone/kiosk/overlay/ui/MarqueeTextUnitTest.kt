package com.pisophone.kiosk.overlay.ui

import androidx.compose.ui.graphics.Color
import com.pisophone.kiosk.ui.theme.BrandPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class MarqueeTextUnitTest {
    @Test
    fun noNameOrOnlyBlanksShowsThePlainDefault() {
        assertEquals("PisoPhone", MarqueeText.textFor(null))
        assertEquals("PisoPhone", MarqueeText.textFor(""))
        assertEquals("PisoPhone", MarqueeText.textFor("   "))
    }

    @Test
    fun aNameIsShownAsTypedWithoutItsEdgeBlanks() {
        assertEquals("Juan's Net", MarqueeText.textFor("  Juan's Net "))
    }

    @Test
    fun aShortNameIsRepeatedUntilTheRunningLineIsWiderThanAnyScreen() {
        for (name in listOf("A", "Piso", "PisoPhone", "Juan's Net Cafe")) {
            val line = MarqueeText.runningLine(name)
            assertTrue("$name: line is ${line.length} long", line.length >= MarqueeText.MIN_CHARS)
            assertTrue(MarqueeText.repeatsFor(name) >= 2)
        }
    }

    @Test
    fun aLongestNameStillRunsTwiceAtLeast() {
        val longest = "x".repeat(32)
        assertTrue(MarqueeText.repeatsFor(longest) >= 2)
        assertTrue(MarqueeText.runningLine(longest).startsWith(longest))
    }

    @Test
    fun theBandIsBigAndFast() {
        assertTrue(MarqueeText.TEXT_SP >= 28)
        assertTrue(MarqueeText.BAND_MIN_HEIGHT_DP >= 56)
        assertEquals(60, MarqueeText.SPEED_DP_PER_S)
    }

    private fun lum(c: Color): Double {
        fun ch(v: Float): Double = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double = (maxOf(lum(a), lum(b)) + 0.05) / (minOf(lum(a), lum(b)) + 0.05)

    @Test
    fun theNameIsReadableOnTheBandOfEverySixPalettes() {
        // the band is the palette's own backdrop gradient (top to bottom) and the text is its accent
        for (p in BrandPalette.all) {
            for (band in listOf(p.top, p.bottom)) {
                val ratio = contrast(p.accent, band)
                assertTrue("${p.id}: name on band is $ratio, needs 4.5", ratio >= 4.5)
            }
        }
    }
}
