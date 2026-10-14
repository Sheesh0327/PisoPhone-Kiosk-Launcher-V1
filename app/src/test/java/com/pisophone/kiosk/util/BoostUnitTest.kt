package com.pisophone.kiosk.util

import com.pisophone.kiosk.network.KioskWifi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoostUnitTest {
    @Test
    fun `a boost keeps the app in front`() {
        assertEquals(listOf("a", "c"), AppCloser.packagesToBoost(listOf("a", "b", "c"), "b"))
        assertEquals(listOf("a", "b"), AppCloser.packagesToBoost(listOf("a", "b"), null))
    }

    @Test
    fun `the app in front is the last one that came forward and has not left`() {
        val events = listOf("game" to 1, "chat" to 1, "chat" to 2, "game" to 1)
        assertEquals("game", AppCloser.foregroundFromEvents(events, emptySet()))
        assertNull(AppCloser.foregroundFromEvents(listOf("game" to 1, "game" to 2), emptySet()))
        assertEquals("game", AppCloser.foregroundFromEvents(listOf("game" to 1, "com.pisophone.kiosk" to 1), setOf("com.pisophone.kiosk")))
    }

    @Test
    fun `the wifi refresh waits for its cooldown and for the coin window`() {
        assertTrue(KioskWifi.canToggle(1_000, 0, coinWindowOpen = false))
        assertFalse(KioskWifi.canToggle(10_000, 1_000, coinWindowOpen = false))
        assertTrue(KioskWifi.canToggle(16_001, 1_000, coinWindowOpen = false))
        assertFalse(KioskWifi.canToggle(99_000, 0, coinWindowOpen = true))
    }
}
