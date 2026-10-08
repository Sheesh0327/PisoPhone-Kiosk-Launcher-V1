package com.pisophone.kiosk.network

import com.pisophone.kiosk.network.Esp32Responses.Refusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Esp32ResponsesTest {
    @Test
    fun onlyA423MeansTheSlotIsNotActivated() {
        assertEquals(Refusal.SLOT_LOCKED, Esp32Responses.classify(423, "{\"error\":\"SLOT_EXPIRED\"}"))
        assertEquals(Refusal.SLOT_LOCKED, Esp32Responses.classify(423, "{\"error\":\"SLOT_NOT_PAIRED\"}"))
    }

    @Test
    fun a403IsNeverReadAsALockdown() {
        assertEquals(Refusal.AUTH_REFUSED, Esp32Responses.classify(403, "{\"error\":\"AUTH_FAILED\"}"))
        assertEquals(Refusal.AUTH_REFUSED, Esp32Responses.classify(403, "{\"error\":\"SIGNATURE_REQUIRED\"}"))
        assertEquals(Refusal.AUTH_REFUSED, Esp32Responses.classify(403, ""))
        assertEquals(Refusal.SETUP_REQUIRED, Esp32Responses.classify(403, "{\"error\":\"SETUP_REQUIRED\"}"))
    }

    @Test
    fun aStaleTimestampIsAClockProblemTheBoxCanHeal() {
        assertEquals(Refusal.CLOCK_SKEW, Esp32Responses.classify(403, "{\"error\":\"AUTH_FAILED\",\"reason\":\"STALE_TIMESTAMP\",\"server_time_ms\":1790000000000}"))
        assertEquals("a wrong key is still a refusal", Refusal.AUTH_REFUSED, Esp32Responses.classify(403, "{\"error\":\"AUTH_FAILED\",\"reason\":\"BAD_SIGNATURE\"}"))
    }

    @Test
    fun busyAndOtherStatuses() {
        assertEquals(Refusal.BUSY, Esp32Responses.classify(409))
        assertEquals(Refusal.OTHER, Esp32Responses.classify(500))
        assertEquals(Refusal.OTHER, Esp32Responses.classify(0))
    }

    @Test
    fun oneLostHeartbeatDoesNotMakeTheBoxOffline() {
        assertFalse(Esp32Responses.shouldMarkOffline(1, 4_000L))
        assertFalse(Esp32Responses.shouldMarkOffline(2, 12_000L))
        assertTrue(Esp32Responses.shouldMarkOffline(3, 12_000L))
        assertTrue(Esp32Responses.shouldMarkOffline(1, 21_000L))
    }

    @Test
    fun theAddressIsKeptThroughAShortOutage() {
        assertFalse(Esp32Responses.shouldForgetAddress(3, 20_000L))
        assertFalse(Esp32Responses.shouldForgetAddress(5, 40_000L))
        assertTrue(Esp32Responses.shouldForgetAddress(6, 24_000L))
        assertTrue(Esp32Responses.shouldForgetAddress(2, 46_000L))
    }

    @Test
    fun onlyARealBusyAnswerIsLabelledBusy() {
        val labels = Esp32Responses.ArmFailure.entries.map { it.label }
        assertEquals("every failure has its own label", labels.size, labels.toSet().size)
        assertEquals(listOf(Esp32Responses.ArmFailure.BUSY), Esp32Responses.ArmFailure.entries.filter { "BUSY" in it.label })
        // the label sits on a small button: keep it short, and give every failure advice to read
        assertTrue(labels.all { it.length <= 20 })
        assertTrue(Esp32Responses.ArmFailure.entries.all { it.advice.isNotBlank() })
    }
}
