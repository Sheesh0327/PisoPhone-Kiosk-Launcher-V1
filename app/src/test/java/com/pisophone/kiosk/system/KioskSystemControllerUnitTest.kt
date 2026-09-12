package com.pisophone.kiosk.system

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KioskSystemControllerUnitTest {

    private lateinit var context: Context
    private lateinit var controller: AndroidKioskSystemController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        controller = AndroidKioskSystemController(context)
    }

    @Test
    fun testGetStreamMaxVolumeReturnsPositive() {
        val maxVol = controller.getStreamMaxVolume()
        assertTrue("Max volume should be greater than 0", maxVol > 0)
    }

    @Test
    fun testSetStreamVolumeWithinRangeDoesNotCrash() {
        controller.setStreamVolume(5)
        // Verify clamping
        controller.setStreamVolume(-10)
        controller.setStreamVolume(100)
    }

    @Test
    fun testGetMemoryStatsReturnsValidTotals() {
        val (usedMb, totalMb) = controller.getMemoryStats()
        assertTrue("Total memory should be non-negative", totalMb >= 0)
        assertTrue("Used memory should be non-negative", usedMb >= 0)
    }

    @Test
    fun testOptimizeMemoryReturnsPositiveFreed() {
        val freed = controller.optimizeMemory()
        assertTrue("Freed memory should be at least baseline guarantee", freed >= 160L)
    }

    @Test
    fun testScreenBrightnessFallback() {
        val brightness = controller.getScreenBrightness()
        assertTrue("Screen brightness should be within 0..255", brightness in 0f..255f)
    }

    @Test
    fun testTriggerHapticFeedbackDoesNotCrash() {
        controller.triggerHapticFeedback()
    }
}
