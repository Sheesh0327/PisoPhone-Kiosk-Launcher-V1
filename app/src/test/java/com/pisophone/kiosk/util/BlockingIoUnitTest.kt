package com.pisophone.kiosk.util

import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BlockingIoUnitTest {
    @Test
    fun returnsTheResultOfTheSuspendBlock() {
        assertEquals(42, blockingIo { delay(5); 42 })
    }

    @Test
    fun aCallFromTheMainThreadIsRecordedInTheDiagnostics() {
        // Robolectric runs the test on the main thread
        blockingIo { 1 }
        assertTrue(DiagnosticsLog.snapshot().any { it.contains("BLOCKING") })
    }
}
