package com.pisophone.kiosk.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinThrottleUnitTest {
    private val t = PinThrottle()

    @Test fun firstFourWrongTriesAreFree() {
        var s = PinThrottle.State()
        repeat(4) { s = t.onFailure(s, 1000L); assertEquals(0L, t.remainingLockMs(s, 1000L)) }
    }

    @Test fun fifthWrongTryLocksForAMinute() {
        var s = PinThrottle.State()
        repeat(5) { s = t.onFailure(s, 1000L) }
        assertEquals(60_000L, t.remainingLockMs(s, 1000L))
        assertEquals(30_000L, t.remainingLockMs(s, 31_000L))
        assertEquals(0L, t.remainingLockMs(s, 61_000L))
    }

    @Test fun lockDoublesUpToTheCap() {
        var s = PinThrottle.State()
        val locks = mutableListOf<Long>()
        repeat(12) { s = t.onFailure(s, 0L); locks += t.remainingLockMs(s, 0L) }
        assertEquals(listOf(0L, 0L, 0L, 0L, 60_000L, 120_000L, 240_000L, 480_000L, 900_000L, 900_000L, 900_000L, 900_000L), locks)
    }

    @Test fun successResets() {
        var s = PinThrottle.State()
        repeat(7) { s = t.onFailure(s, 0L) }
        s = t.onSuccess()
        assertEquals(PinThrottle.State(), s)
        assertEquals(0L, t.remainingLockMs(s, 0L))
    }

    @Test fun aClockSetBackNeverExtendsPastTheCap() {
        var s = PinThrottle.State()
        repeat(5) { s = t.onFailure(s, 1_000_000L) }
        assertTrue(t.remainingLockMs(s, 0L) <= 15 * 60_000L)
    }

    @Test fun hugeFailureCountsDoNotOverflow() {
        var s = PinThrottle.State(failures = 1_000_000)
        s = t.onFailure(s, 5L)
        assertEquals(900_000L, t.remainingLockMs(s, 5L))
    }
}
