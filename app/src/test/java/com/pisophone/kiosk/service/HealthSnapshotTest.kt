package com.pisophone.kiosk.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthSnapshotTest {
    @Test
    fun theLineNamesTheReceiptCount() {
        assertEquals("Health snapshot: payment_receipts=1234", HealthSnapshot.line(1234))
    }

    @Test
    fun theFirstCallIsDueThenOnceAnHour() {
        val t0 = 1_000_000L
        assertTrue(HealthSnapshot.isDue(0L, t0))
        assertFalse(HealthSnapshot.isDue(t0, t0 + 59 * 60_000L))
        assertTrue(HealthSnapshot.isDue(t0, t0 + 60 * 60_000L))
    }

    @Test
    fun aClockThatWentBackIsDueAgainInsteadOfSilentForHours() {
        assertTrue(HealthSnapshot.isDue(5_000_000L, 1_000L))
    }
}
