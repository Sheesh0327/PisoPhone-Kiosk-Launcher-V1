package com.pisophone.kiosk.overlay

import com.pisophone.kiosk.overlay.ui.TimeLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeLevelUnitTest {
    @Test
    fun `levels follow the 5 minute and 1 minute marks`() {
        assertEquals(TimeLevel.OK, TimeLevel.of(0))
        assertEquals(TimeLevel.OK, TimeLevel.of(301))
        assertEquals(TimeLevel.LOW, TimeLevel.of(300))
        assertEquals(TimeLevel.LOW, TimeLevel.of(61))
        assertEquals(TimeLevel.CRITICAL, TimeLevel.of(60))
        assertEquals(TimeLevel.CRITICAL, TimeLevel.of(1))
    }
}
