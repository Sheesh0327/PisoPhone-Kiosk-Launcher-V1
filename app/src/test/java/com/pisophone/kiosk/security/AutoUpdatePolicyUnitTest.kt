package com.pisophone.kiosk.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoUpdatePolicyUnitTest {
    @Test
    fun aPhoneIsIdleOnlyOnTheLockScreenWithNoTimeNobodySignedInAndNoMatch() {
        assertTrue(AutoUpdatePolicy.isIdle(appState = 0, secondsRemaining = 0, signedInAccount = "", arenaMode = false))
        // a customer paying (1), playing (2, 3) or any time left
        assertFalse(AutoUpdatePolicy.isIdle(1, 0, "", false))
        assertFalse(AutoUpdatePolicy.isIdle(2, 600, "", false))
        assertFalse(AutoUpdatePolicy.isIdle(3, 0, "", false))
        assertFalse(AutoUpdatePolicy.isIdle(0, 30, "", false))
        // a player's card is signed in, or an arena match is on
        assertFalse(AutoUpdatePolicy.isIdle(0, 0, "12345", false))
        assertFalse(AutoUpdatePolicy.isIdle(0, 0, "", true))
    }

    @Test
    fun aBuildThatKeepsFailingIsGivenUpOnUntilANewerOneIsPublished() {
        assertTrue(AutoUpdatePolicy.mayAttempt(0))
        assertTrue(AutoUpdatePolicy.mayAttempt(AutoUpdatePolicy.MAX_ATTEMPTS_PER_BUILD - 1))
        assertFalse(AutoUpdatePolicy.mayAttempt(AutoUpdatePolicy.MAX_ATTEMPTS_PER_BUILD))
    }

    @Test
    fun theCheckIsHourlyAndTheFirstOneWaitsForTheServiceToSettle() {
        assertEquals(3_600_000L, AutoUpdatePolicy.CHECK_INTERVAL_MS)
        assertTrue(AutoUpdatePolicy.FIRST_CHECK_DELAY_MS in 60_000L..AutoUpdatePolicy.CHECK_INTERVAL_MS)
    }
}
