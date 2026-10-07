package com.pisophone.kiosk.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KioskStatusToastUnitTest {
    @Before
    fun setUp() = KioskStatusToast.reset()

    @Test
    fun theSameLineIsNotRepeatedUntilItsTimeHasPassed() {
        assertTrue(KioskStatusToast.shouldShow("Wi-Fi: connected", 60_000L, 0L))
        assertFalse(KioskStatusToast.shouldShow("Wi-Fi: connected", 60_000L, 59_999L))
        assertTrue(KioskStatusToast.shouldShow("Wi-Fi: connected", 60_000L, 60_000L))
    }

    @Test
    fun aDifferentLineIsShownAtOnce() {
        assertTrue(KioskStatusToast.shouldShow("Scan: started", 60_000L, 0L))
        assertTrue(KioskStatusToast.shouldShow("Scan: box found", 60_000L, 1L))
    }
}
