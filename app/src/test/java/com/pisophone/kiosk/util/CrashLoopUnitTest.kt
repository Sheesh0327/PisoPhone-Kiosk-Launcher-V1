package com.pisophone.kiosk.util

import org.junit.Assert.assertEquals
import org.junit.Test

class CrashLoopUnitTest {
    @Test
    fun `the restart waits longer when crashes repeat`() {
        assertEquals(1_000L, CrashLoop.restartDelayMs(1))
        assertEquals(1_000L, CrashLoop.restartDelayMs(2))
        assertEquals(30_000L, CrashLoop.restartDelayMs(3))
        assertEquals(30_000L, CrashLoop.restartDelayMs(4))
        assertEquals(120_000L, CrashLoop.restartDelayMs(5))
    }

    @Test
    fun `old crash times fall out of the list`() {
        val now = 10_000_000_000L
        val day = CrashLoop.DAY_MS
        val parsed = CrashLoop.parse("${now - day - 1},${now - 5_000},${now - 60_000},junk,", now)
        assertEquals(listOf(now - 5_000, now - 60_000), parsed)
    }

    @Test
    fun `crashes are counted within a window`() {
        val now = 10_000_000_000L
        val times = listOf(now - 11 * 60_000, now - 9 * 60_000, now - 1_000)
        assertEquals(2, CrashLoop.countWithin(times, now, CrashLoop.LOOP_WINDOW_MS))
        assertEquals(3, CrashLoop.countWithin(times, now, CrashLoop.DAY_MS))
    }
}
