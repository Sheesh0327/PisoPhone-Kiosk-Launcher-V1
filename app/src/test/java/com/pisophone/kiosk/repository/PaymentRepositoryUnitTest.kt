package com.pisophone.kiosk.repository

import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pisophone.kiosk.db.AppDatabase
import com.pisophone.kiosk.db.PaidSessionState
import com.pisophone.kiosk.db.PaymentReceipt
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

    @Test
    fun testRebootRecoveryRebuildsSessionDeadlineUsingElapsedRealtime() = runBlocking {
        val nowMonotonic = SystemClock.elapsedRealtime()
        val prevBootElapsed = nowMonotonic + 1000_000L // Larger than current -> indicates reboot
        val savedRemaining = 300

        db.paymentDao().updateSessionState(
            PaidSessionState(
                id = 1,
                sessionTimeRemaining = savedRemaining,
                sessionExpiryDeadlineMs = prevBootElapsed + 300_000L,
                lastSavedElapsedRealtime = prevBootElapsed,
                revision = 10L
            )
        )

        val repository = PaymentRepository(db = db, context = context, isEligible = { true })
        val restored = repository.restoreSessionState(context)

        assertTrue("Reboot must be detected", restored.isReboot)
        val expectedRemaining = maxOf(0, savedRemaining)
        assertEquals("Remaining time must equal saved time exactly after reboot", expectedRemaining, restored.remainingSeconds)
        assertEquals("Monotonic deadline must be now + remainingMs", nowMonotonic + (expectedRemaining * 1000L), restored.deadlineMs)
        assertEquals(11L, restored.revision)
    }

    @Test
    fun testBootCountChangeTriggersRebootRecovery() = runBlocking {
        val encryptedPrefs = com.pisophone.kiosk.security.KioskSecurity.getEncryptedPreferences(context)
        encryptedPrefs.edit().putInt(PaymentRepository.KEY_BOOT_COUNT, 1).commit()

        val nowMonotonic = SystemClock.elapsedRealtime()
        // lastSavedElapsed is LESS than nowMonotonic so monotonic check alone would NOT detect reboot
        val lastSavedElapsed = maxOf(1L, nowMonotonic - 5000L)
        val savedRemaining = 600

        db.paymentDao().updateSessionState(
            PaidSessionState(
                id = 1,
                sessionTimeRemaining = savedRemaining,
                sessionExpiryDeadlineMs = nowMonotonic + 600_000L,
                lastSavedElapsedRealtime = lastSavedElapsed,
                revision = 5L
            )
        )

        // Simulate new boot count by updating Settings.Global.BOOT_COUNT or synthetic boot count
        android.provider.Settings.Global.putInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 2)

        val repository = PaymentRepository(db = db, context = context, isEligible = { true })
        val restored = repository.restoreSessionState(context)

        assertTrue("Reboot must be detected via BOOT_COUNT change", restored.isReboot)
        val expectedRemaining = maxOf(0, savedRemaining)
        assertEquals(expectedRemaining, restored.remainingSeconds)
        assertEquals(6L, restored.revision)
        assertEquals("2", db.paymentDao().getMetadata(PaymentRepository.KEY_BOOT_COUNT))
    }

    @Test
    fun testRecoverUncommittedTransactionsSanitizesCorruptState() = runBlocking {
        db.paymentDao().updateSessionState(
            PaidSessionState(
                id = 1,
                sessionTimeRemaining = -10,
                sessionExpiryDeadlineMs = -500L,
                lastSavedElapsedRealtime = 1000L,
                revision = 1L
            )
        )

        val repository = PaymentRepository(db = db, context = context, isEligible = { true })
        repository.recoverUncommittedTransactions()

        // Verify corrupt session state was safely reverted to 0
        val sanitizedState = repository.getSessionState()!!
        assertEquals("Negative remaining time must be sanitized to 0", 0, sanitizedState.sessionTimeRemaining)
        assertEquals("Negative deadline must be sanitized to 0L", 0L, sanitizedState.sessionExpiryDeadlineMs)
        assertEquals(2L, sanitizedState.revision)
    }

    @Test
    fun testCreditPaymentStoresAllAuditFieldsImmutably() = runBlocking {
        val repository = PaymentRepository(db = db, isEligible = { true })
        val txId = "tx-audit-100"
        val result = repository.creditPayment(
            txId = txId,
            seconds = 1800,
            amount = 5.0,
            operationKind = "COIN",
            coinAmount = 1,
            pricePerCoin = 5.0,
            boxInstallationEpoch = 1710000000L,
            phonePairingEpoch = 1710050000L
        )

        assertEquals(PaymentResult.APPLIED, result)

        val receipt = db.paymentDao().getReceiptByTxId(txId)
        assertNotNull("Receipt must be stored", receipt)
        assertEquals(txId, receipt?.txId)
        assertEquals(1800, receipt?.secondsCredited)
        assertEquals(5.0, receipt?.amount ?: 0.0, 0.001)
        assertEquals("COIN", receipt?.operationKind)
        assertEquals(1, receipt?.coinAmount)
        assertEquals(5.0, receipt?.pricePerCoin ?: 0.0, 0.001)
        assertEquals(1710000000L, receipt?.boxInstallationEpoch)
        assertEquals(1710050000L, receipt?.phonePairingEpoch)
        assertEquals(PaymentReceipt.CURRENT_RECORD_SCHEMA_VERSION, receipt?.recordSchemaVersion)

        // Duplicate identical submission returns ALREADY_APPLIED
        val dupResult = repository.creditPayment(
            txId = txId,
            seconds = 1800,
            amount = 5.0,
            operationKind = "COIN",
            coinAmount = 1,
            pricePerCoin = 5.0,
            boxInstallationEpoch = 1710000000L,
            phonePairingEpoch = 1710050000L
        )
        assertEquals(PaymentResult.ALREADY_APPLIED, dupResult)
    }

    @Test
    fun testAuditFieldConflictsRejectedWithConflict() = runBlocking {
        val repository = PaymentRepository(db = db, isEligible = { true })
        val txId = "tx-conflict-audit"
        val res1 = repository.creditPayment(
            txId = txId,
            seconds = 600,
            amount = 1.0,
            operationKind = "COIN",
            coinAmount = 1,
            pricePerCoin = 1.0,
            boxInstallationEpoch = 1000L,
            phonePairingEpoch = 2000L
        )
        assertEquals(PaymentResult.APPLIED, res1)

        // Conflicting operation kind
        val resKind = repository.creditPayment(
            txId = txId,
            seconds = 600,
            amount = 1.0,
            operationKind = "MANUAL_ADJUSTMENT"
        )
        assertEquals("Conflicting operationKind must return CONFLICT", PaymentResult.CONFLICT, resKind)

        // Conflicting coin amount
        val resCoin = repository.creditPayment(
            txId = txId,
            seconds = 600,
            amount = 1.0,
            coinAmount = 5
        )
        assertEquals("Conflicting coinAmount must return CONFLICT", PaymentResult.CONFLICT, resCoin)

        // Conflicting pricePerCoin
        val resPrice = repository.creditPayment(
            txId = txId,
            seconds = 600,
            amount = 1.0,
            pricePerCoin = 5.0
        )
        assertEquals("Conflicting pricePerCoin must return CONFLICT", PaymentResult.CONFLICT, resPrice)

        // Conflicting epochs
        val resEpoch = repository.creditPayment(
            txId = txId,
            seconds = 600,
            amount = 1.0,
            boxInstallationEpoch = 9999L
        )
        assertEquals("Conflicting boxInstallationEpoch must return CONFLICT", PaymentResult.CONFLICT, resEpoch)
    }

    @Test
    fun testDeductPaymentAppliedAndAuditFieldsPersisted() = runBlocking {
        val repository = PaymentRepository(db = db, isEligible = { true })
        // Add initial balance
        repository.creditPayment("tx-initial", 1800, 5.0)

        val txId = "tx-deduct-1"
        val result = repository.deductPayment(
            txId = txId,
            seconds = 600,
            operationKind = "MATCH_TRANSFER_DEDUCT",
            boxInstallationEpoch = 1000L,
            phonePairingEpoch = 2000L
        )
        assertEquals(PaymentResult.APPLIED, result)

        val receipt = db.paymentDao().getReceiptByTxId(txId)
        assertNotNull(receipt)
        assertEquals(txId, receipt?.txId)
        assertEquals(-600, receipt?.secondsCredited)
        assertEquals("MATCH_TRANSFER_DEDUCT", receipt?.operationKind)
        assertEquals(0, receipt?.coinAmount)

        // Duplicate returns ALREADY_APPLIED
        val dupResult = repository.deductPayment(
            txId = txId,
            seconds = 600,
            operationKind = "MATCH_TRANSFER_DEDUCT",
            boxInstallationEpoch = 1000L,
            phonePairingEpoch = 2000L
        )
        assertEquals(PaymentResult.ALREADY_APPLIED, dupResult)

        // Conflict returns CONFLICT
        val conflictResult = repository.deductPayment(
            txId = txId,
            seconds = 300
        )
        assertEquals(PaymentResult.CONFLICT, conflictResult)
    }

    @Test
    fun testTwoPhoneMatchTransferFullSuccess() = runBlocking {
        val phone1Db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val phone2Db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()

        val p1Repo = PaymentRepository(db = phone1Db, isEligible = { true })
        val p2Repo = PaymentRepository(db = phone2Db, isEligible = { true })

        // P1 has 1200 seconds initially
        p1Repo.creditPayment("tx-p1-seed", 1200, 5.0)

        val matchId = "match-test-100"
        val transferSeconds = 300
        val deductTxId = "$matchId-deduct"
        val creditTxId = "$matchId-credit"

        // Execute distinct operations on two separate databases
        val deductRes = p1Repo.deductPayment(
            txId = deductTxId,
            seconds = transferSeconds,
            operationKind = "MATCH_TRANSFER_DEDUCT",
            boxInstallationEpoch = 1000L,
            phonePairingEpoch = 2000L
        )
        val creditRes = p2Repo.creditPayment(
            txId = creditTxId,
            seconds = transferSeconds,
            amount = 0.0,
            operationKind = "MATCH_TRANSFER_CREDIT",
            coinAmount = 0,
            pricePerCoin = 0.0,
            boxInstallationEpoch = 1000L,
            phonePairingEpoch = 2000L
        )

        val outcome = p1Repo.evaluateMatchTransfer(
            matchId = matchId,
            sourceDeviceId = "p1",
            targetDeviceId = "p2",
            stakeSeconds = transferSeconds,
            deductTxId = deductTxId,
            creditTxId = creditTxId,
            deductResult = deductRes,
            creditResult = creditRes
        )

        assertEquals(PaymentResult.APPLIED, outcome.deductResult)
        assertEquals(PaymentResult.APPLIED, outcome.creditResult)
        assertTrue("Transfer must be complete", outcome.isComplete)
        assertFalse("Transfer must not be partial", outcome.isPartial)

        phone1Db.close()
        phone2Db.close()
    }

    @Test
    fun testTwoPhoneMatchTransferPartialDebitFailed() = runBlocking {
        val phone1Db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val phone2Db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()

        // P1 is not eligible to debit/credit
        val p1Repo = PaymentRepository(db = phone1Db, isEligible = { false })
        val p2Repo = PaymentRepository(db = phone2Db, isEligible = { true })

        val matchId = "match-test-200"
        val transferSeconds = 300
        val deductTxId = "$matchId-deduct"
        val creditTxId = "$matchId-credit"

        val deductRes = p1Repo.deductPayment(
            txId = deductTxId,
            seconds = transferSeconds,
            operationKind = "MATCH_TRANSFER_DEDUCT"
        )
        val creditRes = p2Repo.creditPayment(
            txId = creditTxId,
            seconds = transferSeconds,
            amount = 0.0,
            operationKind = "MATCH_TRANSFER_CREDIT"
        )

        val outcome = p1Repo.evaluateMatchTransfer(
            matchId = matchId,
            sourceDeviceId = "p1",
            targetDeviceId = "p2",
            stakeSeconds = transferSeconds,
            deductTxId = deductTxId,
            creditTxId = creditTxId,
            deductResult = deductRes,
            creditResult = creditRes
        )

        assertEquals(PaymentResult.NOT_ELIGIBLE, outcome.deductResult)
        assertEquals(PaymentResult.APPLIED, outcome.creditResult)
        assertFalse(outcome.isComplete)
        assertTrue("Must be marked as partial when one phone fails", outcome.isPartial)

        phone1Db.close()
        phone2Db.close()
    }

    @Test
    fun testTwoPhoneMatchTransferPartialCreditFailed() = runBlocking {
        val phone1Db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val phone2Db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()

        val p1Repo = PaymentRepository(db = phone1Db, isEligible = { true })
        // P2 is not eligible
        val p2Repo = PaymentRepository(db = phone2Db, isEligible = { false })

        val matchId = "match-test-300"
        val transferSeconds = 300
        val deductTxId = "$matchId-deduct"
        val creditTxId = "$matchId-credit"

        val deductRes = p1Repo.deductPayment(
            txId = deductTxId,
            seconds = transferSeconds,
            operationKind = "MATCH_TRANSFER_DEDUCT"
        )
        val creditRes = p2Repo.creditPayment(
            txId = creditTxId,
            seconds = transferSeconds,
            amount = 0.0,
            operationKind = "MATCH_TRANSFER_CREDIT"
        )

        val outcome = p1Repo.evaluateMatchTransfer(
            matchId = matchId,
            sourceDeviceId = "p1",
            targetDeviceId = "p2",
            stakeSeconds = transferSeconds,
            deductTxId = deductTxId,
            creditTxId = creditTxId,
            deductResult = deductRes,
            creditResult = creditRes
        )

        assertEquals(PaymentResult.APPLIED, outcome.deductResult)
        assertEquals(PaymentResult.NOT_ELIGIBLE, outcome.creditResult)
        assertFalse(outcome.isComplete)
        assertTrue("Must be marked as partial when one phone fails", outcome.isPartial)

        phone1Db.close()
        phone2Db.close()
    }

    @Test
    fun testTwoPhoneMatchTransferIdempotentRetry() = runBlocking {
        val phone1Db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val phone2Db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()

        val p1Repo = PaymentRepository(db = phone1Db, isEligible = { true })
        val p2Repo = PaymentRepository(db = phone2Db, isEligible = { true })

        val matchId = "match-test-400"
        val transferSeconds = 300
        val deductTxId = "$matchId-deduct"
        val creditTxId = "$matchId-credit"

        val deduct1 = p1Repo.deductPayment(txId = deductTxId, seconds = transferSeconds)
        val credit1 = p2Repo.creditPayment(txId = creditTxId, seconds = transferSeconds, amount = 0.0)
        val outcome1 = p1Repo.evaluateMatchTransfer(
            matchId, "p1", "p2", transferSeconds, deductTxId, creditTxId, deduct1, credit1
        )
        assertTrue(outcome1.isComplete)

        // Retry with same IDs
        val deduct2 = p1Repo.deductPayment(txId = deductTxId, seconds = transferSeconds)
        val credit2 = p2Repo.creditPayment(txId = creditTxId, seconds = transferSeconds, amount = 0.0)
        assertEquals(PaymentResult.ALREADY_APPLIED, deduct2)
        assertEquals(PaymentResult.ALREADY_APPLIED, credit2)

        val outcome2 = p1Repo.evaluateMatchTransfer(
            matchId, "p1", "p2", transferSeconds, deductTxId, creditTxId, deduct2, credit2
        )
        assertTrue("Retried complete transfer remains complete", outcome2.isComplete)
        assertFalse(outcome2.isPartial)

        phone1Db.close()
        phone2Db.close()
    }

    @Test
    fun testOnDiskV4DatabaseMigrationAndProductionRepository() = runBlocking {
        val dbFile = context.getDatabasePath("test_v4_ondisk.db")
        if (dbFile.exists()) {
            dbFile.delete()
        }

        // 1. Create a real v4 SQLite database file on disk and seed v4 receipts & session balance
        val rawDb = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        rawDb.execSQL("CREATE TABLE IF NOT EXISTS `coin_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `txId` TEXT NOT NULL, `secondsAdded` INTEGER NOT NULL, `source` TEXT NOT NULL, `timestamp` INTEGER NOT NULL);")
        rawDb.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_coin_events_txId` ON `coin_events` (`txId`);")
        rawDb.execSQL("CREATE TABLE IF NOT EXISTS `payment_receipts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `txId` TEXT NOT NULL, `secondsCredited` INTEGER NOT NULL, `amount` REAL NOT NULL, `acceptanceTimestamp` INTEGER NOT NULL);")
        rawDb.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_payment_receipts_txId` ON `payment_receipts` (`txId`);")
        rawDb.execSQL("CREATE TABLE IF NOT EXISTS `paid_session_state` (`id` INTEGER NOT NULL, `sessionTimeRemaining` INTEGER NOT NULL, `sessionExpiryDeadlineMs` INTEGER NOT NULL, `lastSavedElapsedRealtime` INTEGER NOT NULL, `revision` INTEGER NOT NULL, PRIMARY KEY(`id`));")
        rawDb.execSQL("CREATE TABLE IF NOT EXISTS `app_metadata` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`));")
        rawDb.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT);")
        rawDb.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, 'cb710ab4ae7aa7cddb70934f06260d43');")
        rawDb.execSQL("PRAGMA user_version = 4;")

        // Seed legacy v4 receipts:
        // tx-old: +300s, amount=5.0
        // adj-old: -60s, amount=0.0
        rawDb.execSQL("INSERT INTO `payment_receipts` (`txId`, `secondsCredited`, `amount`, `acceptanceTimestamp`) VALUES ('tx-old', 300, 5.0, 1700000000000);")
        rawDb.execSQL("INSERT INTO `payment_receipts` (`txId`, `secondsCredited`, `amount`, `acceptanceTimestamp`) VALUES ('adj-old', -60, 0.0, 1700000001000);")
        val nowMonotonic = SystemClock.elapsedRealtime()
        rawDb.execSQL("INSERT INTO `paid_session_state` (`id`, `sessionTimeRemaining`, `sessionExpiryDeadlineMs`, `lastSavedElapsedRealtime`, `revision`) VALUES (1, 300, ${nowMonotonic + 300000L}, $nowMonotonic, 1);")
        rawDb.close()

        // 2. Open via Room AppDatabase (executing real migrations 4 -> 5 -> 6)
        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, "test_v4_ondisk.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_1_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()

        val repository = PaymentRepository(db = migratedDb, context = context, isEligible = { true })

        // 3. Test production repository interactions on migrated legacy receipts
        // v4 receipt tx-old (+300s, amount 5.0): identical retry returns ALREADY_APPLIED
        val dupTxOld = repository.creditPayment(
            txId = "tx-old",
            seconds = 300,
            amount = 5.0,
            operationKind = "COIN",
            coinAmount = 5,
            pricePerCoin = 1.0,
            boxInstallationEpoch = 1000L,
            phonePairingEpoch = 2000L
        )
        assertEquals("Identical retry for legacy tx-old returns ALREADY_APPLIED", PaymentResult.ALREADY_APPLIED, dupTxOld)

        // Retrying tx-old with conflicting seconds returns CONFLICT
        val conflictTxOld = repository.creditPayment(
            txId = "tx-old",
            seconds = 600,
            amount = 5.0
        )
        assertEquals("Conflicting retry for legacy tx-old returns CONFLICT", PaymentResult.CONFLICT, conflictTxOld)

        // v4 receipt adj-old (-60s, amount 0.0): identical deduction retry returns ALREADY_APPLIED
        val dupAdjOld = repository.deductPayment(
            txId = "adj-old",
            seconds = 60,
            operationKind = "MANUAL_DEDUCTION",
            boxInstallationEpoch = 1000L
        )
        assertEquals("Identical retry for legacy adj-old returns ALREADY_APPLIED", PaymentResult.ALREADY_APPLIED, dupAdjOld)

        // Retrying adj-old with conflicting deduction seconds returns CONFLICT
        val conflictAdjOld = repository.deductPayment(
            txId = "adj-old",
            seconds = 120
        )
        assertEquals("Conflicting deduction retry for legacy adj-old returns CONFLICT", PaymentResult.CONFLICT, conflictAdjOld)

        migratedDb.close()
    }

    @Test
    fun testOnDiskV5RepairPathAndProductionRepository() = runBlocking {
        val dbFile = context.getDatabasePath("test_v5_ondisk.db")
        if (dbFile.exists()) {
            dbFile.delete()
        }

        // 1. Create a database simulating an existing shipped v5 installation where MIGRATION_4_5 ran
        val rawDb = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        rawDb.execSQL("CREATE TABLE IF NOT EXISTS `coin_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `txId` TEXT NOT NULL, `secondsAdded` INTEGER NOT NULL, `source` TEXT NOT NULL, `timestamp` INTEGER NOT NULL);")
        rawDb.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_coin_events_txId` ON `coin_events` (`txId`);")
        rawDb.execSQL("CREATE TABLE IF NOT EXISTS `payment_receipts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `txId` TEXT NOT NULL, `secondsCredited` INTEGER NOT NULL, `amount` REAL NOT NULL, `acceptanceTimestamp` INTEGER NOT NULL, `operationKind` TEXT NOT NULL, `coinAmount` INTEGER NOT NULL, `pricePerCoin` REAL NOT NULL, `boxInstallationEpoch` INTEGER NOT NULL, `phonePairingEpoch` INTEGER NOT NULL, `recordSchemaVersion` INTEGER NOT NULL);")
        rawDb.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_payment_receipts_txId` ON `payment_receipts` (`txId`);")
        rawDb.execSQL("CREATE TABLE IF NOT EXISTS `paid_session_state` (`id` INTEGER NOT NULL, `sessionTimeRemaining` INTEGER NOT NULL, `sessionExpiryDeadlineMs` INTEGER NOT NULL, `lastSavedElapsedRealtime` INTEGER NOT NULL, `revision` INTEGER NOT NULL, PRIMARY KEY(`id`));")
        rawDb.execSQL("CREATE TABLE IF NOT EXISTS `app_metadata` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`));")
        rawDb.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT);")
        rawDb.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, 'eeda8a76f8700216258d9bbe38c3d0cc');")
        rawDb.execSQL("PRAGMA user_version = 5;")

        // Seed legacy receipts with default v5 values (operationKind='COIN', coinAmount=0, recordSchemaVersion=1)
        rawDb.execSQL("INSERT INTO `payment_receipts` (`txId`, `secondsCredited`, `amount`, `acceptanceTimestamp`, `operationKind`, `coinAmount`, `pricePerCoin`, `boxInstallationEpoch`, `phonePairingEpoch`, `recordSchemaVersion`) VALUES ('tx-old-v5', 300, 5.0, 1700000000000, 'COIN', 0, 0.0, 0, 0, 1);")
        rawDb.execSQL("INSERT INTO `payment_receipts` (`txId`, `secondsCredited`, `amount`, `acceptanceTimestamp`, `operationKind`, `coinAmount`, `pricePerCoin`, `boxInstallationEpoch`, `phonePairingEpoch`, `recordSchemaVersion`) VALUES ('adj-old-v5', -60, 0.0, 1700000001000, 'COIN', 0, 0.0, 0, 0, 1);")
        val nowMonotonic = SystemClock.elapsedRealtime()
        rawDb.execSQL("INSERT INTO `paid_session_state` (`id`, `sessionTimeRemaining`, `sessionExpiryDeadlineMs`, `lastSavedElapsedRealtime`, `revision`) VALUES (1, 300, ${nowMonotonic + 300000L}, $nowMonotonic, 1);")
        rawDb.close()

        // 2. Open via Room AppDatabase v6 (triggers MIGRATION_5_6 forward repair migration)
        val repairedDb = Room.databaseBuilder(context, AppDatabase::class.java, "test_v5_ondisk.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_1_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()

        val repository = PaymentRepository(db = repairedDb, context = context, isEligible = { true })

        // 3. Verify repair path succeeded and production repository handles retries properly
        val dupCredit = repository.creditPayment(
            txId = "tx-old-v5",
            seconds = 300,
            amount = 5.0,
            operationKind = "COIN",
            coinAmount = 5,
            pricePerCoin = 1.0,
            boxInstallationEpoch = 1000L,
            phonePairingEpoch = 2000L
        )
        assertEquals("Repaired v5 installation returns ALREADY_APPLIED for credit retry", PaymentResult.ALREADY_APPLIED, dupCredit)

        val dupDeduct = repository.deductPayment(
            txId = "adj-old-v5",
            seconds = 60,
            operationKind = "MANUAL_DEDUCTION"
        )
        assertEquals("Repaired v5 installation returns ALREADY_APPLIED for deduction retry", PaymentResult.ALREADY_APPLIED, dupDeduct)

        // Conflicting retries still return CONFLICT
        val conflictCredit = repository.creditPayment(
            txId = "tx-old-v5",
            seconds = 900,
            amount = 5.0
        )
        assertEquals("Repaired v5 installation returns CONFLICT for conflicting credit retry", PaymentResult.CONFLICT, conflictCredit)

        repairedDb.close()
    }

    @Test
    fun testNewReceiptsStrictComparisonInSchema6() = runBlocking {
        val repository = PaymentRepository(db = db, isEligible = { true })

        // Apply new receipt in schema 6
        val txId = "tx-v6-new"
        val applyRes = repository.creditPayment(
            txId = txId,
            seconds = 600,
            amount = 5.0,
            operationKind = "COIN",
            coinAmount = 1,
            pricePerCoin = 5.0,
            boxInstallationEpoch = 1000L,
            phonePairingEpoch = 2000L
        )
        assertEquals(PaymentResult.APPLIED, applyRes)

        // Identical retry returns ALREADY_APPLIED
        val dupRes = repository.creditPayment(
            txId = txId,
            seconds = 600,
            amount = 5.0,
            operationKind = "COIN",
            coinAmount = 1,
            pricePerCoin = 5.0,
            boxInstallationEpoch = 1000L,
            phonePairingEpoch = 2000L
        )
        assertEquals(PaymentResult.ALREADY_APPLIED, dupRes)

        // Strict comparison: retry with zero boxInstallationEpoch must NOT act as a wildcard and must return CONFLICT
        val wildcardEpochRes = repository.creditPayment(
            txId = txId,
            seconds = 600,
            amount = 5.0,
            operationKind = "COIN",
            coinAmount = 1,
            pricePerCoin = 5.0,
            boxInstallationEpoch = 0L,
            phonePairingEpoch = 2000L
        )
        assertEquals("Zero epoch must NOT act as wildcard for new receipts; return CONFLICT", PaymentResult.CONFLICT, wildcardEpochRes)
    }
}


