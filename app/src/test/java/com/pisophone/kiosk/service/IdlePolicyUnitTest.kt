package com.pisophone.kiosk.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdlePolicyUnitTest {
    private val minute = 60_000L

    @Test
    fun `the social apps are wiped five minutes after the phone locked, once`() {
        assertFalse(IdlePolicy.shouldClearGuest(true, 4 * minute, 5, signedIn = false, alreadyDone = false))
        assertTrue(IdlePolicy.shouldClearGuest(true, 5 * minute, 5, signedIn = false, alreadyDone = false))
        assertFalse(IdlePolicy.shouldClearGuest(true, 9 * minute, 5, signedIn = false, alreadyDone = true))
    }

    @Test
    fun `nothing is wiped when switched off or while a card player is signed in`() {
        assertFalse(IdlePolicy.shouldClearGuest(false, 10 * minute, 5, signedIn = false, alreadyDone = false))
        assertFalse(IdlePolicy.shouldClearGuest(true, 10 * minute, 5, signedIn = true, alreadyDone = false))
    }

    @Test
    fun `the screen goes off after the idle minutes with the screen on`() {
        assertFalse(IdlePolicy.shouldScreenOff(true, true, true, 4 * minute, 5))
        assertTrue(IdlePolicy.shouldScreenOff(true, true, true, 5 * minute, 5))
        assertFalse(IdlePolicy.shouldScreenOff(true, true, false, 9 * minute, 5))
        assertFalse(IdlePolicy.shouldScreenOff(false, true, true, 9 * minute, 5))
        assertFalse(IdlePolicy.shouldScreenOff(true, false, true, 9 * minute, 5))
    }
}
