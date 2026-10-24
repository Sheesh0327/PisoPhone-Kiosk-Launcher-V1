package com.pisophone.kiosk.network

import org.junit.Assert.assertEquals
import org.junit.Test

class KioskWifiRefusedUnitTest {
    @Test
    fun aNetworkSavedBeforeThatCanBeSwitchedOnIsJoinedNotRefused() {
        assertEquals(KioskWifi.Join.JOINING, KioskWifi.afterRefused(existingEnabled = true, suggested = false))
    }

    @Test
    fun anAcceptedSuggestionIsTheWayInWhenTheNetworkCannotBeAdded() {
        assertEquals(KioskWifi.Join.JOINING, KioskWifi.afterRefused(existingEnabled = false, suggested = true))
    }

    @Test
    fun onlyWithNoWayInIsItARealRefusal() {
        assertEquals(KioskWifi.Join.REFUSED, KioskWifi.afterRefused(existingEnabled = false, suggested = false))
    }
}
