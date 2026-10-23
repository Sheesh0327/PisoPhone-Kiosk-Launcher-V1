package com.pisophone.kiosk.overlay

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pisophone.kiosk.overlay.ui.BrandingSummary
import com.pisophone.kiosk.util.Branding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The read-only line in the owner vault (feature 013): what the box told this phone, and when. */
@RunWith(RobolectricTestRunner::class)
class BrandingSummaryUnitTest {
    private lateinit var context: Context
    private val now = 1_800_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun values(b: Branding, at: Long = now) = BrandingSummary.rows(context, b, at).associate { it.first to it.second }

    @Test
    fun `a phone the box never spoke to shows the defaults and says it never heard`() {
        val rows = BrandingSummary.rows(context, Branding(), now)
        assertEquals(5, rows.size)
        val v = rows.map { it.second }
        assertEquals("TechNet (default)", v[0])
        assertEquals("not set", v[1])
        assertEquals("PisoPhone (default)", v[2])
        assertEquals("Normal (default)", v[3])
        assertEquals("never", v[4])
    }

    @Test
    fun `what the box sent is shown as it is stored`() {
        val b = Branding(palette = "rosas", shopName = "Mang Juan's Tindahan", title = "Piso Net", density = "low", receivedAtMs = now - 5 * 60_000L)
        val v = BrandingSummary.rows(context, b, now).map { it.second }
        assertEquals("Rosas", v[0])
        assertEquals("Mang Juan's Tindahan", v[1])
        assertEquals("Piso Net", v[2])
        assertEquals("Low", v[3])
        assertEquals("5 min ago", v[4])
    }

    @Test
    fun `how long ago the box last spoke reads in minutes, hours and days`() {
        fun heard(agoMs: Long) = BrandingSummary.rows(context, Branding(palette = "dagat", receivedAtMs = now - agoMs), now).last().second
        assertEquals("just now", heard(20_000L))
        assertEquals("1 min ago", heard(60_000L))
        assertEquals("59 min ago", heard(59 * 60_000L))
        assertEquals("1 h ago", heard(60 * 60_000L))
        assertEquals("23 h ago", heard(23 * 3_600_000L))
        assertEquals("1 d ago", heard(24 * 3_600_000L))
        assertEquals("30 d ago", heard(30 * 24 * 3_600_000L))
    }

    @Test
    fun `a clock that went backwards still reads sensibly`() {
        val b = Branding(palette = "dagat", receivedAtMs = now + 3_600_000L)
        assertEquals("just now", BrandingSummary.rows(context, b, now).last().second)
    }

    @Test
    fun `nothing in the summary can be edited or addresses the credit`() {
        val labels = BrandingSummary.rows(context, Branding(), now).map { it.first.lowercase() }
        assertTrue(labels.none { "credit" in it || "maker" in it })
        assertFalse(BrandingSummary::class.java.declaredMethods.any { it.name.lowercase().let { n -> "set" == n.take(3) || "save" in n || "apply" in n } })
    }
}
