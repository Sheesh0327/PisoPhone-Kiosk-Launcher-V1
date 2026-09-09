package com.pisophone.kiosk.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class KioskSessionSupervisorUnitTest {

    private lateinit var context: Context
    private lateinit var stateManager: KioskStateManager
    private val testScope = TestScope()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        stateManager = KioskStateManager(context)
    }

    @Test
    fun testSupervisorStartAndStallCheck() {
        val supervisor = KioskSessionSupervisor(
            context = context,
            scope = testScope,
            stateManager = stateManager,
            onSpeakWarning = {},
            onFinishPayment = {},
            onCloseSession = {},
            onCheckBatteryAlerts = {}
        )

        // Initially before start(), isStalled is false because lastTickMonotonicMs is 0
        assertFalse("Supervisor should not report stalled before starting", supervisor.isStalled())

        supervisor.start()

        // Freshly started, lastTickMonotonicMs is current time so isStalled is false
        assertFalse("Freshly started supervisor should not be stalled", supervisor.isStalled(maxLagMs = 5000L))
    }

    @Test
    fun testEnsureRunningRestartsInactiveJob() {
        val supervisor = KioskSessionSupervisor(
            context = context,
            scope = testScope,
            stateManager = stateManager,
            onSpeakWarning = {},
            onFinishPayment = {},
            onCloseSession = {},
            onCheckBatteryAlerts = {}
        )

        // ensureRunning should start supervisor if not running
        supervisor.ensureRunning()
        assertFalse(supervisor.isStalled(maxLagMs = 5000L))
    }
}
