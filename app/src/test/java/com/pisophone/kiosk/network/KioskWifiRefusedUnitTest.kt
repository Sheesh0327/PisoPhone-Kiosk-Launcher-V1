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

    @Test
    fun aConnectedPhoneWithAnUnreadableNameIsOnTheKioskNetworkWhenItCannotBeAnythingElse() {
        // held to the kiosk network by the allowlist
        assertEquals(true, KioskWifi.connectedWithoutName(true, true, lockedByAllowlist = true, savedListVisible = true, idMatches = false))
        // the app cannot see the saved networks at all (saved by the system)
        assertEquals(true, KioskWifi.connectedWithoutName(true, true, lockedByAllowlist = false, savedListVisible = false, idMatches = false))
        // the saved kiosk network is the one in use
        assertEquals(true, KioskWifi.connectedWithoutName(true, true, lockedByAllowlist = false, savedListVisible = true, idMatches = true))
    }

    @Test
    fun aPhoneThatIsNotConnectedOrHasNoAddressIsNotOnIt() {
        assertEquals(false, KioskWifi.connectedWithoutName(false, true, lockedByAllowlist = true, savedListVisible = false, idMatches = true))
        assertEquals(false, KioskWifi.connectedWithoutName(true, false, lockedByAllowlist = true, savedListVisible = false, idMatches = true))
        // another saved network is in use and the app can see the list: not the kiosk network
        assertEquals(false, KioskWifi.connectedWithoutName(true, true, lockedByAllowlist = false, savedListVisible = true, idMatches = false))
    }
}
