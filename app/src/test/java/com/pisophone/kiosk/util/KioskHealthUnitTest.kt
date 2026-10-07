package com.pisophone.kiosk.util

import com.pisophone.kiosk.util.KioskHealth.Level
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KioskHealthUnitTest {
    private var now = 1_000_000L
    private val goodWifi = KioskHealth.Wifi("10.0.0.23", -55)

    @Before
    fun setUp() {
        KioskHealth.clock = { now }
        KioskHealth.reset()
    }

    @After
    fun tearDown() {
        KioskHealth.clock = { System.currentTimeMillis() }
        KioskHealth.reset()
    }

    private fun summary(wifi: KioskHealth.Wifi = goodWifi) = KioskHealth.summarize(now, KioskHealth.snapshot(), wifi)

    @Test
    fun boxNotFoundShortlyAfterStartIsAWarningThenAProblem() {
        now += 30_000
        assertEquals(Level.WARN, summary().level)
        assertTrue(summary().lines.any { it.startsWith("Box: not found yet (searching for 30s)") })

        now += KioskHealth.BOX_OFFLINE_RED_MS
        assertEquals(Level.PROBLEM, summary().level)
    }

    @Test
    fun healthyKioskIsOk() {
        KioskHealth.boxFound("10.0.0.2")
        now += 5 * 60_000
        KioskHealth.coin("APPLIED")
        val s = summary()
        assertEquals(Level.OK, s.level)
        assertEquals("Box: online at 10.0.0.2 for 5m", s.lines[1])
        assertEquals("Last coin: APPLIED, 0s ago", s.lines[2])
    }

    @Test
    fun boxDropTurnsRedOnlyAfterTheGracePeriodAndRemembersWhereItWas() {
        KioskHealth.boxFound("10.0.0.2")
        now += 60_000
        KioskHealth.boxLink(false)
        now += 30_000
        assertEquals(Level.WARN, summary().level)
        now += KioskHealth.BOX_OFFLINE_RED_MS
        val s = summary()
        assertEquals(Level.PROBLEM, s.level)
        assertEquals("Box: OFFLINE for 2m, last found at 10.0.0.2 3m ago", s.lines[1])
    }

    @Test
    fun repeatedOnlineReportsDoNotResetTheSinceTime() {
        KioskHealth.boxLink(true)
        now += 10 * 60_000
        KioskHealth.boxLink(true)
        assertTrue(summary().lines[1].endsWith("for 10m"))
    }

    @Test
    fun noWifiAddressIsAProblem() {
        KioskHealth.boxFound("10.0.0.2")
        val s = summary(KioskHealth.Wifi("", null))
        assertEquals(Level.PROBLEM, s.level)
        assertEquals("Wi-Fi: no address (not on the kiosk network)", s.lines[0])
    }

    @Test
    fun weakSignalIsAWarning() {
        KioskHealth.boxFound("10.0.0.2")
        val s = summary(KioskHealth.Wifi("10.0.0.23", -82))
        assertEquals(Level.WARN, s.level)
        assertEquals("Wi-Fi: 10.0.0.23, signal -82 dBm (weak)", s.lines[0])
    }

    @Test
    fun aCoinThatFailedToCreditIsAWarning() {
        KioskHealth.boxFound("10.0.0.2")
        KioskHealth.coin("FAILED")
        assertEquals(Level.WARN, summary().level)
        KioskHealth.coin("ALREADY_APPLIED")
        assertEquals(Level.OK, summary().level)
    }

    @Test
    fun agoIsShortAndReadable() {
        assertEquals("45s", KioskHealth.ago(45_000))
        assertEquals("12m", KioskHealth.ago(12 * 60_000L))
        assertEquals("3h 5m", KioskHealth.ago((3 * 60 + 5) * 60_000L))
        assertEquals("2d 4h", KioskHealth.ago((2 * 24 + 4) * 3_600_000L))
        assertEquals("0s", KioskHealth.ago(-5))
    }
}
