package com.pisophone.kiosk.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursUnitTest {
    @Test
    fun `a window inside one day`() {
        assertTrue(QuietHours.contains(13, 12, 14))
        assertFalse(QuietHours.contains(14, 12, 14))
        assertFalse(QuietHours.contains(11, 12, 14))
    }

    @Test
    fun `a window across midnight`() {
        assertTrue(QuietHours.contains(23, 22, 7))
        assertTrue(QuietHours.contains(0, 22, 7))
        assertTrue(QuietHours.contains(6, 22, 7))
        assertFalse(QuietHours.contains(7, 22, 7))
        assertFalse(QuietHours.contains(12, 22, 7))
        assertTrue(QuietHours.contains(22, 22, 7))
    }

    @Test
    fun `equal start and end means no quiet window`() {
        assertFalse(QuietHours.contains(5, 8, 8))
    }
}
