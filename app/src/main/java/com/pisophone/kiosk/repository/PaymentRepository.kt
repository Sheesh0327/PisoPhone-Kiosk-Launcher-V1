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
 * Result of restoring session state from local persistence.
 */
data class RestoredSessionState(
    val remainingSeconds: Int,
    val deadlineMs: Long,
    val isReboot: Boolean
)

/**
 * Repository acting as the single payment-processing and session-balance authority.
 */
class PaymentRepository(
    private val db: AppDatabase,
    private val context: Context? = null,
    private val isEligible: () -> Boolean = { true },
    private val onPaymentApplied: ((txId: String, seconds: Int, amount: Double, newDeadline: Long, newRemaining: Int) -> Unit)? = null,
    private val onSessionStateChanged: ((deadlineMs: Long, remainingSec: Int) -> Unit)? = null
) {
    companion object {
        private const val TAG = "PaymentRepository"
        const val PREFS_NAME = "kiosk_persistent_state"
        const val KEY_MIGRATION_MARKER = "legacy_paid_state_migrated_v3"
    }

    constructor(
        db: AppDatabase,
        context: Context,
        onPaymentApplied: ((txId: String, seconds: Int, amount: Double, newDeadline: Long, newRemaining: Int) -> Unit)? = null,
        onSessionStateChanged: ((deadlineMs: Long, remainingSec: Int) -> Unit)? = null
    ) : this(
        db = db,
        context = context,
        isEligible = { KioskActivationManager.isAppAllowedToRun(context) },
        onPaymentApplied = onPaymentApplied,
        onSessionStateChanged = onSessionStateChanged
    )

    private val paymentDao = db.paymentDao()

    /**
     * Credits a payment in a single atomic Room database transaction.
     *
     * 1. Looks up the transaction ID.
     * 2. If an identical receipt exists, returns ALREADY_APPLIED without extending time.
     * 3. If the ID exists with different payment values, returns CONFLICT.
     * 4. For a new payment, checks the existing eligibility rules.
     * 5. Reads the current paid state, calculates the extension, and saves the receipt and updated state together.
     * 6. Returns APPLIED only after the transaction commits.
     *
     * Database failures escape withTransaction and are converted to FAILED outside it.
     */
    suspend fun creditPayment(txId: String, seconds: Int, amount: Double): PaymentResult {
        var committedNewDeadline: Long? = null
        var committedNewRemaining: Int? = null

        val result = try {
            db.withTransaction {
                // Look up the transaction ID
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

                // For a new payment, check the existing eligibility rules
                if (!isEligible()) {
                    Log.w(TAG, "Payment rejected for $txId: Device/slot is not currently eligible.")
                    return@withTransaction PaymentResult.NOT_ELIGIBLE
                }

                // Read current paid state and calculate extension
                val currentState = paymentDao.getSessionState()
                val nowMonotonic = SystemClock.elapsedRealtime()
                val currentDeadline = currentState?.sessionExpiryDeadlineMs ?: 0L
                val newDeadline = if (currentDeadline > nowMonotonic) {
                    currentDeadline + (seconds * 1000L)
                } else {
                    nowMonotonic + (seconds * 1000L)
                }
                val newSessionTime = ((newDeadline - nowMonotonic) / 1000L).toInt()

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
                    lastSavedElapsedRealtime = nowMonotonic
                )

                // Save receipt and updated state together
                paymentDao.insertReceipt(receipt)
                paymentDao.updateSessionState(newState)

                committedNewDeadline = newDeadline
                committedNewRemaining = newSessionTime

                PaymentResult.APPLIED
            }
        } catch (e: Exception) {
            Log.e(TAG, "Database transaction failed for txId $txId: ${e.message}", e)
            PaymentResult.FAILED
        }

        if (result == PaymentResult.APPLIED) {
            val deadline = committedNewDeadline ?: 0L
            val remaining = committedNewRemaining ?: 0
            onPaymentApplied?.invoke(txId, seconds, amount, deadline, remaining)
            onSessionStateChanged?.invoke(deadline, remaining)
        }

        return result
    }

    /**
     * Synchronous wrapper for creditPayment for blocking contexts.
     */
    fun creditPaymentBlocking(txId: String, seconds: Int, amount: Double): PaymentResult =
        runBlocking(Dispatchers.IO) {
            creditPayment(txId, seconds, amount)
        }

    /**
     * Deducts time from the active paid session in a database transaction.
     * [secondsDelta] is negative for deductions (e.g., -300 for -5 minutes).
     */
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
            val newState = PaidSessionState(
                id = 1,
                sessionTimeRemaining = remaining,
                sessionExpiryDeadlineMs = effectiveDeadline,
                lastSavedElapsedRealtime = nowMonotonic
            )
            paymentDao.updateSessionState(newState)
            newState
        }
        onSessionStateChanged?.invoke(updatedState.sessionExpiryDeadlineMs, updatedState.sessionTimeRemaining)
        return updatedState
    }

    fun deductTimeBlocking(secondsDelta: Int): PaidSessionState = runBlocking(Dispatchers.IO) {
        deductTime(secondsDelta)
    }

    /**
     * Terminates the active paid session by committing 0 remaining time and deadline in Room.
     */
    suspend fun expireSession(): PaidSessionState {
        val nowMonotonic = SystemClock.elapsedRealtime()
        val newState = PaidSessionState(
            id = 1,
            sessionTimeRemaining = 0,
            sessionExpiryDeadlineMs = 0L,
            lastSavedElapsedRealtime = nowMonotonic
        )
        db.withTransaction {
            paymentDao.updateSessionState(newState)
        }
        onSessionStateChanged?.invoke(0L, 0)
        return newState
    }

    fun expireSessionBlocking(): PaidSessionState = runBlocking(Dispatchers.IO) {
        expireSession()
    }

    /**
     * Sets or adjusts the session duration directly for administrative bypass or maintenance.
     */
    suspend fun adjustSessionTime(durationSeconds: Int): PaidSessionState {
        val nowMonotonic = SystemClock.elapsedRealtime()
        val newDeadline = if (durationSeconds > 0) nowMonotonic + (durationSeconds * 1000L) else 0L
        val remaining = maxOf(0, durationSeconds)
        val newState = PaidSessionState(
            id = 1,
            sessionTimeRemaining = remaining,
            sessionExpiryDeadlineMs = newDeadline,
            lastSavedElapsedRealtime = nowMonotonic
        )
        db.withTransaction {
            paymentDao.updateSessionState(newState)
        }
        onSessionStateChanged?.invoke(newDeadline, remaining)
        return newState
    }

    fun adjustSessionTimeBlocking(durationSeconds: Int): PaidSessionState = runBlocking(Dispatchers.IO) {
        adjustSessionTime(durationSeconds)
    }

    fun resetSessionBlocking(): PaidSessionState = expireSessionBlocking()

    /**
     * Periodically checkpoints countdown progress to local persistence without overwriting
     * newly committed credit if a concurrent payment increased the deadline.
     */
    suspend fun checkpointSession(remainingSec: Int, deadlineMs: Long) {
        db.withTransaction {
            val current = paymentDao.getSessionState()
            val nowMonotonic = SystemClock.elapsedRealtime()
            val curDeadline = current?.sessionExpiryDeadlineMs ?: 0L
            if (curDeadline > deadlineMs && curDeadline > nowMonotonic) {
                Log.d(TAG, "Skipping checkpoint: DB deadline ($curDeadline) > snapshot deadline ($deadlineMs)")
                return@withTransaction
            }
            val newState = PaidSessionState(
                id = 1,
                sessionTimeRemaining = maxOf(0, remainingSec),
                sessionExpiryDeadlineMs = if (remainingSec > 0) maxOf(0L, deadlineMs) else 0L,
                lastSavedElapsedRealtime = nowMonotonic
            )
            paymentDao.updateSessionState(newState)
        }
    }

    fun checkpointSessionBlocking(remainingSec: Int, deadlineMs: Long) = runBlocking(Dispatchers.IO) {
        checkpointSession(remainingSec, deadlineMs)
    }

    /**
     * Authoritatively restores paid session state from Room, correctly handling reboot scenarios.
     * Automatically ensures legacy migration runs first if context is provided.
     */
    fun restoreSessionState(ctx: Context? = context): RestoredSessionState = runBlocking(Dispatchers.IO) {
        if (ctx != null) {
            migrateAndInitialize(ctx)
        }
        val paidState = paymentDao.getSessionState()
        val nowMonotonic = SystemClock.elapsedRealtime()
        if (paidState == null) {
            val fallback = RestoredSessionState(0, 0L, false)
            onSessionStateChanged?.invoke(0L, 0)
            return@runBlocking fallback
        }

        val savedDeadline = paidState.sessionExpiryDeadlineMs
        val savedTime = paidState.sessionTimeRemaining
        val lastSavedElapsed = paidState.lastSavedElapsedRealtime

        val isReboot = lastSavedElapsed > 0L && nowMonotonic < lastSavedElapsed
        val effectiveRemainingSec: Int
        val effectiveDeadline: Long

        if (isReboot) {
            effectiveRemainingSec = maxOf(0, savedTime)
            effectiveDeadline = if (effectiveRemainingSec > 0) nowMonotonic + (effectiveRemainingSec * 1000L) else 0L
            paymentDao.updateSessionState(
                PaidSessionState(
                    id = 1,
                    sessionTimeRemaining = effectiveRemainingSec,
                    sessionExpiryDeadlineMs = effectiveDeadline,
                    lastSavedElapsedRealtime = nowMonotonic
                )
            )
        } else if (savedDeadline > nowMonotonic) {
            effectiveDeadline = savedDeadline
            effectiveRemainingSec = ((savedDeadline - nowMonotonic) / 1000L).toInt()
        } else if (savedDeadline > 0L) {
            effectiveRemainingSec = 0
            effectiveDeadline = 0L
            paymentDao.updateSessionState(
                PaidSessionState(
                    id = 1,
                    sessionTimeRemaining = 0,
                    sessionExpiryDeadlineMs = 0L,
                    lastSavedElapsedRealtime = nowMonotonic
                )
            )
        } else {
            effectiveRemainingSec = maxOf(0, savedTime)
            effectiveDeadline = 0L
        }

        val result = RestoredSessionState(effectiveRemainingSec, effectiveDeadline, isReboot)
        onSessionStateChanged?.invoke(effectiveDeadline, effectiveRemainingSec)
        result
    }

    /**
     * Imports existing paid state once, with a durable migration marker, and preserves
     * existing payment history and usable transaction IDs without adding their credit again.
     */
    fun migrateAndInitialize(ctx: Context) = runBlocking(Dispatchers.IO) {
        val deviceContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            ctx.applicationContext.createDeviceProtectedStorageContext()
        } else {
            ctx.applicationContext
        }
        val prefs = deviceContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isMigrated = prefs.getBoolean(KEY_MIGRATION_MARKER, false)
        if (isMigrated) {
            return@runBlocking
        }

        db.withTransaction {
            // 1. Reconcile existing coin_events records into payment_receipts to preserve payment history
            try {
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
            } catch (e: Exception) {
                Log.w(TAG, "Error reconciling coin_events: ${e.message}")
            }

            // 2. Reconcile processed_tx_ids from legacy SharedPreferences into payment_receipts without adding credit
            try {
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
            } catch (e: Exception) {
                Log.w(TAG, "Error reconciling processed_tx_ids: ${e.message}")
            }

            // 3. Import existing paid session state once without clearing credit
            val currentState = paymentDao.getSessionState()
            if (currentState == null || (currentState.sessionTimeRemaining == 0 && currentState.sessionExpiryDeadlineMs == 0L)) {
                val legacyRemaining = prefs.getInt("session_time_remaining", 0)
                val legacyDeadline = prefs.getLong("session_expiry_deadline", 0L)
                val nowMonotonic = SystemClock.elapsedRealtime()

                val effectiveRemaining: Int
                val effectiveDeadline: Long

                if (legacyDeadline > nowMonotonic) {
                    effectiveDeadline = legacyDeadline
                    effectiveRemaining = ((legacyDeadline - nowMonotonic) / 1000L).toInt()
                } else if (legacyRemaining > 0) {
                    effectiveRemaining = legacyRemaining
                    effectiveDeadline = nowMonotonic + (legacyRemaining * 1000L)
                } else {
                    effectiveRemaining = 0
                    effectiveDeadline = 0L
                }

                if (effectiveRemaining > 0) {
                    paymentDao.updateSessionState(
                        PaidSessionState(
                            id = 1,
                            sessionTimeRemaining = effectiveRemaining,
                            sessionExpiryDeadlineMs = effectiveDeadline,
                            lastSavedElapsedRealtime = nowMonotonic
                        )
                    )
                    Log.i(TAG, "Migrated legacy paid state: ${effectiveRemaining}s, deadline=$effectiveDeadline")
                }
            }
        }

        // 4. Record durable migration marker synchronously
        prefs.edit().putBoolean(KEY_MIGRATION_MARKER, true).commit()
        Log.i(TAG, "Recorded durable migration marker: $KEY_MIGRATION_MARKER")
    }

    fun getSessionState(): PaidSessionState? {
        return paymentDao.getSessionState()
    }
}
