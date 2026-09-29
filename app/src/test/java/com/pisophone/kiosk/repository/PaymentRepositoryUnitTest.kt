package com.pisophone.kiosk.repository

import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pisophone.kiosk.db.AppDatabase
import com.pisophone.kiosk.db.PaidSessionState
import com.pisophone.kiosk.service.KioskStateManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            onPaymentApplied = { _, _, _, _ -> appliedCount++ }
        )

        val txId = "tx-1001"
        val result = repository.creditPayment(txId = txId, seconds = 600, amount = 5.0)

        assertEquals("First credit should return APPLIED", PaymentResult.APPLIED, result)
        assertEquals("Post-commit callback called once", 1, appliedCount)

        val receipt = db.paymentDao().getReceiptByTxId(txId)
        assertNotNull("Receipt must be persisted", receipt)
        assertEquals(txId, receipt?.txId)
        assertEquals(600, receipt?.secondsCredited)
        assertEquals(5.0, receipt?.amount ?: 0.0, 0.001)

        val sessionState = repository.getSessionState()
        assertNotNull("Session state must be saved", sessionState)
        assertTrue("Session time remaining must be > 0", (sessionState?.sessionTimeRemaining ?: 0) > 0)
        assertEquals("Revision must be 1 on first credit", 1L, sessionState?.revision)
    }

    @Test
    fun testDuplicateReceiptReturnsAlreadyAppliedWithoutExtendingTime() = runBlocking {
        var appliedCount = 0
        val repository = PaymentRepository(
            db = db,
            isEligible = { isEligibleState },
            onPaymentApplied = { _, _, _, _ -> appliedCount++ }
        )

        val txId = "tx-1002"
        val result1 = repository.creditPayment(txId = txId, seconds = 300, amount = 1.0)
        assertEquals(PaymentResult.APPLIED, result1)
        assertEquals(1, appliedCount)

        val deadlineAfterFirst = repository.getSessionState()?.sessionExpiryDeadlineMs ?: 0L

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

        val resDiffSeconds = repository.creditPayment(txId = txId, seconds = 600, amount = 1.0)
        assertEquals("Different seconds returns CONFLICT", PaymentResult.CONFLICT, resDiffSeconds)

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
    fun testDeductTimeDecreasesBalanceAndIncrementsRevision() = runBlocking {
        val repository = PaymentRepository(db = db, isEligible = { true })
        val initial = repository.adjustSessionTime(1200)
        assertEquals(1L, initial.revision)

        val updated = repository.deductTime(300)
        assertTrue("Time remaining should decrease", updated.sessionTimeRemaining <= 900)
        assertEquals("Revision must increment on deduction", 2L, updated.revision)

        val persisted = repository.getSessionState()
        assertEquals(updated.sessionTimeRemaining, persisted?.sessionTimeRemaining)
        assertEquals(2L, persisted?.revision)
    }

    @Test
    fun testExpireSessionResetsBalanceAndIncrementsRevision() = runBlocking {
        val repository = PaymentRepository(db = db, isEligible = { true })
        val initial = repository.adjustSessionTime(1200)
        assertEquals(1L, initial.revision)

        val expired = repository.expireSession()
        assertEquals(0, expired.sessionTimeRemaining)
        assertEquals(0L, expired.sessionExpiryDeadlineMs)
        assertEquals(2L, expired.revision)

        val persisted = repository.getSessionState()
        assertEquals(0, persisted?.sessionTimeRemaining)
        assertEquals(0L, persisted?.sessionExpiryDeadlineMs)
        assertEquals(2L, persisted?.revision)
    }

    @Test
    fun testConditionalExpiryKeepsNewlyPurchasedTimeWhenTimerReadsExpiredDeadline() = runBlocking {
        val repository = PaymentRepository(db = db, isEligible = { true })
        val now = SystemClock.elapsedRealtime()

        // 1. Initial state: expired deadline in the past
        val expiredState = PaidSessionState(
            id = 1,
            sessionTimeRemaining = 0,
            sessionExpiryDeadlineMs = now - 5000L,
            lastSavedElapsedRealtime = now - 6000L,
            revision = 1L
        )
        db.paymentDao().updateSessionState(expiredState)

        // 2. Timer reads the expired deadline snapshot
        val timerObservedDeadline = expiredState.sessionExpiryDeadlineMs
        assertTrue("Timer observes expired deadline", timerObservedDeadline <= now)

        // 3. Concurrently, a new payment is committed
        val paymentResult = repository.creditPayment("tx-new-topup", 600, 5.0)
        assertEquals(PaymentResult.APPLIED, paymentResult)

        val stateAfterPayment = repository.getSessionState()!!
        assertTrue("State deadline extended into future", stateAfterPayment.sessionExpiryDeadlineMs > now)
        assertEquals(2L, stateAfterPayment.revision)

        // 4. Timer's delayed handler runs expireSessionIfDue()
        val expiryResult = repository.expireSessionIfDue()
        assertFalse("Expiration must NOT happen because DB deadline was extended", expiryResult.didExpire)
        assertEquals("Newly purchased deadline must remain", stateAfterPayment.sessionExpiryDeadlineMs, expiryResult.sessionState.sessionExpiryDeadlineMs)
        assertTrue("Newly purchased time remaining must remain", expiryResult.sessionState.sessionTimeRemaining > 0)
    }

    @Test
    fun testCaptureCheckpointDeductionResetConflictRejectsStaleCheckpoint() = runBlocking {
        val repository = PaymentRepository(db = db, isEligible = { true })
        repository.adjustSessionTime(1000)
        val stateInitial = repository.getSessionState()!!
        val capturedRevision = stateInitial.revision
        assertEquals(1L, capturedRevision)

        // Admin resets or deducts time
        repository.expireSession()
        val stateAfterReset = repository.getSessionState()!!
        assertEquals(0, stateAfterReset.sessionTimeRemaining)
        assertEquals(0L, stateAfterReset.sessionExpiryDeadlineMs)
        assertEquals(2L, stateAfterReset.revision)

        // Stale timer tries to submit checkpoint with capturedRevision (1)
        repository.checkpointSession(capturedRevision)

        val stateAfterStaleCheckpoint = repository.getSessionState()!!
        assertEquals("Stale checkpoint must be ignored; balance remains 0", 0, stateAfterStaleCheckpoint.sessionTimeRemaining)
        assertEquals("Authoritative deadline remains 0", 0L, stateAfterStaleCheckpoint.sessionExpiryDeadlineMs)
        assertEquals("Revision remains 2", 2L, stateAfterStaleCheckpoint.revision)
    }

    @Test
    fun testSerializedStateUpdateRetainsNewerRevisionWhenDeliveredInReverseOrder() {
        val stateManager = KioskStateManager(context)
        val now = SystemClock.elapsedRealtime()

        val snapshot1 = SessionSnapshot(deadlineMs = now + 300_000L, remainingSeconds = 300, revision = 1L)
        val snapshot2 = SessionSnapshot(deadlineMs = now + 600_000L, remainingSeconds = 600, revision = 2L)

        // Apply snapshot 2 first
        val applied2 = stateManager.applySessionUpdate(snapshot2)
        assertTrue("Newer snapshot applied", applied2)
        assertEquals(2L, stateManager.sessionRevision.value)
        assertEquals(600, stateManager.sessionTimeRemaining.value)

        // Apply snapshot 1 in reverse order
        val applied1 = stateManager.applySessionUpdate(snapshot1)
        assertFalse("Older snapshot must be rejected", applied1)
        assertEquals("State manager must retain newer revision 2", 2L, stateManager.sessionRevision.value)
        assertEquals("State manager must retain newer time 600s", 600, stateManager.sessionTimeRemaining.value)
    }

    @Test
    fun testLegacyMigrationWithRealKeysAndSameBoot() = runBlocking {
        val deviceContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
        val prefs = deviceContext.getSharedPreferences(PaymentRepository.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val nowMonotonic = SystemClock.elapsedRealtime()
        val expectedRemaining = 900
        val expectedDeadline = nowMonotonic + (expectedRemaining * 1000L)
        val lastSavedElapsed = nowMonotonic - 5000L

        prefs.edit()
            .putInt("session_time_remaining", expectedRemaining)
            .putLong("session_expiry_deadline_ms", expectedDeadline)
            .putLong("last_saved_elapsed_realtime", lastSavedElapsed)
            .commit()

        val repository = PaymentRepository(db = db, context = context, isEligible = { true })
        repository.migrateAndInitialize(context)

        val state = repository.getSessionState()
        assertNotNull("Session state must exist in Room after migration", state)
        assertTrue("Session time remaining must match imported value", state!!.sessionTimeRemaining >= 895)
        assertEquals("Session deadline must match imported deadline", expectedDeadline, state.sessionExpiryDeadlineMs)
        assertEquals(1L, state.revision)

        val metaMarker = db.paymentDao().getMetadata(PaymentRepository.KEY_MIGRATION_MARKER)
        assertEquals("Migration marker must be recorded in Room metadata table", "true", metaMarker)
    }

    @Test
    fun testLegacyMigrationExpiredDeadlineRestoresZero() = runBlocking {
        val deviceContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
        val prefs = deviceContext.getSharedPreferences(PaymentRepository.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val startElapsed = SystemClock.elapsedRealtime().coerceAtLeast(1000L)
        val legacyRemaining = 300
        val expiredDeadline = startElapsed + 5000L
        val lastSavedElapsed = startElapsed

        // Advance clock within the same boot so that deadline is in the past
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(10))

        prefs.edit()
            .putInt("session_time_remaining", legacyRemaining)
            .putLong("session_expiry_deadline_ms", expiredDeadline)
            .putLong("last_saved_elapsed_realtime", lastSavedElapsed)
            .commit()

        val repository = PaymentRepository(db = db, context = context, isEligible = { true })
        repository.migrateAndInitialize(context)

        val state = repository.getSessionState()
        assertNotNull(state)
        assertEquals("Expired legacy deadline must restore zero remaining time", 0, state!!.sessionTimeRemaining)
        assertEquals("Expired legacy deadline must restore zero deadline", 0L, state.sessionExpiryDeadlineMs)
    }

    @Test
    fun testLegacyMigrationAfterRebootRecoversUsingSavedRemainingTime() = runBlocking {
        val deviceContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
        val prefs = deviceContext.getSharedPreferences(PaymentRepository.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val nowMonotonic = SystemClock.elapsedRealtime()
        val legacyRemaining = 500
        val oldBootDeadline = 99999999L
        val lastSavedElapsedFromPrevBoot = nowMonotonic + 500000L // Larger than current elapsedRealtime => reboot detected

        prefs.edit()
            .putInt("session_time_remaining", legacyRemaining)
            .putLong("session_expiry_deadline_ms", oldBootDeadline)
            .putLong("last_saved_elapsed_realtime", lastSavedElapsedFromPrevBoot)
            .commit()

        val repository = PaymentRepository(db = db, context = context, isEligible = { true })
        repository.migrateAndInitialize(context)

        val state = repository.getSessionState()
        assertNotNull(state)
        assertEquals("Reboot recovery must preserve saved remaining time", legacyRemaining, state!!.sessionTimeRemaining)
        assertTrue("Reboot recovery must compute new monotonic deadline", state.sessionExpiryDeadlineMs >= nowMonotonic + (legacyRemaining * 1000L) - 1000L)
    }

    @Test
    fun testAuthoritativeRoomStateNotOverwrittenByLegacyPrefs() = runBlocking {
        val deviceContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
        val prefs = deviceContext.getSharedPreferences(PaymentRepository.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("session_time_remaining", 1000)
            .putLong("session_expiry_deadline_ms", SystemClock.elapsedRealtime() + 1000_000L)
            .commit()

        val nowMonotonic = SystemClock.elapsedRealtime()
        val existingZeroState = PaidSessionState(
            id = 1,
            sessionTimeRemaining = 0,
            sessionExpiryDeadlineMs = 0L,
            lastSavedElapsedRealtime = nowMonotonic,
            revision = 3L
        )
        db.paymentDao().updateSessionState(existingZeroState)

        val repository = PaymentRepository(db = db, context = context, isEligible = { true })
        repository.migrateAndInitialize(context)

        val state = repository.getSessionState()
        assertNotNull(state)
        assertEquals("Existing zero balance in Room must be preserved", 0, state!!.sessionTimeRemaining)
        assertEquals("Existing zero deadline in Room must be preserved", 0L, state.sessionExpiryDeadlineMs)
        assertEquals("Existing revision must be preserved", 3L, state.revision)
    }

    @Test
    fun testRetryInitializationDoesNotDuplicateCreditOrOverwriteBalance() = runBlocking {
        val deviceContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
        val prefs = deviceContext.getSharedPreferences(PaymentRepository.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("session_time_remaining", 300)
            .putLong("session_expiry_deadline_ms", SystemClock.elapsedRealtime() + 300_000L)
            .putStringSet("processed_tx_ids", setOf("tx-legacy-1"))
            .commit()

        val repository = PaymentRepository(db = db, context = context, isEligible = { true })
        repository.migrateAndInitialize(context)

        // New payment added after initial migration
        repository.creditPayment("tx-new-2", 600, 5.0)
        val stateAfterCredit = repository.getSessionState()!!
        assertTrue(stateAfterCredit.sessionTimeRemaining >= 890)

        // Retry migrateAndInitialize
        repository.migrateAndInitialize(context)

        val stateAfterRetry = repository.getSessionState()!!
        assertEquals("Balance must not be overwritten on initialization retry", stateAfterCredit.sessionTimeRemaining, stateAfterRetry.sessionTimeRemaining)
        assertNotNull(db.paymentDao().getReceiptByTxId("tx-legacy-1"))
        assertNotNull(db.paymentDao().getReceiptByTxId("tx-new-2"))
    }

    @Test
    fun testCreditPublishedAfterExpirationCommitBeforeUiHandlerKeepsUnlockedStateAndNewCredit() = runBlocking {
        val stateManager = KioskStateManager(context)
        val repository = PaymentRepository(db = db, isEligible = { true })

        // 1. Start an initial session that expires
        repository.adjustSessionTime(10)
        val initialSession = repository.getSessionState()!!
        assertEquals(1L, initialSession.revision)
        stateManager.applySessionUpdate(
            deadlineMs = initialSession.sessionExpiryDeadlineMs,
            remainingSeconds = initialSession.sessionTimeRemaining,
            revision = initialSession.revision,
            targetAppState = 2 // Unlocked
        )
        assertEquals(2, stateManager.appState.value)

        // Advance monotonic clock so deadline is expired
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(15))

        // 2. Expiration commits revision 2 in Room
        val expiryResult = repository.expireSessionIfDue()
        assertTrue("Expiration should commit", expiryResult.didExpire)
        assertEquals(2L, expiryResult.sessionState.revision)
        assertEquals(0, expiryResult.sessionState.sessionTimeRemaining)
        val delayedExpiryState = expiryResult.sessionState

        // 3. Before the UI handler for expiration runs, a new payment commits in Room and publishes revision 3
        val paymentResult = repository.creditPayment("tx-race-1", 600, 5.0)
        assertEquals(PaymentResult.APPLIED, paymentResult)
        val paymentState = repository.getSessionState()!!
        assertEquals(3L, paymentState.revision)
        assertTrue(paymentState.sessionTimeRemaining >= 595)

        // Payment handler publishes state update with targetAppState = 2 (unlocked)
        val appliedPayment = stateManager.applySessionUpdate(
            deadlineMs = paymentState.sessionExpiryDeadlineMs,
            remainingSeconds = paymentState.sessionTimeRemaining,
            revision = paymentState.revision,
            targetAppState = 2
        )
        assertTrue("Payment update must be applied", appliedPayment)
        assertEquals(3L, stateManager.sessionRevision.value)
        assertEquals(paymentState.sessionTimeRemaining, stateManager.sessionTimeRemaining.value)
        assertEquals(2, stateManager.appState.value)

        // 4. Delayed expiration UI handler now runs with delayedExpiryState (rev 2, targetAppState = 0)
        var announcedWarning = false
        var lockedScreen = false
        val appliedExpiry = stateManager.applySessionUpdate(
            deadlineMs = delayedExpiryState.sessionExpiryDeadlineMs,
            remainingSeconds = delayedExpiryState.sessionTimeRemaining,
            revision = delayedExpiryState.revision,
            targetAppState = 0
        )
        if (appliedExpiry) {
            announcedWarning = true
            lockedScreen = true
        }

        // 5. Verify the stale expiration was rejected and phone remains unlocked with new balance
        assertFalse("Stale expiration update must be rejected", appliedExpiry)
        assertFalse("Time expired announcement must NOT be triggered", announcedWarning)
        assertFalse("Screen must NOT be locked", lockedScreen)
        assertEquals("Session revision remains 3L", 3L, stateManager.sessionRevision.value)
        assertEquals("Session time remaining remains the purchased time", paymentState.sessionTimeRemaining, stateManager.sessionTimeRemaining.value)
        assertEquals("App state remains unlocked (2)", 2, stateManager.appState.value)
    }
}


