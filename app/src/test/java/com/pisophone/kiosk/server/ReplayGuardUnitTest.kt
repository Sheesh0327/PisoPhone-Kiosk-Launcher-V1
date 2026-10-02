package com.pisophone.kiosk.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayGuardUnitTest {
    private var now = 1_000_000L
    private val guard = ReplayGuard(ttlMs = 60_000L, maxEntries = 3, clock = { now })

    @Test
    fun repeatIsRejected() {
        assertTrue(guard.firstSeen("a"))
        assertFalse(guard.firstSeen("a"))
        assertTrue(guard.firstSeen("b"))
    }

    @Test
    fun entryExpiresAfterTheWindow() {
        assertTrue(guard.firstSeen("a"))
        now += 60_001L
        assertTrue(guard.firstSeen("a"))
    }

    @Test
    fun memoryIsBounded() {
        for (k in listOf("a", "b", "c", "d")) assertTrue(guard.firstSeen(k))
        assertTrue("oldest was dropped to make room", guard.firstSeen("a"))
        assertFalse("newest is still remembered", guard.firstSeen("d"))
    }
}
