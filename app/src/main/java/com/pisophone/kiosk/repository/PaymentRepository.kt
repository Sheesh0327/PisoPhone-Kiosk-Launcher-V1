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
import com.pisophone.kiosk.security.KioskSecurity
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
 * Outcome of a two-phone match transfer keeping separate linked outcomes
 * and showing partial completion instead of pretending independent databases
 * form one atomic transaction.
 */
data class MatchTransferOutcome(
    val matchId: String,
    val sourceDeviceId: String,
    val targetDeviceId: String,
    val stakeSeconds: Int,
    val deductTxId: String,
    val creditTxId: String,
    val deductResult: PaymentResult,
    val creditResult: PaymentResult,
    val isComplete: Boolean,
    val isPartial: Boolean
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
        const val KEY_BOOT_COUNT = "BOOT_COUNT"
        const val KEY_PENDING_TX = "pending_transaction_in_flight"
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

    suspend fun creditPayment(
        txId: String,
        seconds: Int,
        amount: Double,
        operationKind: String = "COIN",
        coinAmount: Int = amount.toInt(),
        pricePerCoin: Double = 0.0,
        boxInstallationEpoch: Long = 0L,
        phonePairingEpoch: Long = 0L,
        recordSchemaVersion: Int = PaymentReceipt.CURRENT_RECORD_SCHEMA_VERSION
    ): PaymentResult {
        var committedSnapshot: SessionSnapshot? = null

        val result = try {
            db.withTransaction {
                val existing = paymentDao.getReceiptByTxId(txId)
                if (existing != null) {
                    val isLegacy = existing.recordSchemaVersion < 5
                    val isIdentical = if (isLegacy) {
                        val isLegacyPlaceholder = existing.secondsCredited == 0 && Math.abs(existing.amount - 0.0) < 0.0001
                        val secondsMatch = existing.secondsCredited == seconds
                        val amountMatch = Math.abs(existing.amount - amount) < 0.0001 || Math.abs(existing.amount - 0.0) < 0.0001
                        (secondsMatch && amountMatch) || isLegacyPlaceholder
                    } else {
                        val secondsMatch = existing.secondsCredited == seconds
                        val amountMatch = Math.abs(existing.amount - amount) < 0.0001
                        val kindMatch = existing.operationKind == operationKind
                        val coinMatch = existing.coinAmount == coinAmount
                        val priceMatch = Math.abs(existing.pricePerCoin - pricePerCoin) < 0.001
                        val boxEpochMatch = existing.boxInstallationEpoch == boxInstallationEpoch
                        val phoneEpochMatch = existing.phonePairingEpoch == phonePairingEpoch
                        secondsMatch && amountMatch && kindMatch && coinMatch && priceMatch && boxEpochMatch && phoneEpochMatch
                    }

                    if (isIdentical) {
                        return@withTransaction PaymentResult.ALREADY_APPLIED
                    } else {
                        Log.w(
                            TAG,
                            "Transaction ID conflict for $txId: existing=(s=${existing.secondsCredited}, a=${existing.amount}, k=${existing.operationKind}) vs new=(s=$seconds, a=$amount, k=$operationKind)"
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
                    acceptanceTimestamp = System.currentTimeMillis(),
                    operationKind = operationKind,
                    coinAmount = coinAmount,
                    pricePerCoin = pricePerCoin,
                    boxInstallationEpoch = boxInstallationEpoch,
                    phonePairingEpoch = phonePairingEpoch,
                    recordSchemaVersion = recordSchemaVersion
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

    fun creditPaymentBlocking(
        txId: String,
        seconds: Int,
        amount: Double,
        operationKind: String = "COIN",
        coinAmount: Int = amount.toInt(),
        pricePerCoin: Double = 0.0,
        boxInstallationEpoch: Long = 0L,
        phonePairingEpoch: Long = 0L
    ): PaymentResult =
        runBlocking(Dispatchers.IO) {
            creditPayment(
                txId, seconds, amount,
                operationKind, coinAmount, pricePerCoin,
                boxInstallationEpoch, phonePairingEpoch
            )
        }

    suspend fun deductPayment(
        txId: String,
        seconds: Int,
        operationKind: String = "MANUAL_DEDUCTION",
        boxInstallationEpoch: Long = 0L,
        phonePairingEpoch: Long = 0L,
        recordSchemaVersion: Int = PaymentReceipt.CURRENT_RECORD_SCHEMA_VERSION
    ): PaymentResult {
        var committedSnapshot: SessionSnapshot? = null
        val positiveSeconds = if (seconds == Int.MIN_VALUE) Int.MAX_VALUE else Math.abs(seconds)
        val expectedNegativeSeconds = -positiveSeconds

        val result = try {
            db.withTransaction {
                if (txId.isNotBlank()) {
                    val existing = paymentDao.getReceiptByTxId(txId)
                    if (existing != null) {
                        val isLegacy = existing.recordSchemaVersion < 5
                        val isIdentical = if (isLegacy) {
                            val secondsMatch = existing.secondsCredited == expectedNegativeSeconds
                            val amountMatch = Math.abs(existing.amount - 0.0) < 0.0001
                            secondsMatch && amountMatch
                        } else {
                            val secondsMatch = existing.secondsCredited == expectedNegativeSeconds
                            val amountMatch = Math.abs(existing.amount - 0.0) < 0.0001
                            val kindMatch = existing.operationKind == operationKind
                            val boxEpochMatch = existing.boxInstallationEpoch == boxInstallationEpoch
                            val phoneEpochMatch = existing.phonePairingEpoch == phonePairingEpoch
                            secondsMatch && amountMatch && kindMatch && boxEpochMatch && phoneEpochMatch
                        }

                        if (isIdentical) {
                            return@withTransaction PaymentResult.ALREADY_APPLIED
                        } else {
                            Log.w(
                                TAG,
                                "Deduction transaction conflict for $txId: existing=(s=${existing.secondsCredited}, a=${existing.amount}, k=${existing.operationKind}) vs new=(s=$expectedNegativeSeconds, a=0.0, k=$operationKind)"
                            )
                            return@withTransaction PaymentResult.CONFLICT
                        }
                    }
                }

                if (!isEligible()) {
                    Log.w(TAG, "Deduction rejected for $txId: Device/slot is not currently eligible.")
                    return@withTransaction PaymentResult.NOT_ELIGIBLE
                }

                val currentState = paymentDao.getSessionState()
                val nowMonotonic = SystemClock.elapsedRealtime()
                val curDeadline = currentState?.sessionExpiryDeadlineMs ?: 0L
                val deductMs = positiveSeconds.toLong() * 1000L

                val isMatchTransfer = operationKind.equals("MATCH_TRANSFER", ignoreCase = true)
                if (isMatchTransfer) {
                    val currentRemainingMs = if (curDeadline > nowMonotonic) (curDeadline - nowMonotonic) else 0L
                    if (currentRemainingMs < deductMs) {
                        Log.w(
                            TAG,
                            "Match transfer deduction rejected for $txId: Insufficient balance ($currentRemainingMs ms < $deductMs ms required)."
                        )
                        return@withTransaction PaymentResult.NOT_ELIGIBLE
                    }
                }

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

                if (txId.isNotBlank()) {
                    val receipt = PaymentReceipt(
                        txId = txId,
                        secondsCredited = expectedNegativeSeconds,
                        amount = 0.0,
                        acceptanceTimestamp = System.currentTimeMillis(),
                        operationKind = operationKind,
                        coinAmount = 0,
                        pricePerCoin = 0.0,
                        boxInstallationEpoch = boxInstallationEpoch,
                        phonePairingEpoch = phonePairingEpoch,
                        recordSchemaVersion = recordSchemaVersion
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

                committedSnapshot = SessionSnapshot(effectiveDeadline, remaining, newRevision)
                PaymentResult.APPLIED
            }
        } catch (e: Exception) {
            Log.e(TAG, "Database transaction failed for deductPayment txId $txId: ${e.message}", e)
            PaymentResult.FAILED
        }

        if (result == PaymentResult.APPLIED) {
            val snapshot = committedSnapshot ?: SessionSnapshot(0L, 0, 0L)
            onSessionStateChanged?.invoke(snapshot)
        }

        return result
    }

    fun deductPaymentBlocking(
        txId: String,
        seconds: Int,
        operationKind: String = "MANUAL_DEDUCTION",
        boxInstallationEpoch: Long = 0L,
        phonePairingEpoch: Long = 0L
    ): PaymentResult =
        runBlocking(Dispatchers.IO) {
            deductPayment(txId, seconds, operationKind, boxInstallationEpoch, phonePairingEpoch)
        }

    fun evaluateMatchTransfer(
        matchId: String,
        sourceDeviceId: String,
        targetDeviceId: String,
        stakeSeconds: Int,
        deductTxId: String,
        creditTxId: String,
        deductResult: PaymentResult,
        creditResult: PaymentResult
    ): MatchTransferOutcome {
        val hasDeductSuccess = (deductResult == PaymentResult.APPLIED || deductResult == PaymentResult.ALREADY_APPLIED)
        val hasCreditSuccess = (creditResult == PaymentResult.APPLIED || creditResult == PaymentResult.ALREADY_APPLIED)
        val isComplete = hasDeductSuccess && hasCreditSuccess
        val isPartial = (hasDeductSuccess && !hasCreditSuccess) || (!hasDeductSuccess && hasCreditSuccess)
        return MatchTransferOutcome(
            matchId = matchId,
            sourceDeviceId = sourceDeviceId,
            targetDeviceId = targetDeviceId,
            stakeSeconds = stakeSeconds,
            deductTxId = deductTxId,
            creditTxId = creditTxId,
            deductResult = deductResult,
            creditResult = creditResult,
            isComplete = isComplete,
            isPartial = isPartial
        )
    }

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

    suspend fun recoverUncommittedTransactions(nowMonotonic: Long = SystemClock.elapsedRealtime()) {
        // Inspect and sanitize PaidSessionState
        val currentState = paymentDao.getSessionState()
        if (currentState != null) {
            var sanitizedRemaining = currentState.sessionTimeRemaining
            var sanitizedDeadline = currentState.sessionExpiryDeadlineMs
            var needsUpdate = false

            if (sanitizedRemaining < 0) {
                sanitizedRemaining = 0
                sanitizedDeadline = 0L
                needsUpdate = true
            }
            if (sanitizedDeadline < 0L) {
                sanitizedDeadline = 0L
                sanitizedRemaining = 0
                needsUpdate = true
            }

            if (needsUpdate) {
                paymentDao.updateSessionState(
                    currentState.copy(
                        sessionTimeRemaining = sanitizedRemaining,
                        sessionExpiryDeadlineMs = sanitizedDeadline,
                        lastSavedElapsedRealtime = nowMonotonic,
                        revision = currentState.revision + 1L
                    )
                )
            }
        }
    }

    fun recoverUncommittedTransactionsBlocking(nowMonotonic: Long = SystemClock.elapsedRealtime()) =
        runBlocking(Dispatchers.IO) {
            recoverUncommittedTransactions(nowMonotonic)
        }

    fun restoreSessionState(ctx: Context? = context): RestoredSessionState = runBlocking(Dispatchers.IO) {
        if (ctx != null) {
            migrateAndInitialize(ctx)
        }

        val effectiveCtx = ctx ?: context
        val encryptedPrefs = effectiveCtx?.let { KioskSecurity.getEncryptedPreferences(it) }

        val (snapshot, isReboot) = db.withTransaction {
            val nowMonotonic = SystemClock.elapsedRealtime()
            recoverUncommittedTransactions(nowMonotonic)

            val currentBootCount = if (effectiveCtx != null) {
                try {
                    android.provider.Settings.Global.getInt(
                        effectiveCtx.contentResolver,
                        android.provider.Settings.Global.BOOT_COUNT,
                        -1
                    )
                } catch (e: Exception) {
                    -1
                }
            } else {
                -1
            }
            val lastSavedBootCountStr = paymentDao.getMetadata(KEY_BOOT_COUNT)
            val lastSavedBootCount = if (lastSavedBootCountStr != null) {
                lastSavedBootCountStr.toIntOrNull() ?: -1
            } else {
                encryptedPrefs?.getInt(KEY_BOOT_COUNT, -1) ?: -1
            }
            val isBootCountChanged = if (currentBootCount != -1) {
                val changed = lastSavedBootCount != -1 && currentBootCount != lastSavedBootCount
                paymentDao.setMetadata(AppMetadata(KEY_BOOT_COUNT, currentBootCount.toString()))
                changed
            } else {
                false
            }

            val paidState = paymentDao.getSessionState()
            if (paidState == null) {
                return@withTransaction Pair(SessionSnapshot(0L, 0, 0L), false)
            }

            val savedDeadline = paidState.sessionExpiryDeadlineMs
            val savedTime = paidState.sessionTimeRemaining
            val lastSavedElapsed = paidState.lastSavedElapsedRealtime

            val monotonicRebootDetected = lastSavedElapsed > 0L && nowMonotonic < lastSavedElapsed
            val rebootDetected = isBootCountChanged || monotonicRebootDetected
            if (currentBootCount == -1 && monotonicRebootDetected) {
                val nextCount = (lastSavedBootCount.takeIf { it >= 0 } ?: 0) + 1
                paymentDao.setMetadata(AppMetadata(KEY_BOOT_COUNT, nextCount.toString()))
            }

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

