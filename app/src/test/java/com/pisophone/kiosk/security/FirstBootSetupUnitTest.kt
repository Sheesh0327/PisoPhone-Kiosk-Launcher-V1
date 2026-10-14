package com.pisophone.kiosk.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pisophone.kiosk.security.FirstBootSetup.Step
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FirstBootSetupUnitTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        KioskSecurity.getDirectBootPrefs(context, "kiosk_first_boot").edit().clear().commit()
    }

    @Test
    fun aFreshPhoneIsPendingAndAPhoneAlreadyInUseIsLeftAlone() {
        val hour = 60L * 60 * 1000
        assertEquals(Step.PENDING, FirstBootSetup.classify(0))
        assertEquals(Step.PENDING, FirstBootSetup.classify(2 * hour))
        assertEquals(Step.DONE, FirstBootSetup.classify(4 * hour))
        assertEquals(Step.DONE, FirstBootSetup.classify(30L * 24 * hour))
        assertEquals("a clock set backwards is not a fresh phone", Step.DONE, FirstBootSetup.classify(-1))
    }

    @Test
    fun nothingHappensUntilSignInStarted() {
        assertEquals(Step.UNSEEN, FirstBootSetup.step(context))
        FirstBootSetup.onKioskScreenLeft(context)
        FirstBootSetup.onKioskScreenReturned(context)
        assertEquals("coming back to the kiosk screen alone completes nothing", Step.UNSEEN, FirstBootSetup.step(context))
    }

    @Test
    fun anAdminRestoreMarksTheSetupDoneSoItNeverRunsAgain() {
        FirstBootSetup.markDone(context)
        assertEquals(Step.DONE, FirstBootSetup.step(context))
        FirstBootSetup.maybeStart(context)
        assertEquals(Step.DONE, FirstBootSetup.step(context))
    }
}
