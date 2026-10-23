package com.pisophone.kiosk.ui.theme

import androidx.compose.ui.graphics.Color
import com.pisophone.kiosk.overlay.ui.BackdropTint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The gate for feature 013: no palette a shop can pick may hide the text, the icons or the maker's credit. The numbers are the
 * ones in docs/design/GUI_SPEC_PISOPHONE_KIDS.md sections 2 and 9: 4.5:1 for text, 3:1 for icons and outlines.
 * A new palette that fails here does not get into the product.
 */
class BrandPaletteContrastUnitTest {
    private fun channel(v: Float): Double = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    private fun luminance(c: Color): Double = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun theListStartsWithTheDefaultAndHasSixDistinctIds() {
        assertEquals(listOf("technet", "dagat", "tropiko", "ginto", "rosas", "berde"), BrandPalette.all.map { it.id })
        assertEquals(BrandPalette.DEFAULT_ID, BrandPalette.all.first().id)
    }

    @Test
    fun primaryTextIsReadableOnTheTopAndBottomOfEveryBackdrop() {
        for (p in BrandPalette.all) {
            for (bg in listOf(p.top, p.bottom)) {
                val ratio = contrast(TechNet.text, bg)
                assertTrue("${p.id}: text on backdrop is $ratio, needs 4.5", ratio >= 4.5)
            }
        }
    }

    @Test
    fun iconsAndOutlinesStandOutOnEveryBackdrop() {
        for (p in BrandPalette.all) {
            for (bg in listOf(p.top, p.bottom)) {
                val outline = contrast(TechNet.lineStrong, bg)
                val accent = contrast(p.accent, bg)
                assertTrue("${p.id}: outline on backdrop is $outline, needs 3", outline >= 3.0)
                assertTrue("${p.id}: accent on backdrop is $accent, needs 3", accent >= 3.0)
            }
        }
    }

    @Test
    fun theAccentCarriesTextSoItIsReadableOnEveryBackdrop() {
        // the header title and the shop-name marquee are drawn in the accent: text, so 4.5:1 rather than the 3:1 of an icon
        for (p in BrandPalette.all) {
            for (bg in listOf(p.top, p.bottom)) {
                val ratio = contrast(p.accent, bg)
                assertTrue("${p.id}: the accent on the backdrop is $ratio, needs 4.5 for text", ratio >= 4.5)
            }
        }
    }

    @Test
    fun theMakersCreditIsReadableOnEveryBackdrop() {
        for (p in BrandPalette.all) {
            for (bg in listOf(p.top, p.bottom)) {
                val ratio = contrast(p.credit, bg)
                assertTrue("${p.id}: the credit on the backdrop is $ratio, needs 4.5", ratio >= 4.5)
            }
        }
    }

    @Test
    fun anUnknownOrMissingIdGivesTheDefaultLook() {
        assertSame(BrandPalette.technet, BrandPalette.of("nonsense"))
        assertSame(BrandPalette.technet, BrandPalette.of(""))
        assertSame(BrandPalette.technet, BrandPalette.of(null))
        assertSame(BrandPalette.rosas, BrandPalette.of("rosas"))
    }

    @Test
    fun theThreeOldBackdropsAreKeptColourForColour() {
        // an unbranded phone must look exactly like today (criterion 6): the golden screenshots depend on it
        val pairs = listOf(
            BackdropTint.Dagat to BrandPalette.technet,
            BackdropTint.Tropiko to BrandPalette.tropiko,
            BackdropTint.Ginto to BrandPalette.ginto,
        )
        for ((old, p) in pairs) {
            assertEquals(old.top, p.top)
            assertEquals(old.bottom, p.bottom)
            assertEquals(old.cloudA, p.cloudA)
            assertEquals(old.cloudB, p.cloudB)
            assertEquals(old.cloudC, p.cloudC)
        }
        for (i in -4..9) assertEquals(BackdropTint.of(i).top, BrandPalette.forTheme(i).top)
    }

    @Test
    fun moneyWarningAndSuccessColoursAreNotPartOfAPalette() {
        // gold, red and teal live in TechNet only; a palette has no field for them
        val fields = BrandPalette::class.java.declaredFields.map { it.name }.toSet()
        assertTrue(fields.none { it in setOf("gold", "danger", "warn", "ok", "teal") })
    }
}
