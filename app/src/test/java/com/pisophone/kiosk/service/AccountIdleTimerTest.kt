package com.pisophone.kiosk.service

import com.pisophone.kiosk.service.AccountIdleTimer.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountIdleTimerTest {
    private val timer = AccountIdleTimer(offLimitMs = 120_000L, warnBeforeMs = 30_000L)

    @Test
    fun nothingHappensWhileTheScreenIsOn() {
        assertEquals(Action.NONE, timer.tick(0L, signedIn = true))
        assertEquals(Action.NONE, timer.tick(10_000_000L, signedIn = true))
    }

    @Test
    fun warnsOnceThenSignsOutOnceWhenTheScreenStaysOff() {
        timer.onScreenOff(1_000L)
        assertEquals(Action.NONE, timer.tick(1_000L + 89_999L, signedIn = true))
        assertEquals(Action.WARN, timer.tick(1_000L + 90_000L, signedIn = true))
        assertEquals(Action.NONE, timer.tick(1_000L + 100_000L, signedIn = true)) // the warning is spoken only once
        assertEquals(Action.SIGN_OUT, timer.tick(1_000L + 120_000L, signedIn = true))
        assertEquals(Action.NONE, timer.tick(1_000L + 130_000L, signedIn = true)) // and the sign-out is not repeated
    }

    @Test
    fun turningTheScreenBackOnCancelsTheCountdown() {
        timer.onScreenOff(0L)
        assertEquals(Action.WARN, timer.tick(95_000L, signedIn = true))
        timer.onScreenOn()
        assertEquals(Action.NONE, timer.tick(200_000L, signedIn = true))
        // a later idle stretch starts from zero and warns again
        timer.onScreenOff(300_000L)
        assertEquals(Action.NONE, timer.tick(300_000L + 60_000L, signedIn = true))
        assertEquals(Action.WARN, timer.tick(300_000L + 90_000L, signedIn = true))
    }

    @Test
    fun anAnonymousPhoneIsNeverSignedOut() {
        timer.onScreenOff(0L)
        assertEquals(Action.NONE, timer.tick(500_000L, signedIn = false))
    }

    @Test
    fun aSecondScreenOffEventDoesNotRestartTheClock() {
        timer.onScreenOff(0L)
        timer.onScreenOff(100_000L) // already off: keep the first time
        assertEquals(Action.SIGN_OUT, timer.tick(120_000L, signedIn = true))
    }

    @Test
    fun aPlayerSignedInWhileTheScreenWasAlreadyOffStillTimesOutFromTheScreenOffMoment() {
        timer.onScreenOff(0L)
        assertEquals(Action.NONE, timer.tick(60_000L, signedIn = false))
        assertEquals(Action.SIGN_OUT, timer.tick(125_000L, signedIn = true))
    }
}
