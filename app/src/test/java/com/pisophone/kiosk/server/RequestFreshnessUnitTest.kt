package com.pisophone.kiosk.server

import com.pisophone.kiosk.server.RequestFreshness.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestFreshnessUnitTest {
    private val t0 = 1_790_000_000_000L // a plausible box time
    private var now = t0
    private var known = true

    private fun freshness() = RequestFreshness({ now }, { known })

    @Test
    fun aRecentMessageIsFreshAndOneFarOffInEitherDirectionIsNot() {
        val f = freshness()
        assertEquals(Verdict.FRESH, f.check(now))
        assertEquals(Verdict.FRESH, f.check(now - RequestFreshness.WINDOW_MS))
        assertEquals(Verdict.FRESH, f.check(now + RequestFreshness.WINDOW_MS))
        assertEquals(Verdict.OUTSIDE_WINDOW, f.check(now - RequestFreshness.WINDOW_MS - 1))
        assertEquals(Verdict.OUTSIDE_WINDOW, f.check(now + RequestFreshness.WINDOW_MS + 1))
        assertEquals("a day-old capture", Verdict.OUTSIDE_WINDOW, f.check(now - 24L * 3600 * 1000))
    }

    @Test
    fun theWindowFollowsTheBoxClock() {
        val f = freshness()
        val captured = now
        assertEquals(Verdict.FRESH, f.check(captured))
        now += 10 * 60_000L // ten minutes later the same message is stale
        assertEquals(Verdict.OUTSIDE_WINDOW, f.check(captured))
    }

    @Test
    fun nothingIsEnforcedUntilTheBoxClockIsKnownOrWhenTheBoxSentNoTime() {
        known = false
        val f = freshness()
        assertEquals(Verdict.UNVERIFIED, f.check(now))
        assertEquals("even a very old time: only the signature cache protects now", Verdict.UNVERIFIED, f.check(now - 3_600_000L))
        known = true
        assertEquals(Verdict.UNVERIFIED, f.check(0L))
        assertEquals(Verdict.UNVERIFIED, f.check(-5L))
    }

    @Test
    fun theSignatureCacheRemembersAMessageLongerThanItCanPassTheWindow() {
        assertTrue(ReplayGuard.DEFAULT_TTL_MS >= RequestFreshness.MIN_REPLAY_MEMORY_MS)
    }
}
