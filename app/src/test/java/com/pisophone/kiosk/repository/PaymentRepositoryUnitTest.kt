package com.pisophone.kiosk.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pisophone.kiosk.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PaymentRepositoryUnitTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private var isEligibleState = true

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val deviceContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
        deviceContext.getSharedPreferences(PaymentRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        isEligibleState = true
    }

    @After
    fun tearDown() {
        val deviceContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
        deviceContext.getSharedPreferences(PaymentRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        db.close()
    }

    @Test
    fun testFirstCreditReturnsAppliedAndPersistsState() = runBlocking {
        var appliedCount = 0
        val repository = PaymentRepository(
            db = db,
            isEligible = { isEligibleState },
            onPaymentApplied = { _, _, _, _, _ -> appliedCount++ }
        )

        val txId = "tx-1001"
        val result = repository.creditPayment(txId = txId, seconds = 600, amount = 5.0)

        assertEquals("First credit should return APPLIED", PaymentResult.APPLIED, result)
        assertEquals("Post-commit callback called once", 1, appliedCount)

        // Verify in DB
        val receipt = db.paymentDao().getReceiptByTxId(txId)
        assertNotNull("Receipt must be persisted", receipt)
        assertEquals(txId, receipt?.txId)
        assertEquals(600, receipt?.secondsCredited)
        assertEquals(5.0, receipt?.amount ?: 0.0, 0.001)

        val sessionState = repository.getSessionState()
        assertNotNull("Session state must be saved", sessionState)
        assertTrue("Session time remaining must be > 0", (sessionState?.sessionTimeRemaining ?: 0) > 0)
    }

    @Test
    fun testDuplicateReceiptReturnsAlreadyAppliedWithoutExtendingTime() = runBlocking {
        var appliedCount = 0
        val repository = PaymentRepository(
            db = db,
            isEligible = { isEligibleState },
            onPaymentApplied = { _, _, _, _, _ -> appliedCount++ }
        )

        val txId = "tx-1002"
        val result1 = repository.creditPayment(txId = txId, seconds = 300, amount = 1.0)
        assertEquals(PaymentResult.APPLIED, result1)
        assertEquals(1, appliedCount)

        val deadlineAfterFirst = repository.getSessionState()?.sessionExpiryDeadlineMs ?: 0L

        // Replay identical payment
        val result2 = repository.creditPayment(txId = txId, seconds = 300, amount = 1.0)
        assertEquals("Duplicate identical payment returns ALREADY_APPLIED", PaymentResult.ALREADY_APPLIED, result2)
        assertEquals("Post-commit callback must NOT fire on duplicate", 1, appliedCount)

        val deadlineAfterSecond = repository.getSessionState()?.sessionExpiryDeadlineMs ?: 0L
        assertEquals("Session deadline must NOT extend on duplicate", deadlineAfterFirst, deadlineAfterSecond)
    }

    @Test
    fun testConflictingValuesReturnConflict() = runBlocking {
        val repository = PaymentRepository(
            db = db,
            isEligible = { isEligibleState }
        )

        val txId = "tx-1003"
        val res1 = repository.creditPayment(txId = txId, seconds = 300, amount = 1.0)
        assertEquals(PaymentResult.APPLIED, res1)

        // Replay with different seconds
        val resDiffSeconds = repository.creditPayment(txId = txId, seconds = 600, amount = 1.0)
        assertEquals("Different seconds returns CONFLICT", PaymentResult.CONFLICT, resDiffSeconds)

        // Replay with different amount
        val resDiffAmount = repository.creditPayment(txId = txId, seconds = 300, amount = 5.0)
        assertEquals("Different amount returns CONFLICT", PaymentResult.CONFLICT, resDiffAmount)
    }

    @Test
    fun testNotEligibleRejectsNewPayment() = runBlocking {
        isEligibleState = false
        val repository = PaymentRepository(
            db = db,
            isEligible = { isEligibleState }
        )

        val txId = "tx-1004"
        val result = repository.creditPayment(txId = txId, seconds = 300, amount = 1.0)
        assertEquals("New payment rejected when not eligible", PaymentResult.NOT_ELIGIBLE, result)

        val receipt = db.paymentDao().getReceiptByTxId(txId)
        assertEquals("No receipt must be saved when not eligible", null, receipt)
    }

    @Test
    fun testAlreadyCommittedPaymentReceivesDuplicateAcknowledgmentWhenEligibilityChanges() = runBlocking {
        isEligibleState = true
        val repository = PaymentRepository(
            db = db,
            isEligible = { isEligibleState }
        )

        val txId = "tx-1005"
        // Initially eligible and applied
        val res1 = repository.creditPayment(txId = txId, seconds = 600, amount = 5.0)
        assertEquals(PaymentResult.APPLIED, res1)

        // Now device state changes to ineligible (e.g. locked down / unprovisioned)
        isEligibleState = false

        // Replay of the already committed payment
        val res2 = repository.creditPayment(txId = txId, seconds = 600, amount = 5.0)
        assertEquals(
            "Already committed payment must still return ALREADY_APPLIED even if eligibility changed",
            PaymentResult.ALREADY_APPLIED,
            res2
        )
    }

    @Test
    fun testDeductTimeDecreasesBalanceAndPersists() = runBlocking {
        val repository = PaymentRepository(db = db, isEligible = { true })
        repository.adjustSessionTime(1200)

        val updated = repository.deductTime(300)
        assertTrue("Time remaining should decrease", updated.sessionTimeRemaining <= 900)

        val persisted = repository.getSessionState()
        assertEquals(updated.sessionTimeRemaining, persisted?.sessionTimeRemaining)
    }

    @Test
    fun testExpireSessionResetsBalanceAndPersists() = runBlocking {
        val repository = PaymentRepository(db = db, isEligible = { true })
        repository.adjustSessionTime(1200)

        repository.expireSession()

        val persisted = repository.getSessionState()
        assertEquals(0, persisted?.sessionTimeRemaining)
        assertEquals(0L, persisted?.sessionExpiryDeadlineMs)
    }

    @Test
    fun testCheckpointDoesNotOverwriteNewerCommittedCredit() = runBlocking {
        val repository = PaymentRepository(db = db, isEligible = { true })
        // Credit a new payment
        repository.creditPayment("tx-new-credit", 1200, 10.0)
        val stateAfterCredit = repository.getSessionState()!!

        // Attempt to checkpoint with an older snapshot
        val staleRemaining = 100
        val staleDeadline = android.os.SystemClock.elapsedRealtime() + 100_000L
        repository.checkpointSession(staleRemaining, staleDeadline)

        val stateAfterStaleCheckpoint = repository.getSessionState()!!
        assertEquals(
            "Checkpointing with older deadline must not overwrite newer committed credit",
            stateAfterCredit.sessionExpiryDeadlineMs,
            stateAfterStaleCheckpoint.sessionExpiryDeadlineMs
        )
    }

    @Test
    fun testRestoreSessionStateRecoversFromDatabase() = runBlocking {
        val repository = PaymentRepository(db = db, isEligible = { true })
        repository.adjustSessionTime(600)

        val restored = repository.restoreSessionState()
        assertTrue("Restored time remaining should be > 0", restored.remainingSeconds > 0)
        assertTrue("Restored deadline should be > 0", restored.deadlineMs > 0L)
    }

    @Test
    fun testLegacyMigrationImportsPaidStateWithoutClearingCredit() = runBlocking {
        val deviceContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
        val prefs = deviceContext.getSharedPreferences(PaymentRepository.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val expectedRemaining = 900
        val expectedDeadline = android.os.SystemClock.elapsedRealtime() + (expectedRemaining * 1000L)
        prefs.edit()
            .putInt("session_time_remaining", expectedRemaining)
            .putLong("session_expiry_deadline", expectedDeadline)
            .commit()

        val repository = PaymentRepository(db = db, context = context, isEligible = { true })
        repository.migrateAndInitialize(context)

        // Verify session state was imported into Room
        val state = repository.getSessionState()
        assertNotNull("Session state must exist in Room after migration", state)
        assertTrue("Session time remaining must match imported value", state!!.sessionTimeRemaining >= 899)
        assertEquals("Session deadline must match imported deadline", expectedDeadline, state.sessionExpiryDeadlineMs)

        // Verify durable migration marker is set
        assertTrue("Durable migration marker must be true", prefs.getBoolean(PaymentRepository.KEY_MIGRATION_MARKER, false))
    }

    @Test
    fun testLegacyMigrationReconcilesHistoricalPaymentsWithoutAddingCreditAgain() = runBlocking {
        val deviceContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
        val prefs = deviceContext.getSharedPreferences(PaymentRepository.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        // Seed coin_events in database
        db.coinEventDao().insertEvent(
            com.pisophone.kiosk.db.CoinEvent(
                txId = "legacy-coin-1",
                source = "COIN",
                secondsAdded = 600,
                timestamp = System.currentTimeMillis() - 50000L
            )
        )

        // Seed processed_tx_ids in SharedPreferences
        prefs.edit()
            .putStringSet("processed_tx_ids", setOf("legacy-tx-2", "legacy-tx-3"))
            .commit()

        val repository = PaymentRepository(db = db, context = context, isEligible = { true })
        repository.migrateAndInitialize(context)

        // Check that all 3 transactions exist in payment_receipts
        assertNotNull("legacy-coin-1 should be in payment_receipts", db.paymentDao().getReceiptByTxId("legacy-coin-1"))
        assertNotNull("legacy-tx-2 should be in payment_receipts", db.paymentDao().getReceiptByTxId("legacy-tx-2"))
        assertNotNull("legacy-tx-3 should be in payment_receipts", db.paymentDao().getReceiptByTxId("legacy-tx-3"))

        // Attempting to credit any of these legacy transactions must return ALREADY_APPLIED and NOT add credit again
        val initialBalance = repository.getSessionState()?.sessionTimeRemaining ?: 0
        val creditRes = repository.creditPayment("legacy-coin-1", 600, 5.0)
        assertEquals("Reconciled legacy txId must return ALREADY_APPLIED", PaymentResult.ALREADY_APPLIED, creditRes)

        val balanceAfterReplay = repository.getSessionState()?.sessionTimeRemaining ?: 0
        assertEquals("Replay of reconciled legacy txId must NOT add extra credit", initialBalance, balanceAfterReplay)
    }
}

