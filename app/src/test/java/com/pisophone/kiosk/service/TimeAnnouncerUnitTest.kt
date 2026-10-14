package com.pisophone.kiosk.service

import com.pisophone.kiosk.service.TimeAnnouncer.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeAnnouncerUnitTest {
    private fun run(seconds: List<Int>): List<Action?> {
        val announcer = TimeAnnouncer()
        return seconds.map { announcer.onTick(it) }
    }

    @Test
    fun `the first tick is only a baseline`() {
        assertNull(TimeAnnouncer().onTick(200))
    }

    @Test
    fun `each warning is said once when its second is crossed`() {
        val a = TimeAnnouncer()
        a.onTick(302)
        assertNull(a.onTick(301))
        assertEquals(Action.Warning("5 minutes time remaining"), a.onTick(300))
        assertNull(a.onTick(299))
        a.onTick(181)
        assertEquals(Action.Warning("3 minutes time remaining"), a.onTick(180))
        a.onTick(61)
        assertEquals(Action.Warning("1 minute time remaining"), a.onTick(60))
        a.onTick(11)
        assertEquals(Action.Warning("10 seconds time remaining"), a.onTick(10))
    }

    @Test
    fun `a late tick that jumps over a warning still says it`() {
        val a = TimeAnnouncer()
        a.onTick(302)
        assertEquals(Action.Warning("5 minutes time remaining"), a.onTick(298))
    }

    @Test
    fun `a late tick that crosses two warnings says the later one`() {
        val a = TimeAnnouncer()
        a.onTick(70)
        assertEquals(Action.Warning("10 seconds time remaining"), a.onTick(9))
    }

    @Test
    fun `the final countdown says 5 4 3 2 1 one by one`() {
        val actions = run(listOf(7, 6, 5, 4, 3, 2, 1, 0))
        assertEquals(
            listOf(null, null, Action.Digit(5), Action.Digit(4), Action.Digit(3), Action.Digit(2), Action.Digit(1), null),
            actions,
        )
    }

    @Test
    fun `a late tick in the countdown says the number now, not an old one`() {
        val actions = run(listOf(7, 6, 5, 3, 2, 1))
        assertEquals(listOf(null, null, Action.Digit(5), Action.Digit(3), Action.Digit(2), Action.Digit(1)), actions)
    }

    @Test
    fun `time added during the countdown cancels it`() {
        val a = TimeAnnouncer()
        a.onTick(7)
        a.onTick(4)
        assertEquals(Action.CancelCountdown, a.onTick(900))
        assertNull(a.onTick(899))
    }

    @Test
    fun `time added outside the countdown says nothing`() {
        val a = TimeAnnouncer()
        a.onTick(100)
        assertNull(a.onTick(1900))
    }

    @Test
    fun `a restored session below a warning does not announce it`() {
        val a = TimeAnnouncer()
        assertNull(a.onTick(200))
        assertNull(a.onTick(199))
    }
}
