package com.pisophone.kiosk.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pisophone.kiosk.db.AppDatabase
import com.pisophone.kiosk.repository.PaymentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PaymentOrchestrationIntegrationTest {

    private lateinit var context: Context
    private lateinit var stateManager: KioskStateManager
    private lateinit var engine: KioskEngine
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        stateManager = KioskStateManager(context)
        engine = KioskEngine(context, stateManager)
        db = AppDatabase.getDatabase(context)

        engine.paymentRepo.migrateAndInitialize(context)
        engine.setInitializedForTesting(true)
    }

    @After
    fun tearDown() {
        runBlocking(Dispatchers.IO) {
            db.clearAllTables()
        }
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun testHttpAndWebSocketCrossChannelDeduplication() {
        val txId = "TX_CROSS_101"
        val seconds = 300
        val amount = 5.0
        val opKind = "COIN"
        val coinAmount = 5
        val price = 1.0
        val boxEpoch = 123456789L
        val phoneEpoch = 987654321L

        // 1. Initial credit via HTTP Server Coordinator
        val httpResult = engine.serverCoordinator.creditPayment(
            txId = txId,
            seconds = seconds,
            amount = amount,
            operationKind = opKind,
            coinAmount = coinAmount,
            pricePerCoin = price,
            boxInstallationEpoch = boxEpoch,
            phonePairingEpoch = phoneEpoch
        )
        assertEquals(PaymentResult.APPLIED, httpResult)

        // 2. Duplicate credit attempt via ESP32 WebSocket Coordinator with same txId
        val wsResult = engine.esp32Coordinator.onCoinMessageReceived(
            seconds = seconds,
            amount = amount,
            txId = txId,
            operationKind = opKind,
            coinAmount = coinAmount,
            pricePerCoin = price,
            boxInstallationEpoch = boxEpoch,
            phonePairingEpoch = phoneEpoch
        )
        assertEquals(PaymentResult.ALREADY_APPLIED, wsResult)

        // 3. Verify exactly one receipt exists with all metadata intact
        runBlocking(Dispatchers.IO) {
            val receipt = db.paymentDao().getReceiptByTxId(txId)
            assertNotNull(receipt)
            assertEquals(seconds, receipt!!.secondsCredited)
            assertEquals(amount, receipt.amount, 0.001)
            assertEquals(opKind, receipt.operationKind)
            assertEquals(coinAmount, receipt.coinAmount)
            assertEquals(price, receipt.pricePerCoin, 0.001)
            assertEquals(boxEpoch, receipt.boxInstallationEpoch)
            assertEquals(phoneEpoch, receipt.phonePairingEpoch)
        }
    }

    @Test
    fun testQuickAddImmediateUnlockTransitionsToActiveSession() {
        stateManager.appState.value = 0 // Locked
        stateManager.sessionTimeRemaining.value = 0

        val txId = "QUICK_ADD_1"
        val seconds = 600
        val result = engine.serverCoordinator.creditPayment(
            txId = txId,
            seconds = seconds,
            amount = 0.0,
            operationKind = "QUICK_ADJUST",
            coinAmount = 0,
            pricePerCoin = 0.0,
            boxInstallationEpoch = 0L,
            phonePairingEpoch = 0L
        )

        assertEquals(PaymentResult.APPLIED, result)
        assertEquals(2, stateManager.appState.value) // Unlocked directly to active state
        assertEquals(seconds, stateManager.sessionTimeRemaining.value)
        assertEquals(0, stateManager.paymentTimeout.value)
    }

    @Test
    fun testNormalCoinFromLockedTransitionsToArmedPaymentState() {
        stateManager.appState.value = 0 // Locked
        stateManager.sessionTimeRemaining.value = 0
        stateManager.coinsInserted.value = 0

        val txId = "COIN_INSERT_1"
        val seconds = 300
        val amount = 5.0
        val result = engine.esp32Coordinator.onCoinMessageReceived(
            seconds = seconds,
            amount = amount,
            txId = txId,
            operationKind = "COIN",
            coinAmount = 5,
            pricePerCoin = 1.0,
            boxInstallationEpoch = 0L,
            phonePairingEpoch = 0L
        )

        assertEquals(PaymentResult.APPLIED, result)
        assertEquals(1, stateManager.appState.value) // Armed payment state
        assertEquals(5, stateManager.coinsInserted.value)
        assertEquals(20, stateManager.paymentTimeout.value)
    }

    @Test
    fun testDeductionToZeroLocksSession() {
        stateManager.appState.value = 2 // Active
        stateManager.sessionTimeRemaining.value = 300

        // Credit a base session first in DB
        engine.creditPayment(
            txId = "BASE_SESSION",
            seconds = 300,
            amount = 5.0,
            operationKind = "COIN"
        )
        stateManager.appState.value = 2

        val result = engine.serverCoordinator.onDeductTime(
            seconds = 300,
            txId = "DEDUCT_FULL",
            operationKind = "MANUAL_DEDUCTION",
            boxInstallationEpoch = 0L,
            phonePairingEpoch = 0L
        )

        assertEquals(PaymentResult.APPLIED, result)
        assertEquals(0, stateManager.appState.value) // Locked
        assertEquals(0, stateManager.sessionTimeRemaining.value)
    }

    @Test
    fun testInitializationGuardRejectsEarlyRequests() {
        engine.setInitializedForTesting(false)

        val creditResult = engine.serverCoordinator.creditPayment(
            txId = "EARLY_CREDIT",
            seconds = 300,
            amount = 5.0,
            operationKind = "COIN",
            coinAmount = 5,
            pricePerCoin = 1.0,
            boxInstallationEpoch = 0L,
            phonePairingEpoch = 0L
        )
        assertEquals(PaymentResult.FAILED, creditResult)

        val deductResult = engine.serverCoordinator.onDeductTime(
            seconds = 60,
            txId = "EARLY_DEDUCT",
            operationKind = "MANUAL_DEDUCTION",
            boxInstallationEpoch = 0L,
            phonePairingEpoch = 0L
        )
        assertEquals(PaymentResult.FAILED, deductResult)

        val wsResult = engine.esp32Coordinator.onCoinMessageReceived(
            seconds = 300,
            amount = 5.0,
            txId = "EARLY_WS",
            operationKind = "COIN",
            coinAmount = 5,
            pricePerCoin = 1.0,
            boxInstallationEpoch = 0L,
            phonePairingEpoch = 0L
        )
        assertEquals(PaymentResult.FAILED, wsResult)
    }
}
