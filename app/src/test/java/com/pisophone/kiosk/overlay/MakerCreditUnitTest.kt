package com.pisophone.kiosk.overlay

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pisophone.kiosk.R
import com.pisophone.kiosk.overlay.ui.AmbientBudget
import com.pisophone.kiosk.overlay.ui.AnimationDensity
import com.pisophone.kiosk.overlay.ui.EffectsLevel
import com.pisophone.kiosk.overlay.ui.MakerCredit
import com.pisophone.kiosk.server.KioskHttpServer
import com.pisophone.kiosk.ui.theme.BrandPalette
import com.pisophone.kiosk.util.Branding
import com.pisophone.kiosk.util.ShopBranding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.pow

/**
 * Criterion 12 of feature 012: nothing the box can send removes, hides or stills the "by TechNet Solutions" line.
 */
@RunWith(RobolectricTestRunner::class)
class MakerCreditUnitTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.createDeviceProtectedStorageContext().getSharedPreferences("shop_branding", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun luminance(c: androidx.compose.ui.graphics.Color): Double {
        fun lin(v: Float): Double = v.toDouble().let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    private fun contrast(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Every value the box could send for each setting, junk and blanks included. */
    private val palettes = BrandPalette.all.map { it.id } + listOf("nonsense", "", " ", "TECHNET", "../etc", "<b>")
    private val densities = AnimationDensity.values().map { it.word } + listOf("turbo", "", "OFF", "0")
    private val names = listOf(null, "", "   ", "Tindahan ni Mang Juan", "X".repeat(500), "\n\u0000\t", "hide_credit=1", "‮reversed")
    private val titles = listOf(null, "", "  ", "Piso Net", "T".repeat(300), "credit off")

    @Test
    fun `the line is the same words for every combination the box can send`() {
        assertEquals("by TechNet Solutions", context.getString(R.string.maker_credit))
        var combos = 0
        for (pal in palettes) for (den in densities) for (name in names) for (title in titles) {
            ShopBranding.applyFromBox(context, pal, name, title, den)
            val b = ShopBranding.read(context)
            val palette = BrandPalette.of(b.palette)
            // the credit takes no branding value: its colour is the palette's credit colour and its sheen follows the ladder only
            for (bg in listOf(palette.top, palette.bottom)) {
                assertTrue("credit unreadable for $pal/$den", contrast(palette.credit, bg) >= 4.5)
            }
            for (effects in EffectsLevel.values()) {
                assertEquals(effects.ambient, MakerCredit.sheenRuns(effects))
            }
            combos++
        }
        assertTrue("drove $combos combinations", combos > 3000)
    }

    @Test
    fun `nothing set at all still has the credit with the technet palette`() {
        val b = ShopBranding.read(context)
        assertFalse(b.isSet)
        assertEquals("technet", BrandPalette.of(b.palette).id)
        assertTrue(contrast(BrandPalette.of(b.palette).credit, BrandPalette.of(b.palette).bottom) >= 4.5)
    }

    @Test
    fun `the sheen follows the ladder and no density`() {
        // it runs wherever ambient motion may run and rests with everything else on a hot or nearly flat phone
        assertTrue(MakerCredit.sheenRuns(EffectsLevel.Full))
        for (e in listOf(EffectsLevel.Events, EffectsLevel.Still, EffectsLevel.Flat)) assertFalse(MakerCredit.sheenRuns(e))
        // `off` stills the picture but not the credit: a density has no way in (sheenRuns takes only the ladder's level)
        for (d in AnimationDensity.values()) {
            assertFalse("the art stands still at $d", AmbientBudget.of(d, EffectsLevel.Full).anythingMoves && d == AnimationDensity.Off)
        }
        assertTrue(MakerCredit.sheenRuns(EffectsLevel.Full))
    }

    @Test
    fun `the composable has no switch to hide, disable or still it`() {
        val method = Class.forName("com.pisophone.kiosk.overlay.ui.MakerCreditKt").declaredMethods.single { it.name == "MakerCredit" }
        assertFalse("no Boolean parameter at all", method.parameterTypes.any { it == java.lang.Boolean.TYPE })
        assertFalse("no String parameter: the words are not configurable", method.parameterTypes.any { it == String::class.java })
    }

    @Test
    fun `no key, param or stored setting addresses the credit`() {
        assertTrue(KioskHttpServer.BRANDING_PARAMS.none { it.contains("credit") || it.contains("maker") })
        val members = (ShopBranding::class.java.declaredMethods.map { it.name } + ShopBranding::class.java.declaredFields.map { it.name } +
            Branding::class.java.declaredFields.map { it.name }).map { it.lowercase() }
        assertTrue("ShopBranding/Branding mention the credit: $members", members.none { "credit" in it || "maker" in it })
    }
}
