package com.pisophone.kiosk.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins every transition of the paid-session state machine. Several tests re-state the inline logic
 * that existed before [SessionRules] (copied literally) and compare it for every state, so the
 * refactor is proven behaviour-preserving; the rest name the money bugs fixed in Phase 1.
 */
class SessionRulesTest {
    private val LOCKED = 0
    private val ARMED_LOCKED = 1
    private val UNLOCKED = 2
    private val UNLOCKED_ARMED = 3
    private val allStates = listOf(0, 1, 2, 3, 4, -1)

    @Test
    fun stateCodesAreStable() {
        assertEquals(0, SessionState.LOCKED.code)
        assertEquals(1, SessionState.ARMED_LOCKED.code)
        assertEquals(2, SessionState.UNLOCKED.code)
        assertEquals(3, SessionState.UNLOCKED_ARMED.code)
        assertEquals(SessionState.UNLOCKED_ARMED, SessionState.fromCode(3))
        assertNull(SessionState.fromCode(4))
    }

    @Test
    fun predicatesMatchTheOldInlineChecks() {
        for (s in allStates) {
            assertEquals("armed $s", s == 1 || s == 3, SessionRules.isArmed(s))
            assertEquals("unlocked $s", s == 2 || s == 3, SessionRules.isUnlocked(s))
            assertEquals("lock screen $s", s == 0 || s == 1, SessionRules.isLockScreenShown(s))
        }
    }

    @Test
    fun coinCreditOnlyMovesAFullyLockedPhone() {
        assertEquals(ARMED_LOCKED, SessionRules.afterCoinCredit(LOCKED))
        for (s in listOf(1, 2, 3, 4)) assertNull(SessionRules.afterCoinCredit(s))
    }

    @Test
    fun nonCoinCreditUnlocksButKeepsAnArmedSlotArmed() {
        assertEquals(UNLOCKED, SessionRules.afterNonCoinCredit(LOCKED))
        assertEquals(UNLOCKED_ARMED, SessionRules.afterNonCoinCredit(ARMED_LOCKED))
        assertNull(SessionRules.afterNonCoinCredit(UNLOCKED))
        assertNull(SessionRules.afterNonCoinCredit(UNLOCKED_ARMED))
    }

    @Test
    fun expiryKeepsAnArmedSlotArmedAndLocksOtherwise() {
        assertEquals(LOCKED, SessionRules.afterExpiry(LOCKED))
        assertEquals(ARMED_LOCKED, SessionRules.afterExpiry(ARMED_LOCKED))
        assertEquals(LOCKED, SessionRules.afterExpiry(UNLOCKED))
        assertEquals(ARMED_LOCKED, SessionRules.afterExpiry(UNLOCKED_ARMED))
        for (s in allStates) assertEquals(if (s == 1 || s == 3) 1 else 0, SessionRules.afterExpiry(s))
    }

    @Test
    fun deductLocksOnlyAnUnlockedSessionThatReachedZero() {
        for (s in allStates) {
            val old = if (s == 2 || s == 3) (if (s == 1 || s == 3) 1 else 0) else null
            assertEquals("deduct to zero in $s", old, SessionRules.afterDeduct(s, 0))
            assertNull("time left in $s", SessionRules.afterDeduct(s, 60))
        }
        assertEquals(ARMED_LOCKED, SessionRules.afterDeduct(UNLOCKED_ARMED, 0))
        assertEquals(LOCKED, SessionRules.afterDeduct(UNLOCKED, -5))
        assertNull(SessionRules.afterDeduct(LOCKED, 0))
    }

    @Test
    fun adminBypassUnlocksAndKeepsArmedSlotArmed() {
        for (s in allStates) {
            val old = when (s) { 1, 3 -> 3; else -> 2 }
            assertEquals("bypass $s", old, SessionRules.afterAdminBypass(s))
        }
    }

    @Test
    fun armSuccessAddsArmingToTheCurrentState() {
        assertEquals(UNLOCKED_ARMED, SessionRules.afterArmSuccess(UNLOCKED))
        assertEquals(ARMED_LOCKED, SessionRules.afterArmSuccess(LOCKED))
        assertEquals(ARMED_LOCKED, SessionRules.afterArmSuccess(ARMED_LOCKED))
        assertEquals(UNLOCKED_ARMED, SessionRules.afterArmSuccess(UNLOCKED_ARMED))
        assertEquals(4, SessionRules.afterArmSuccess(4))
    }

    @Test
    fun aFailedArmNeverLocksAPaidSession() {
        for (paid in listOf(true, false)) {
            for (s in allStates) {
                val old = when (s) { 2, 3 -> 2; 1 -> if (paid) 2 else 0; else -> s }
                assertEquals("arm failure $s paid=$paid", old, SessionRules.afterArmFailure(s, paid))
            }
        }
        assertEquals(UNLOCKED, SessionRules.afterArmFailure(UNLOCKED_ARMED, hasPaidTime = true))
        assertEquals(LOCKED, SessionRules.afterArmFailure(ARMED_LOCKED, hasPaidTime = false))
    }

    @Test
    fun armWindowTimeoutWithoutCoinsReturnsToRunningOrLocked() {
        for (paid in listOf(true, false)) {
            for (s in listOf(1, 3)) {
                val old = if (s == 3 || paid) 2 else 0
                assertEquals("timeout $s paid=$paid", old, SessionRules.afterArmTimeoutNoCoin(s, paid))
            }
        }
    }

    @Test
    fun finishingPaymentUnlocksWhenCoinsWereInsertedOrTimeWasRunning() {
        for (coins in listOf(0, 5)) {
            for (s in allStates) {
                val old = if (coins > 0) {
                    2
                } else if (s == 3) {
                    2
                } else {
                    0
                }
                assertEquals("finish $s coins=$coins", old, SessionRules.afterFinishPayment(s, coins))
            }
        }
    }

    @Test
    fun hardLockAlwaysGoesToLocked() {
        assertEquals(LOCKED, SessionRules.hardLocked())
    }

    // Phase 1 regressions, expressed as state-machine facts.

    @Test
    fun coinDuringAnUnlockedSessionNeverRelocksThePhone() {
        assertNull(SessionRules.afterCoinCredit(UNLOCKED))
        assertNull(SessionRules.afterCoinCredit(UNLOCKED_ARMED))
    }

    @Test
    fun adminTimeAddedWhileArmedDoesNotDropTheArmedSlot() {
        assertTrue(SessionRules.isArmed(SessionRules.afterNonCoinCredit(ARMED_LOCKED)!!))
    }

    @Test
    fun expiryWhileArmedDoesNotLoseAnInsertedCoin() {
        assertTrue(SessionRules.isArmed(SessionRules.afterExpiry(UNLOCKED_ARMED)))
        assertFalse(SessionRules.isUnlocked(SessionRules.afterExpiry(UNLOCKED_ARMED)))
    }

    @Test
    fun pillShowsOnlyWhenUnlockedOrBannerAndNotHiddenForAnAdminApp() {
        for (state in allStates) {
            for (banner in listOf(false, true)) {
                assertEquals(SessionRules.isUnlocked(state) || banner, SessionRules.isPillShown(state, banner, hiddenForAdminApp = false))
                assertFalse(SessionRules.isPillShown(state, banner, hiddenForAdminApp = true))
            }
        }
    }
}
