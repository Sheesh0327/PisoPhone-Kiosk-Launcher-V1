package com.pisophone.kiosk.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class DiagnosticsLogUnitTest {

    @Before
    fun setUp() {
        DiagnosticsLog.clear()
    }

    @After
    fun tearDown() {
        DiagnosticsLog.clear()
    }

    @Test
    fun keepsEventsOldestFirstWithTagAndText() {
        DiagnosticsLog.add("ESP32", "link up")
        DiagnosticsLog.add("COIN", "credited 300s")
        val lines = DiagnosticsLog.snapshot()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("[ESP32] link up"))
        assertTrue(lines[1].endsWith("[COIN] credited 300s"))
    }

    @Test
    fun dropsOldestWhenFull() {
        for (i in 1..(DiagnosticsLog.MAX_LINES + 25)) DiagnosticsLog.add("T", "event $i")
        val lines = DiagnosticsLog.snapshot()
        assertEquals(DiagnosticsLog.MAX_LINES, lines.size)
        assertTrue(lines.first().endsWith("event 26"))
        assertTrue(lines.last().endsWith("event ${DiagnosticsLog.MAX_LINES + 25}"))
    }

    @Test
    fun lineBreaksInMessagesCannotSplitAnEntry() {
        DiagnosticsLog.add("T", "first\nsecond")
        val lines = DiagnosticsLog.snapshot()
        assertEquals(1, lines.size)
        assertFalse(lines[0].contains('\n'))
    }

    @Test
    fun snapshotIsACopy() {
        DiagnosticsLog.add("T", "a")
        val snap = DiagnosticsLog.snapshot()
        DiagnosticsLog.add("T", "b")
        assertEquals(1, snap.size)
    }

    @Test
    fun concurrentWritersDoNotLoseOrCorruptEntries() {
        val workers = 8
        val perWorker = 100
        val start = CountDownLatch(1)
        val threads = (0 until workers).map { w ->
            thread {
                start.await()
                repeat(perWorker) { DiagnosticsLog.add("W$w", "n$it") }
            }
        }
        start.countDown()
        threads.forEach { it.join() }
        val lines = DiagnosticsLog.snapshot()
        assertEquals(minOf(workers * perWorker, DiagnosticsLog.MAX_LINES), lines.size)
        assertTrue(lines.all { it.contains("[W") })
    }
}
