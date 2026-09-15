package com.pisophone.kiosk.repository

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import com.pisophone.kiosk.db.AppDatabase
import com.pisophone.kiosk.db.AppMetadata
import com.pisophone.kiosk.db.PaidSessionState
import com.pisophone.kiosk.db.PaymentReceipt
import com.pisophone.kiosk.security.KioskActivationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Immutable snapshot of active paid session state.
 */
data class SessionSnapshot(
    val deadlineMs: Long,
    val remainingSeconds: Int,
    val revision: Long
)

/**
 * Result of restoring session state from local persistence.
 */
data class RestoredSessionState(
    val remainingSeconds: Int,
    val deadlineMs: Long,
    val isReboot: Boolean,
    val revision: Long = 0L
)

/**
 * Result of conditional session expiration inside Room.
 */
data class ExpiryResult(
    val didExpire: Boolean,
    val sessionState: PaidSessionState
)

/**
 * Repository acting as the single payment-processing and session-balance authority.
 */
class PaymentRepository(
    private val db: AppDatabase,
    private val context: Context? = null,
    private val isEligible: () -> Boolean = { true },
    private val onPaymentApplied: ((txId: String, seconds: Int, amount: Double, snapshot: SessionSnapshot) -> Unit)? = null,
    private val onSessionStateChanged: ((snapshot: SessionSnapshot) -> Unit)? = null
) {
    companion object {
        private const val TAG = "PaymentRepository"
        const val PREFS_NAME = "kiosk_persistent_state"
        const val KEY_MIGRATION_MARKER = "legacy_paid_state_migrated_v4"
        const val KEY_MIGRATION_MARKER_PREFS = "legacy_paid_state_migrated_v3"
    }

    constructor(
        db: AppDatabase,
        context: Context,
        onPaymentApplied: ((txId: String, seconds: Int, amount: Double, snapshot: SessionSnapshot) -> Unit)? = null,
        onSessionStateChanged: ((snapshot: SessionSnapshot) -> Unit)? = null
    ) : this(
        db = db,
        context = context,
        isEligible = { KioskActivationManager.isAppAllowedToRun(context) },
        onPaymentApplied = onPaymentApplied,
        onSessionStateChanged = onSessionStateChanged
    )

    private val paymentDao = db.paymentDao()

    suspend fun creditPayment(txId: String, seconds: Int, amount: Double): PaymentResult {
        var committedSnapshot: SessionSnapshot? = null

        val result = try {
            db.withTransaction {
                val existing = paymentDao.getReceiptByTxId(txId)
                if (existing != null) {
                    val isLegacyPlaceholder = existing.secondsCredited == 0 && Math.abs(existing.amount - 0.0) < 0.0001
                    val isIdentical = (existing.secondsCredited == seconds && Math.abs(existing.amount - amount) < 0.0001) ||
                            (existing.secondsCredited == seconds && Math.abs(existing.amount - 0.0) < 0.0001) ||
                            isLegacyPlaceholder
                    if (isIdentical) {
                        return@withTransaction PaymentResult.ALREADY_APPLIED
                    } else {
                        Log.w(
                            TAG,
                            "Transaction ID conflict for $txId: existing=(s=${existing.secondsCredited}, a=${existing.amount}) vs new=(s=$seconds, a=$amount)"
                        )
                        return@withTransaction PaymentResult.CONFLICT
                    }
                }

                if (!isEligible()) {
                    Log.w(TAG, "Payment rejected for $txId: Device/slot is not currently eligible.")
                    return@withTransaction PaymentResult.NOT_ELIGIBLE
                }

                val currentState = paymentDao.getSessionState()
                val nowMonotonic = SystemClock.elapsedRealtime()
                val currentDeadline = currentState?.sessionExpiryDeadlineMs ?: 0L
                val newDeadline = if (currentDeadline > nowMonotonic) {
                    currentDeadline + (seconds * 1000L)
                } else {
                    nowMonotonic + (seconds * 1000L)
                }
                val newSessionTime = ((newDeadline - nowMonotonic) / 1000L).toInt()
                val newRevision = (currentState?.revision ?: 0L) + 1L

                val receipt = PaymentReceipt(
                    txId = txId,
                    secondsCredited = seconds,
                    amount = amount,
                    acceptanceTimestamp = System.currentTimeMillis()
                )
                val newState = PaidSessionState(
                    id = 1,
                    sessionTimeRemaining = newSessionTime,
                    sessionExpiryDeadlineMs = newDeadline,
                    lastSavedElapsedRealtime = nowMonotonic,
                    revision = newRevision
                )

                paymentDao.insertReceipt(receipt)
                paymentDao.updateSessionState(newState)

                committedSnapshot = SessionSnapshot(newDeadline, newSessionTime, newRevision)
                PaymentResult.APPLIED
            }
        } catch (e: Exception) {
            Log.e(TAG, "Database transaction failed for txId $txId: ${e.message}", e)
            PaymentResult.FAILED
        }

        if (result == PaymentResult.APPLIED) {
            val snapshot = committedSnapshot ?: SessionSnapshot(0L, 0, 0L)
            onPaymentApplied?.invoke(txId, seconds, amount, snapshot)
            onSessionStateChanged?.invoke(snapshot)
        }

        return result
    }

    fun creditPaymentBlocking(txId: String, seconds: Int, amount: Double): PaymentResult =
        runBlocking(Dispatchers.IO) {
            creditPayment(txId, seconds, amount)
        }

    suspend fun deductTime(secondsDelta: Int): PaidSessionState {
        val updatedState = db.withTransaction {
            val currentState = paymentDao.getSessionState()
            val nowMonotonic = SystemClock.elapsedRealtime()
            val curDeadline = currentState?.sessionExpiryDeadlineMs ?: 0L
            val deductMs = if (secondsDelta > 0) secondsDelta * 1000L else -secondsDelta * 1000L
            val newDeadline = if (curDeadline > nowMonotonic) {
                maxOf(nowMonotonic, curDeadline - deductMs)
            } else {
                0L
            }
            val remaining = if (newDeadline > nowMonotonic) {
                ((newDeadline - nowMonotonic) / 1000L).toInt()
            } else {
                0
            }
            val effectiveDeadline = if (remaining > 0) newDeadline else 0L
            val newRevision = (currentState?.revision ?: 0L) + 1L
            val newState = PaidSessionState(
                id = 1,
                sessionTimeRemaining = remaining,
                sessionExpiryDeadlineMs = effectiveDeadline,
                lastSavedElapsedRealtime = nowMonotonic,
                revision = newRevision
            )
            paymentDao.updateSessionState(newState)
            newState
        }
        onSessionStateChanged?.invoke(
            SessionSnapshot(updatedState.sessionExpiryDeadlineMs, updatedState.sessionTimeRemaining, updatedState.revision)
        )
        return updatedState
    }

    fun deductTimeBlocking(secondsDelta: Int): PaidSessionState = runBlocking(Dispatchers.IO) {
        deductTime(secondsDelta)
    }

    /**
     * Unconditionally terminates the active paid session by committing 0 remaining time and deadline in Room.
     * Reserved for explicit administrative actions.
     */
    suspend fun expireSession(): PaidSessionState {
        val newState = db.withTransaction {
            val currentState = paymentDao.getSessionState()
            val nowMonotonic = SystemClock.elapsedRealtime()
            val newRevision = (currentState?.revision ?: 0L) + 1L
            val state = PaidSessionState(
                id = 1,
                sessionTimeRemaining = 0,
                sessionExpiryDeadlineMs = 0L,
                lastSavedElapsedRealtime = nowMonotonic,
                revision = newRevision
            )
            paymentDao.updateSessionState(state)
            state
        }
        onSessionStateChanged?.invoke(SessionSnapshot(0L, 0, newState.revision))
        return newState
    }

    fun expireSessionBlocking(): PaidSessionState = runBlocking(Dispatchers.IO) {
        expireSession()
    }

    /**
     * Checks if the active paid session deadline in Room has actually expired before clearing.
     * Clears balance ONLY if the database deadline has expired.
     */
    suspend fun expireSessionIfDue(): ExpiryResult {
        var didExpire = false
        val state = db.withTransaction {
            val currentState = paymentDao.getSessionState()
            val nowMonotonic = SystemClock.elapsedRealtime()
            if (currentState == null) {
                val fallback = PaidSessionState(
                    id = 1,
                    sessionTimeRemaining = 0,
                    sessionExpiryDeadlineMs = 0L,
                    lastSavedElapsedRealtime = nowMonotonic,
                    revision = 0L
                )
                return@withTransaction fallback
            }

            val isDue = currentState.sessionExpiryDeadlineMs > 0L && nowMonotonic >= currentState.sessionExpiryDeadlineMs
            if (isDue) {
                didExpire = true
                val newRevision = currentState.revision + 1L
                val clearedState = PaidSessionState(
                    id = 1,
                    sessionTimeRemaining = 0,
                    sessionExpiryDeadlineMs = 0L,
                    lastSavedElapsedRealtime = nowMonotonic,
                    revision = newRevision
                )
                paymentDao.updateSessionState(clearedState)
                clearedState
            } else {
                currentState
            }
        }

        if (didExpire) {
            onSessionStateChanged?.invoke(SessionSnapshot(0L, 0, state.revision))
        }

        return ExpiryResult(didExpire = didExpire, sessionState = state)
    }

    fun expireSessionIfDueBlocking(): ExpiryResult = runBlocking(Dispatchers.IO) {
        expireSessionIfDue()
    }

    suspend fun adjustSessionTime(durationSeconds: Int): PaidSessionState {
        val newState = db.withTransaction {
            val currentState = paymentDao.getSessionState()
            val nowMonotonic = SystemClock.elapsedRealtime()
            val newDeadline = if (durationSeconds > 0) nowMonotonic + (durationSeconds * 1000L) else 0L
            val remaining = maxOf(0, durationSeconds)
            val newRevision = (currentState?.revision ?: 0L) + 1L
            val state = PaidSessionState(
                id = 1,
                sessionTimeRemaining = remaining,
                sessionExpiryDeadlineMs = newDeadline,
                lastSavedElapsedRealtime = nowMonotonic,
                revision = newRevision
            )
            paymentDao.updateSessionState(state)
            state
        }
        onSessionStateChanged?.invoke(
            SessionSnapshot(newState.sessionExpiryDeadlineMs, newState.sessionTimeRemaining, newState.revision)
        )
        return newState
    }

    fun adjustSessionTimeBlocking(durationSeconds: Int): PaidSessionState = runBlocking(Dispatchers.IO) {
        adjustSessionTime(durationSeconds)
    }

    fun resetSessionBlocking(): PaidSessionState = expireSessionBlocking()

    /**
     * Checkpoints countdown progress for recovery if snapshot revision matches database revision.
     * Calculates remaining time directly from the database's authoritative deadline without
     * overwriting the deadline itself.
     */
    suspend fun checkpointSession(snapshotRevision: Long) {
        db.withTransaction {
            val current = paymentDao.getSessionState() ?: return@withTransaction
            if (current.revision != snapshotRevision) {
                Log.d(TAG, "Ignoring stale checkpoint: DB rev (${current.revision}) != snapshot rev ($snapshotRevision)")
                return@withTransaction
            }
            val nowMonotonic = SystemClock.elapsedRealtime()
            val remaining = if (current.sessionExpiryDeadlineMs > nowMonotonic) {
                ((current.sessionExpiryDeadlineMs - nowMonotonic) / 1000L).toInt()
            } else {
                0
            }
            val updated = current.copy(
                sessionTimeRemaining = remaining,
                lastSavedElapsedRealtime = nowMonotonic
            )
            paymentDao.updateSessionState(updated)
        }
    }

    fun checkpointSessionBlocking(snapshotRevision: Long) = runBlocking(Dispatchers.IO) {
        checkpointSession(snapshotRevision)
    }

    // Overload for backward compatibility
    suspend fun checkpointSession(remainingSec: Int, deadlineMs: Long, snapshotRevision: Long = 0L) {
        checkpointSession(snapshotRevision)
    }

    fun checkpointSessionBlocking(remainingSec: Int, deadlineMs: Long, snapshotRevision: Long = 0L) =
        runBlocking(Dispatchers.IO) {
            checkpointSession(snapshotRevision)
        }

    fun restoreSessionState(ctx: Context? = context): RestoredSessionState = runBlocking(Dispatchers.IO) {
        if (ctx != null) {
            migrateAndInitialize(ctx)
        }
        val (snapshot, isReboot) = db.withTransaction {
            val paidState = paymentDao.getSessionState()
            val nowMonotonic = SystemClock.elapsedRealtime()
            if (paidState == null) {
                return@withTransaction Pair(SessionSnapshot(0L, 0, 0L), false)
            }

            val savedDeadline = paidState.sessionExpiryDeadlineMs
            val savedTime = paidState.sessionTimeRemaining
            val lastSavedElapsed = paidState.lastSavedElapsedRealtime

            val rebootDetected = lastSavedElapsed > 0L && nowMonotonic < lastSavedElapsed
            val effectiveRemainingSec: Int
            val effectiveDeadline: Long
            var updatedRevision = paidState.revision

            if (rebootDetected) {
                effectiveRemainingSec = maxOf(0, savedTime)
                effectiveDeadline = if (effectiveRemainingSec > 0) nowMonotonic + (effectiveRemainingSec * 1000L) else 0L
                updatedRevision += 1L
                paymentDao.updateSessionState(
                    PaidSessionState(
                        id = 1,
                        sessionTimeRemaining = effectiveRemainingSec,
                        sessionExpiryDeadlineMs = effectiveDeadline,
                        lastSavedElapsedRealtime = nowMonotonic,
                        revision = updatedRevision
                    )
                )
            } else if (savedDeadline > nowMonotonic) {
                effectiveDeadline = savedDeadline
                effectiveRemainingSec = ((savedDeadline - nowMonotonic) / 1000L).toInt()
            } else if (savedDeadline != 0L) {
                effectiveRemainingSec = 0
                effectiveDeadline = 0L
                updatedRevision += 1L
                paymentDao.updateSessionState(
                    PaidSessionState(
                        id = 1,
                        sessionTimeRemaining = 0,
                        sessionExpiryDeadlineMs = 0L,
                        lastSavedElapsedRealtime = nowMonotonic,
                        revision = updatedRevision
                    )
                )
            } else {
                effectiveRemainingSec = maxOf(0, savedTime)
                effectiveDeadline = 0L
            }

            Pair(SessionSnapshot(effectiveDeadline, effectiveRemainingSec, updatedRevision), rebootDetected)
        }

        onSessionStateChanged?.invoke(snapshot)
        RestoredSessionState(snapshot.remainingSeconds, snapshot.deadlineMs, isReboot, snapshot.revision)
    }

    fun migrateAndInitialize(ctx: Context) = runBlocking(Dispatchers.IO) {
        val deviceContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            ctx.applicationContext.createDeviceProtectedStorageContext()
        } else {
            ctx.applicationContext
        }
        val prefs = deviceContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Fast-path check
        val isAlreadyMigratedMeta = paymentDao.getMetadata(KEY_MIGRATION_MARKER) == "true"
        val isLegacyPrefsMigrated = prefs.getBoolean(KEY_MIGRATION_MARKER_PREFS, false)
        if (isAlreadyMigratedMeta || isLegacyPrefsMigrated) {
            if (!isAlreadyMigratedMeta) {
                db.withTransaction {
                    paymentDao.setMetadata(AppMetadata(KEY_MIGRATION_MARKER, "true"))
                }
            }
            return@runBlocking
        }

        db.withTransaction {
            if (paymentDao.getMetadata(KEY_MIGRATION_MARKER) == "true") {
                return@withTransaction
            }

            // 1. Reconcile coin_events without silencing exceptions
            val existingEvents = db.coinEventDao().getLatestEvents(limit = 1000)
            for (ev in existingEvents) {
                paymentDao.insertReceiptIgnore(
                    PaymentReceipt(
                        txId = ev.txId,
                        secondsCredited = ev.secondsAdded,
                        amount = 0.0,
                        acceptanceTimestamp = ev.timestamp
                    )
                )
            }

            // 2. Reconcile processed_tx_ids without adding credit
            val processedTxIds = prefs.getStringSet("processed_tx_ids", emptySet()) ?: emptySet()
            for (txId in processedTxIds) {
                if (paymentDao.getReceiptByTxId(txId) == null) {
                    paymentDao.insertReceiptIgnore(
                        PaymentReceipt(
                            txId = txId,
                            secondsCredited = 0,
                            amount = 0.0,
                            acceptanceTimestamp = System.currentTimeMillis()
                        )
                    )
                }
            }

            // 3. Import legacy balance ONLY if authoritative Room session state does NOT exist
            val currentState = paymentDao.getSessionState()
            if (currentState == null) {
                val legacyRemaining = prefs.getInt("session_time_remaining", 0)
                val legacyDeadline = prefs.getLong("session_expiry_deadline_ms", 0L)
                val legacyLastElapsed = prefs.getLong("last_saved_elapsed_realtime", 0L)
                val nowMonotonic = SystemClock.elapsedRealtime()

                val isReboot = legacyLastElapsed > 0L && nowMonotonic < legacyLastElapsed
                val effectiveRemaining: Int
                val effectiveDeadline: Long

                if (!isReboot) {
                    if (legacyDeadline > nowMonotonic) {
                        effectiveDeadline = legacyDeadline
                        effectiveRemaining = ((legacyDeadline - nowMonotonic) / 1000L).toInt()
                    } else if (legacyDeadline != 0L) {
                        // Expired legacy session for the same boot restores zero
                        effectiveDeadline = 0L
                        effectiveRemaining = 0
                    } else if (legacyRemaining > 0) {
                        effectiveRemaining = legacyRemaining
                        effectiveDeadline = nowMonotonic + (legacyRemaining * 1000L)
                    } else {
                        effectiveRemaining = 0
                        effectiveDeadline = 0L
                    }
                } else {
                    // After reboot, follow recovery policy using saved remaining time
                    if (legacyRemaining > 0) {
                        effectiveRemaining = legacyRemaining
                        effectiveDeadline = nowMonotonic + (legacyRemaining * 1000L)
                    } else {
                        effectiveRemaining = 0
                        effectiveDeadline = 0L
                    }
                }

                paymentDao.updateSessionState(
                    PaidSessionState(
                        id = 1,
                        sessionTimeRemaining = effectiveRemaining,
                        sessionExpiryDeadlineMs = effectiveDeadline,
                        lastSavedElapsedRealtime = nowMonotonic,
                        revision = 1L
                    )
                )
                Log.i(TAG, "Migrated legacy session state: ${effectiveRemaining}s (deadline=$effectiveDeadline)")
            } else {
                Log.i(TAG, "Authoritative Room session state already exists (rev=${currentState.revision}). Preserving.")
            }

            // 4. Mark migration completed atomically inside Room metadata
            paymentDao.setMetadata(AppMetadata(KEY_MIGRATION_MARKER, "true"))
        }

        prefs.edit().putBoolean(KEY_MIGRATION_MARKER_PREFS, true).commit()
        Log.i(TAG, "Migration completed atomically.")
    }

    fun getSessionState(): PaidSessionState? {
        return paymentDao.getSessionState()
    }
}

