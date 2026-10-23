package com.pisophone.kiosk.ui

import androidx.compose.ui.graphics.Color
import com.pisophone.kiosk.ui.theme.BrandPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class LauncherThemeColorsUnitTest {
    private fun luminance(c: Color): Double {
        fun lin(v: Float): Double = v.toDouble().let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun `a shop with no palette gets today's home screen colours exactly`() {
        val today = LauncherThemeColors()
        val none = LauncherThemeColors.forBranding(null)
        assertEquals(today.bg, none.bg)
        assertEquals(today.surface, none.surface)
        assertEquals(today.cardBg, none.cardBg)
        assertEquals(today.primary, none.primary)
        assertEquals(today.primaryLight, none.primaryLight)
        assertEquals(today.textPrimary, none.textPrimary)
        assertEquals(today.textSecondary, none.textSecondary)
        assertEquals(today.textMuted, none.textMuted)
        assertEquals(today.border, none.border)
        assertEquals(today.borderEmerald, none.borderEmerald)
    }

    @Test
    fun `a palette changes the background and the accent only`() {
        val today = LauncherThemeColors()
        for (p in BrandPalette.all) {
            val t = LauncherThemeColors.forBranding(p.id)
            assertEquals(p.top, t.bg)
            assertEquals(p.accent, t.primary)
            assertEquals("text stays", today.textPrimary, t.textPrimary)
            assertEquals(today.textSecondary, t.textSecondary)
            assertEquals(today.surface, t.surface)
            assertEquals(today.cardBg, t.cardBg)
            assertEquals(today.border, t.border)
        }
    }

    @Test
    fun `the home screen text and accent stay readable on every palette's background`() {
        for (p in BrandPalette.all) {
            val t = LauncherThemeColors.forBranding(p.id)
            assertTrue("${p.id} primary text", contrast(t.textPrimary, t.bg) >= 4.5)
            assertTrue("${p.id} secondary text", contrast(t.textSecondary, t.bg) >= 4.5)
            assertTrue("${p.id} accent as text", contrast(t.primary, t.bg) >= 4.5)
            assertTrue("${p.id} accent in buttons", contrast(t.primaryLight, t.cardBg) >= 4.5)
        }
    }

    @Test
    fun `the credit is readable on the unbranded home screen too`() {
        val today = LauncherThemeColors()
        assertTrue(contrast(BrandPalette.technet.credit, today.bg) >= 4.5)
    }

    @Test
    fun `an unknown palette id falls back to the technet palette, not to a broken colour`() {
        val t = LauncherThemeColors.forBranding("nonsense")
        assertEquals(BrandPalette.technet.top, t.bg)
    }
}
