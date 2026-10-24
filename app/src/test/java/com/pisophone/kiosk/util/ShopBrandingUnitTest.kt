package com.pisophone.kiosk.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShopBrandingUnitTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // start every test as a phone the box never spoke to
        context.createDeviceProtectedStorageContext().getSharedPreferences("shop_branding", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun push(palette: String? = null, name: String? = null, title: String? = null, density: String? = null, now: Long = 1_000L) =
        ShopBranding.applyFromBox(context, palette, name, title, density, now)

    @Test
    fun nothingEverWrittenGivesTheUnbrandedDefaults() {
        val b = ShopBranding.read(context)
        assertNull(b.palette)
        assertNull(b.shopName)
        assertNull(b.title)
        assertNull(b.density)
        assertEquals(0L, b.receivedAtMs)
        assertFalse(b.isSet)
    }

    @Test
    fun theFourSettingsAreStoredAndComeBack() {
        assertTrue(push("rosas", "Aling Nena Piso Net", "Nena Phones", "low", now = 5_000L))
        val b = ShopBranding.read(context)
        assertEquals("rosas", b.palette)
        assertEquals("Aling Nena Piso Net", b.shopName)
        assertEquals("Nena Phones", b.title)
        assertEquals("low", b.density)
        assertEquals(5_000L, b.receivedAtMs)
        assertTrue(b.isSet)
    }

    @Test
    fun theShopNameIsCutAt32AndTheTitleAt20() {
        push(name = "N".repeat(40), title = "T".repeat(30))
        val b = ShopBranding.read(context)
        assertEquals(32, b.shopName!!.length)
        assertEquals(20, b.title!!.length)
        assertEquals(32, ShopBranding.MAX_NAME)
        assertEquals(20, ShopBranding.MAX_TITLE)
    }

    @Test
    fun theCapCountsCharactersNotBytesAndNeverSplitsOne() {
        // a character outside the basic plane is two UTF-16 units: it must be kept whole or dropped whole
        val cut = ShopBranding.cleanText("a".repeat(19) + "😀" + "z", 20)!!
        assertEquals(20, cut.codePointCount(0, cut.length))
        assertTrue(cut.endsWith("😀"))
    }

    @Test
    fun controlCharactersAndNewlinesAreStrippedAndEdgesTrimmed() {
        assertEquals("Juan Shop", ShopBranding.cleanText("  Juan\n Shop\u0000\t\r  ", 32))
        assertEquals("AB", ShopBranding.cleanText("A\u0007B", 32))
        assertEquals("ab", ShopBranding.cleanText("‮ab​", 32)) // direction override and zero-width space
        assertEquals("x", ShopBranding.cleanText("\n\n x  ", 32))
    }

    @Test
    fun aBlankNameOrTitleIsUnsetAndClearsWhatWasThere() {
        push(name = "Old Name", title = "Old Title")
        push(name = "   \n ", title = "")
        val b = ShopBranding.read(context)
        assertNull(b.shopName)
        assertNull(b.title)
    }

    @Test
    fun anUnknownPaletteIdOrDensityWordNeverReachesStorage() {
        push(palette = "rosas", density = "high")
        push(palette = "neon-pink", density = "turbo")
        val b = ShopBranding.read(context)
        assertEquals("the stored palette stays", "rosas", b.palette)
        assertEquals("the stored density stays", "high", b.density)
        // and with nothing stored before, junk leaves it unset
        context.createDeviceProtectedStorageContext().getSharedPreferences("shop_branding", Context.MODE_PRIVATE).edit().clear().commit()
        push(palette = "<script>", density = "9999")
        assertNull(ShopBranding.read(context).palette)
        assertNull(ShopBranding.read(context).density)
    }

    @Test
    fun aBlankPaletteOrDensityGoesBackToTheDefault() {
        push(palette = "berde", density = "off")
        push(palette = "", density = " ")
        val b = ShopBranding.read(context)
        assertNull(b.palette)
        assertNull(b.density)
    }

    @Test
    fun paletteAndDensityAreMatchedWithoutCaseOrSpaces() {
        push(palette = " Rosas ", density = "LOW")
        val b = ShopBranding.read(context)
        assertEquals("rosas", b.palette)
        assertEquals("low", b.density)
    }

    @Test
    fun aPushWithNoBrandingParamsChangesNothingAndDoesNotMoveTheClock() {
        push("ginto", "Keep Me", "Keep", "normal", now = 1_000L)
        assertFalse(push(now = 9_000L))
        val b = ShopBranding.read(context)
        assertEquals("ginto", b.palette)
        assertEquals("Keep Me", b.shopName)
        assertEquals(1_000L, b.receivedAtMs)
    }

    @Test
    fun aPushWithOnlyTheNameKeepsTheOtherThree() {
        push("ginto", "Old", "Title", "high")
        push(name = "New")
        val b = ShopBranding.read(context)
        assertEquals("New", b.shopName)
        assertEquals("ginto", b.palette)
        assertEquals("Title", b.title)
        assertEquals("high", b.density)
    }

    @Test
    fun theDensityWordsAreTheFourTheBoxOffers() {
        assertEquals(listOf("off", "low", "normal", "high"), ShopBranding.DENSITY_WORDS)
    }

    @Test
    fun storedJunkIsCleanedAgainOnReadSoAHandEditedFileCannotSneakThrough() {
        val p = context.createDeviceProtectedStorageContext().getSharedPreferences("shop_branding", Context.MODE_PRIVATE)
        p.edit().putString("shop_name", "X".repeat(80) + "\n").putString("palette", "bogus").putString("density", "bogus").commit()
        val b = ShopBranding.read(context)
        assertEquals(32, b.shopName!!.length)
        assertNull(b.palette)
        assertNull(b.density)
    }

    @Test
    fun aLockScreenAlreadyUpFollowsWhatTheBoxPushes() {
        val flow = ShopBranding.observe(context)
        push(palette = "berde", name = "Tindahan", now = 5L)
        assertEquals("berde", flow.value.palette)
        assertEquals("Tindahan", flow.value.shopName)
        push(name = "")
        assertNull("a blank name clears it on the screen too", flow.value.shopName)
        assertEquals("berde", flow.value.palette)
    }

    // ---- feature 024: the label of the phone's profile ----

    @Test
    fun theTitleIsTheLabelThenTheShopTitleThenPisoPhone() {
        assertEquals("PisoPhone", Branding().displayTitle)
        assertEquals("Piso Net", Branding(title = "Piso Net").displayTitle)
        assertEquals("PisoTab", Branding(title = "Piso Net", deviceLabel = "PisoTab").displayTitle)
        assertEquals("PisoTab", Branding(deviceLabel = "PisoTab").displayTitle)
    }

    @Test
    fun aLabelIsStoredAndComesBackAndABlankOneClearsIt() {
        ShopBranding.applyFromBox(context, null, null, null, null, 1_000L, deviceLabel = "PisoPhone Pro")
        assertEquals("PisoPhone Pro", ShopBranding.read(context).deviceLabel)
        ShopBranding.applyFromBox(context, null, null, null, null, 2_000L, deviceLabel = "")
        assertNull(ShopBranding.read(context).deviceLabel)
    }

    @Test
    fun aLabelIsCutAt20CharactersAndAPushWithNoLabelLeavesItAlone() {
        ShopBranding.applyFromBox(context, null, null, null, null, 1_000L, deviceLabel = "X".repeat(25))
        assertEquals("X".repeat(ShopBranding.MAX_LABEL), ShopBranding.read(context).deviceLabel)
        push(palette = "dagat")
        assertEquals("X".repeat(ShopBranding.MAX_LABEL), ShopBranding.read(context).deviceLabel)
    }

    @Test
    fun aPushThatCarriesOnlyALabelCountsAsHeardFromTheBox() {
        val stored = ShopBranding.applyFromBox(context, null, null, null, null, 5_000L, deviceLabel = "PisoTab")
        assertTrue(stored)
        assertEquals(5_000L, ShopBranding.read(context).receivedAtMs)
        assertFalse("a label alone is not shop branding", ShopBranding.read(context).isSet)
    }
}
