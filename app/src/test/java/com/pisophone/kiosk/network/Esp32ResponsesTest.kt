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
}
