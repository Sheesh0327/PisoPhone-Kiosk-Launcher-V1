package com.pisophone.kiosk.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pisophone.kiosk.security.KioskActivationManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class KioskEngineUnitTest {

    private lateinit var context: Context
    private lateinit var stateManager: KioskStateManager
    private lateinit var engine: KioskEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        stateManager = KioskStateManager(context)
        engine = KioskEngine(context, stateManager)
    }

    @Test
    fun testLockSessionTransitionsState() {
        stateManager.appState.value = 2
        stateManager.sessionTimeRemaining.value = 600
        stateManager.sessionExpiryDeadlineMs.value = android.os.SystemClock.elapsedRealtime() + 600_000L

        engine.performLockSession()

        assertEquals("AppState should be locked (0)", 0, stateManager.appState.value)
        assertEquals("Time remaining should be reset to 0", 0, stateManager.sessionTimeRemaining.value)
        assertEquals("Deadline should be reset to 0L", 0L, stateManager.sessionExpiryDeadlineMs.value)
    }

    @Test
    fun testAdminBypassWhenProvisioned() {
        KioskActivationManager.setPairingCompleted(context, true)
        val duration = 600

        engine.performAdminBypass(duration)

        assertEquals("AppState should be active (2) during admin bypass", 2, stateManager.appState.value)
        assertEquals("Time remaining should match bypass duration", duration, stateManager.sessionTimeRemaining.value)
    }
}
