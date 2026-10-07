package com.pisophone.kiosk.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class DiagnosticsLogUnitTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        DiagnosticsLog.detach()
        DiagnosticsLog.clear()
    }

    @After
    fun tearDown() {
        DiagnosticsLog.detach()
        DiagnosticsLog.clear()
    }

    /** Simulates a new app process: memory is gone, the file is read back. */
    private fun restartWith(file: File) {
        DiagnosticsLog.detach()
        DiagnosticsLog.clear()
        DiagnosticsLog.persistTo(file)
    }

    @Test
    fun linesSurviveARestart() {
        val file = File(tmp.root, "diagnostics.log")
        DiagnosticsLog.persistTo(file)
        DiagnosticsLog.add("ESP32", "link down")
        DiagnosticsLog.add("COIN", "tx 1 -> APPLIED")

        restartWith(file)
        DiagnosticsLog.add("APP", "started")

        val lines = DiagnosticsLog.snapshot()
        assertEquals(3, lines.size)
        assertTrue(lines[0].endsWith("[ESP32] link down"))
        assertTrue(lines[2].endsWith("[APP] started"))
    }

    @Test
    fun linesLoggedBeforeTheFileIsAttachedAreKeptAfterTheEarlierOnes() {
        val file = File(tmp.root, "diagnostics.log")
        file.writeText("01-01 00:00:00 [T] from last run\n")
        DiagnosticsLog.add("T", "early")
        DiagnosticsLog.persistTo(file)
        val lines = DiagnosticsLog.snapshot()
        assertEquals(listOf("01-01 00:00:00 [T] from last run"), lines.take(1))
        assertTrue(lines[1].endsWith("[T] early"))
        assertEquals(2, file.readLines().size)
    }

    @Test
    fun fileStaysBoundedAndKeepsTheNewestLines() {
        val file = File(tmp.root, "diagnostics.log")
        DiagnosticsLog.persistTo(file)
        val total = DiagnosticsLog.MAX_LINES * 5 + 7
        for (i in 1..total) DiagnosticsLog.add("T", "event $i")
        assertTrue(file.readLines().size <= DiagnosticsLog.MAX_LINES * 2)

        restartWith(file)
        val lines = DiagnosticsLog.snapshot()
        assertEquals(DiagnosticsLog.MAX_LINES, lines.size)
        assertTrue(lines.last().endsWith("event $total"))
        assertTrue(lines.first().endsWith("event ${total - DiagnosticsLog.MAX_LINES + 1}"))
    }

    @Test
    fun clearAlsoEmptiesTheFile() {
        val file = File(tmp.root, "diagnostics.log")
        DiagnosticsLog.persistTo(file)
        DiagnosticsLog.add("T", "a")
        DiagnosticsLog.clear()
        restartWith(file)
        assertTrue(DiagnosticsLog.snapshot().isEmpty())
    }

    @Test
    fun anUnreadableFileDoesNotBreakLogging() {
        val dir = tmp.newFolder("not-a-file")
        DiagnosticsLog.persistTo(dir)
        DiagnosticsLog.add("T", "still works")
        assertTrue(DiagnosticsLog.snapshot().last().endsWith("[T] still works"))
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
