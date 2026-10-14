package com.pisophone.kiosk.network

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BoxClockTest {
    private val boxNow = 1_790_000_000_000L // a plausible date

    @Before
    fun setUp() = BoxClock.reset()

    @After
    fun tearDown() = BoxClock.reset()

    @Test
    fun aPhoneWithAWrongClockSignsWithTheBoxsTime() {
        val phoneNow = boxNow - 3L * 24 * 60 * 60 * 1000 // three days behind
        assertTrue(BoxClock.learn(boxNow, phoneNow))
        assertEquals(3L * 24 * 60 * 60 * 1000, BoxClock.offsetMs())
        val signed = BoxClock.nowMs()
        assertTrue("within a second of the box's time", kotlin.math.abs(signed - (boxNow + (System.currentTimeMillis() - phoneNow))) < 1000L)
    }

    @Test
    fun theBoxsTimeCountsAsHeardOnlyOnceARealOneArrived() {
        assertFalse(BoxClock.isSynced())
        BoxClock.learn(0L, boxNow)
        BoxClock.learn(12345L, boxNow)
        assertFalse("a time that is not a real date is not the box's clock", BoxClock.isSynced())
        assertFalse(BoxClock.learn(boxNow + 120, boxNow)) // network delay only: no correction needed
        assertTrue("but the time was heard", BoxClock.isSynced())
        BoxClock.reset()
        assertFalse(BoxClock.isSynced())
    }

    @Test
    fun networkDelayIsNotMistakenForAWrongClock() {
        assertFalse(BoxClock.learn(boxNow + 120, boxNow)) // 120 ms: keep no correction
        assertEquals(0L, BoxClock.offsetMs())
        assertTrue(BoxClock.learn(boxNow + 60_000, boxNow)) // a minute off is a wrong clock
        assertFalse("the same answer again changes nothing", BoxClock.learn(boxNow + 60_500, boxNow))
    }

    @Test
    fun aBoxTimeThatIsNotARealDateIsIgnored() {
        assertFalse(BoxClock.learn(0L, boxNow))
        assertFalse(BoxClock.learn(12345L, boxNow))
        assertEquals(0L, BoxClock.offsetMs())
    }

    @Test
    fun readsTheTimeFromAnyAnswerOfTheBox() {
        val body = """{"status":"ok","mac":"AA:BB","server_time_ms":$boxNow}"""
        assertTrue(BoxClock.learnFromBody(body, boxNow - 10 * 60 * 1000L))
        assertEquals(10 * 60 * 1000L, BoxClock.offsetMs())
        assertFalse(BoxClock.learnFromBody("", boxNow))
        assertFalse(BoxClock.learnFromBody("not json at all server_time_ms", boxNow))
        assertFalse(BoxClock.learnFromBody("""{"status":"ok"}""", boxNow))
    }
}
