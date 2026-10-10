package com.pisophone.kiosk.server

import com.pisophone.kiosk.server.RequestFreshness.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestFreshnessUnitTest {
    private class MemoryStore(var state: String = "") : ReplayStateStore {
        var saves = 0

        override fun load() = state

        override fun save(state: String) {
            this.state = state
            saves++
        }
    }

    private val t0 = 1_790_000_000_000L // a plausible box time
    private var now = t0
    private var known = true
    private val store = MemoryStore()

    private fun newFreshness(s: ReplayStateStore = store) = RequestFreshness({ now }, { known }, s)

    private fun sig(n: Int) = "a".repeat(60) + n.toString(16).padStart(4, '0')

    @Test
    fun aRecentMessageIsFreshAndOneFarOffInEitherDirectionIsNot() {
        val f = newFreshness()
        assertEquals(Verdict.FRESH, f.check(now))
        assertEquals(Verdict.FRESH, f.check(now - RequestFreshness.WINDOW_MS))
        assertEquals(Verdict.FRESH, f.check(now + RequestFreshness.WINDOW_MS))
        assertEquals(Verdict.OUTSIDE_WINDOW, f.check(now - RequestFreshness.WINDOW_MS - 1))
        assertEquals(Verdict.OUTSIDE_WINDOW, f.check(now + RequestFreshness.WINDOW_MS + 1))
        assertEquals("a day-old capture", Verdict.OUTSIDE_WINDOW, f.check(now - 24L * 3600 * 1000))
    }

    @Test
    fun nothingIsEnforcedUntilTheBoxClockIsKnownOrWhenTheBoxSentNoTime() {
        known = false
        val f = newFreshness()
        assertEquals(Verdict.UNVERIFIED, f.check(now))
        assertEquals("even a very old time: only the signature cache protects now", Verdict.UNVERIFIED, f.check(now - 3_600_000L))
        known = true
        assertEquals(Verdict.UNVERIFIED, f.check(0L))
        assertEquals(Verdict.UNVERIFIED, f.check(-5L))
    }

    @Test
    fun aMessageOlderThanTheNewestAcceptedOneIsRefusedBeyondTheJitter() {
        val f = newFreshness()
        f.accepted(now, sig(1))
        assertEquals(Verdict.FRESH, f.check(now - RequestFreshness.JITTER_MS))
        assertEquals(Verdict.OLDER_THAN_NEWEST, f.check(now - RequestFreshness.JITTER_MS - 1))
        now += 10_000L
        f.accepted(now, sig(2))
        assertEquals("only 10 s behind the newest: still within the jitter", Verdict.FRESH, f.check(t0))
        now += 30_000L
        f.accepted(now, sig(3))
        assertEquals("40 s behind the newest: the floor moved with it", Verdict.OLDER_THAN_NEWEST, f.check(t0))
    }

    @Test
    fun theReplayStateSurvivesARestartOfTheApp() {
        val first = newFreshness()
        first.accepted(t0, sig(1))
        first.accepted(t0 + 5_000L, sig(2))
        now = t0 + 20_000L

        val afterRestart = newFreshness() // same storage, nothing in memory
        assertEquals(listOf(sig(1), sig(2)), afterRestart.recentSignatures())
        assertEquals(Verdict.OLDER_THAN_NEWEST, afterRestart.check(t0 - 60_000L))
        assertEquals(Verdict.FRESH, afterRestart.check(t0 + 6_000L))
    }

    @Test
    fun onlyMessagesThatCanStillPassAreRemembered() {
        val f = newFreshness()
        f.accepted(t0, sig(1))
        now = t0 + 100_000L
        f.accepted(now, sig(2)) // t0 is now more than the jitter behind: time alone refuses it, no need to keep it
        assertEquals(listOf(sig(2)), newFreshness().recentSignatures())
    }

    @Test
    fun theMemoryIsBounded() {
        val f = newFreshness()
        for (i in 1..100) f.accepted(t0 + i, sig(i))
        assertEquals(32, newFreshness().recentSignatures().size)
        assertEquals(sig(100), newFreshness().recentSignatures().last())
    }

    @Test
    fun aStoredTimeFromAWrongClockCannotRefuseTheBoxForGood() {
        store.state = "1;${t0 + 3_600_000L}" // an hour ahead of the box's clock: no real message can be that new
        val f = newFreshness()
        assertEquals(Verdict.FRESH, f.check(now))
        f.accepted(now, sig(1))
        assertEquals("replaced by the next accepted message", Verdict.OLDER_THAN_NEWEST, newFreshness().check(now - 60_000L))
        assertEquals(Verdict.FRESH, newFreshness().check(now))
    }

    @Test
    fun aStoredTimeThatIsBehindTheClockIsKeptAsTheFloor() {
        store.state = "1;${t0 - 20_000L}"
        assertEquals(Verdict.OLDER_THAN_NEWEST, newFreshness().check(t0 - 60_000L))
    }

    @Test
    fun damagedStorageStartsCleanInsteadOfCrashingOrRefusing() {
        for (bad in listOf("", "garbage", "2;123", "1;notanumber", "1;5;12,zz", "1;5;nocomma", "1;5;,abc", ";;;", "1")) {
            val s = MemoryStore(bad)
            val f = RequestFreshness({ now }, { known }, s)
            assertEquals(bad, Verdict.FRESH, f.check(now))
            assertTrue(bad, f.recentSignatures().isEmpty())
        }
    }

    @Test
    fun aStoreThatThrowsDoesNotBreakTheServer() {
        val broken = object : ReplayStateStore {
            override fun load(): String = throw IllegalStateException("flash")

            override fun save(state: String) = throw IllegalStateException("flash")
        }
        val f = RequestFreshness({ now }, { known }, broken)
        assertEquals(Verdict.FRESH, f.check(now))
        f.accepted(now, sig(1)) // must not throw
        assertEquals(Verdict.FRESH, f.check(now))
    }

    @Test
    fun theSignatureCacheRemembersAMessageLongerThanItCanPassTheWindow() {
        assertTrue(ReplayGuard.DEFAULT_TTL_MS >= RequestFreshness.MIN_REPLAY_MEMORY_MS)
    }
}
