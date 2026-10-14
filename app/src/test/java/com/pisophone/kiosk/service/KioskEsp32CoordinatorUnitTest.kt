package com.pisophone.kiosk.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pisophone.kiosk.db.AppDatabase
import com.pisophone.kiosk.network.Esp32Responses
import com.pisophone.kiosk.repository.PaymentRepository
import com.pisophone.kiosk.repository.PaymentResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KioskEsp32CoordinatorUnitTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var stateManager: KioskStateManager
    private lateinit var coordinator: KioskEsp32Coordinator
    private var nextCreditResult = PaymentResult.APPLIED
    private val lockEvents = mutableListOf<Boolean>()
    private val shownFailures = mutableListOf<Esp32Responses.ArmFailure>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        stateManager = KioskStateManager(context)
        lockEvents.clear()
        shownFailures.clear()
        coordinator = KioskEsp32Coordinator(
            context = context,
            stateManager = stateManager,
            paymentRepo = PaymentRepository(db = db, isEligible = { true }),
            armingTimeoutSeconds = 20,
            getSecretKey = { "secret" },
            getRealTimeBatteryInfo = { Pair(80, false) },
            onCreditPayment = { _, _, _ -> nextCreditResult },
            onArmFailedTriggered = { shownFailures.add(it) },
            onSessionLocked = { cancelArm -> lockEvents.add(cancelArm) },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun busyFrom(state: Int, remaining: Int = 0): Int {
        stateManager.appState.value = state
        stateManager.sessionTimeRemaining.value = remaining
        stateManager.isArmingInProgress.value = true
        coordinator.onArmFailed(Esp32Responses.ArmFailure.BUSY)
        assertFalse("Arming flag must be cleared on busy", stateManager.isArmingInProgress.value)
        return stateManager.appState.value
    }

    @Test
    fun everyArmFailureIsShownAndNeverLocksAPaidSession() {
        for (failure in Esp32Responses.ArmFailure.entries) {
            stateManager.appState.value = 2
            stateManager.sessionTimeRemaining.value = 600
            coordinator.onArmFailed(failure)
            assertEquals("Paid session stays unlocked after $failure", 2, stateManager.appState.value)
        }
        assertEquals(Esp32Responses.ArmFailure.entries.toList(), shownFailures)
    }

    @Test
    fun testSlotBusyNeverLocksPaidSession() {
        assertEquals("Unlocked session stays unlocked", 2, busyFrom(2, remaining = 600))
        assertEquals("Unlocked+armed drops arm but stays unlocked", 2, busyFrom(3, remaining = 600))
        assertEquals("Locked+armed without balance locks", 0, busyFrom(1))
        assertEquals("Locked+armed with paid balance unlocks", 2, busyFrom(1, remaining = 120))
        assertEquals("Locked stays locked", 0, busyFrom(0))
    }

    @Test
    fun testSlotLockdownClearsArmingFlagAndLocks() {
        stateManager.appState.value = 3
        stateManager.isArmingInProgress.value = true

        coordinator.onSlotLockdown("expired", 2, 0L)

        assertFalse("Arming flag must be cleared on lockdown", stateManager.isArmingInProgress.value)
        assertEquals(0, stateManager.appState.value)
        assertEquals("Lock side effects must run once and cancel the arm", listOf(true), lockEvents)

        // Repeated heartbeat lockdowns while already locked must not re-run side effects.
        coordinator.onSlotLockdown("expired", 2, 0L)
        assertEquals(listOf(true), lockEvents)
    }

    @Test
    fun testArmSuccessPublishesTimeoutBeforeState() {
        stateManager.appState.value = 0
        stateManager.paymentTimeout.value = 0
        stateManager.isArmingInProgress.value = true

        coordinator.onArmSuccess()

        assertEquals(1, stateManager.appState.value)
        assertEquals(20, stateManager.paymentTimeout.value)
        assertFalse(stateManager.isArmingInProgress.value)
    }

    @Test
    fun testCoinResultIsPropagatedForAckDecision() {
        nextCreditResult = PaymentResult.FAILED
        assertEquals(PaymentResult.FAILED, coordinator.onCoinMessageReceived(60, 5.0, "tx-1"))
        nextCreditResult = PaymentResult.ALREADY_APPLIED
        assertEquals(PaymentResult.ALREADY_APPLIED, coordinator.onCoinMessageReceived(60, 5.0, "tx-1"))
        assertEquals("Missing txId is never acked", PaymentResult.FAILED, coordinator.onCoinMessageReceived(60, 5.0, null))
    }
}
