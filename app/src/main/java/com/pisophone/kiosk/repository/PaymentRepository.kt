package com.pisophone.kiosk.repository

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import com.pisophone.kiosk.db.AppDatabase
import com.pisophone.kiosk.db.PaidSessionState
import com.pisophone.kiosk.db.PaymentReceipt
import com.pisophone.kiosk.security.KioskActivationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Authority managing transactional payment credits, ledger persistence, and monotonic rental clocks.
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
        const val PREFS_NAME = PaymentMigrationHelper.PREFS_NAME
        const val KEY_MIGRATION_MARKER = PaymentMigrationHelper.KEY_MIGRATION_MARKER
        const val KEY_MIGRATION_MARKER_PREFS = PaymentMigrationHelper.KEY_MIGRATION_MARKER_PREFS
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

    /**
     * Atomically credits a coin payment transaction into the local Room ledger.
     * Prevents double-spending, enforces idempotency on duplicate txId, and extends the monotonic session deadline.
     */
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
                    Log.w(TAG, "Payment rejected for $txId: Device or slot is not eligible.")
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

    /**
     * Atomically deducts rental time from the active session.
     */
    suspend fun deductTime(secondsDelta: Int, txId: String? = null): PaidSessionState {
        val updatedState = db.withTransaction {
            if (!txId.isNullOrBlank()) {
                val existing = paymentDao.getReceiptByTxId(txId)
                if (existing != null) {
                    val currentState = paymentDao.getSessionState() ?: PaidSessionState(
                        id = 1,
                        sessionTimeRemaining = 0,
                        sessionExpiryDeadlineMs = 0L,
                        lastSavedElapsedRealtime = SystemClock.elapsedRealtime(),
                        revision = 0L
                    )
                    return@withTransaction currentState
                }
            }

            val currentState = paymentDao.getSessionState()
            val nowMonotonic = SystemClock.elapsedRealtime()
            val curDeadline = currentState?.sessionExpiryDeadlineMs ?: 0L

            val rawSecondsLong = secondsDelta.toLong()
            val positiveSecondsLong = if (rawSecondsLong == Long.MIN_VALUE) Long.MAX_VALUE else Math.abs(rawSecondsLong)
            val deductMs = positiveSecondsLong * 1000L

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

            if (!txId.isNullOrBlank()) {
                val receipt = PaymentReceipt(
                    txId = txId,
                    secondsCredited = -positiveSecondsLong.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    amount = 0.0,
                    acceptanceTimestamp = System.currentTimeMillis()
                )
                paymentDao.insertReceipt(receipt)
            }

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

    fun deductTimeBlocking(secondsDelta: Int, txId: String? = null): PaidSessionState = runBlocking(Dispatchers.IO) {
        deductTime(secondsDelta, txId)
    }

    /**
     * Immediately clears and expires the session balance.
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
     * Evaluates whether the monotonic deadline has passed, expiring the session if due.
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

    /**
     * Overrides or adjusts total session time directly.
     */
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
     * Checkpoints active elapsed time to database if revision matches.
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

    suspend fun checkpointSession(remainingSec: Int, deadlineMs: Long, snapshotRevision: Long = 0L) {
        checkpointSession(snapshotRevision)
    }

    fun checkpointSessionBlocking(remainingSec: Int, deadlineMs: Long, snapshotRevision: Long = 0L) =
        runBlocking(Dispatchers.IO) {
            checkpointSession(snapshotRevision)
        }

    /**
     * Restores session state from database, handling device reboots and monotonic clock offsets.
     */
    fun restoreSessionState(ctx: Context? = context): RestoredSessionState = runBlocking(Dispatchers.IO) {
        if (ctx != null) {
            PaymentMigrationHelper.migrateAndInitialize(ctx, db)
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
        PaymentMigrationHelper.migrateAndInitialize(ctx, db)
    }

    fun getSessionState(): PaidSessionState? {
        return paymentDao.getSessionState()
    }
}
