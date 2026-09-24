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
 * Repository acting as the single payment-processing and session-balance authority.
 * Migration logic is in [PaymentMigrationManager], session state in [PaymentSessionManager].
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
        const val PREFS_NAME = PaymentMigrationManager.PREFS_NAME
        const val KEY_MIGRATION_MARKER = PaymentMigrationManager.KEY_MIGRATION_MARKER
        const val KEY_MIGRATION_MARKER_PREFS = PaymentMigrationManager.KEY_MIGRATION_MARKER_PREFS
        const val KEY_BOOT_COUNT = PaymentMigrationManager.KEY_BOOT_COUNT
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
    private val migrationManager = PaymentMigrationManager(db)
    private val sessionManager = PaymentSessionManager(db)

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
    ): PaymentOutcome {
        val (result, snapshot) = try {
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
                        return@withTransaction Pair(PaymentResult.ALREADY_APPLIED, null)
                    } else {
                        Log.w(
                            TAG,
                            "Transaction ID conflict for $txId: existing=(s=${existing.secondsCredited}, a=${existing.amount}, k=${existing.operationKind}) vs new=(s=$seconds, a=$amount, k=$operationKind)"
                        )
                        return@withTransaction Pair(PaymentResult.CONFLICT, null)
                    }
                }

                if (!isEligible()) {
                    Log.w(TAG, "Payment rejected for $txId: Device/slot is not currently eligible.")
                    return@withTransaction Pair(PaymentResult.NOT_ELIGIBLE, null)
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

                val committedSnapshot = SessionSnapshot(newDeadline, newSessionTime, newRevision)
                Pair(PaymentResult.APPLIED, committedSnapshot)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Database transaction failed for txId $txId: ${e.message}", e)
            Pair(PaymentResult.FAILED, null)
        }

        if (result == PaymentResult.APPLIED && snapshot != null) {
            onPaymentApplied?.invoke(txId, seconds, amount, snapshot)
            onSessionStateChanged?.invoke(snapshot)
        }

        return PaymentOutcome(result, snapshot)
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
    ): PaymentOutcome =
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
    ): PaymentOutcome {
        val positiveSeconds = if (seconds == Int.MIN_VALUE) Int.MAX_VALUE else Math.abs(seconds)
        val expectedNegativeSeconds = -positiveSeconds

        val (result, snapshot) = try {
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
                            return@withTransaction Pair(PaymentResult.ALREADY_APPLIED, null)
                        } else {
                            Log.w(
                                TAG,
                                "Deduction transaction conflict for $txId: existing=(s=${existing.secondsCredited}, a=${existing.amount}, k=${existing.operationKind}) vs new=(s=$expectedNegativeSeconds, a=0.0, k=$operationKind)"
                            )
                            return@withTransaction Pair(PaymentResult.CONFLICT, null)
                        }
                    }
                }

                if (!isEligible()) {
                    Log.w(TAG, "Deduction rejected for $txId: Device/slot is not currently eligible.")
                    return@withTransaction Pair(PaymentResult.NOT_ELIGIBLE, null)
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
                        return@withTransaction Pair(PaymentResult.NOT_ELIGIBLE, null)
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

                val committedSnapshot = SessionSnapshot(effectiveDeadline, remaining, newRevision)
                Pair(PaymentResult.APPLIED, committedSnapshot)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Database transaction failed for deductPayment txId $txId: ${e.message}", e)
            Pair(PaymentResult.FAILED, null)
        }

        if (result == PaymentResult.APPLIED && snapshot != null) {
            onSessionStateChanged?.invoke(snapshot)
        }

        return PaymentOutcome(result, snapshot)
    }

    fun deductPaymentBlocking(
        txId: String,
        seconds: Int,
        operationKind: String = "MANUAL_DEDUCTION",
        boxInstallationEpoch: Long = 0L,
        phonePairingEpoch: Long = 0L
    ): PaymentOutcome =
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

    suspend fun deductTime(secondsDelta: Int, txId: String? = null): PaidSessionState =
        sessionManager.deductTime(secondsDelta, txId, onSessionStateChanged)

    fun deductTimeBlocking(secondsDelta: Int, txId: String? = null): PaidSessionState =
        runBlocking(Dispatchers.IO) {
            deductTime(secondsDelta, txId)
        }

    suspend fun expireSession(): PaidSessionState =
        sessionManager.expireSession(onSessionStateChanged)

    fun expireSessionBlocking(): PaidSessionState = runBlocking(Dispatchers.IO) {
        expireSession()
    }

    suspend fun expireSessionIfDue(): ExpiryResult =
        sessionManager.expireSessionIfDue(onSessionStateChanged)

    fun expireSessionIfDueBlocking(): ExpiryResult = runBlocking(Dispatchers.IO) {
        expireSessionIfDue()
    }

    suspend fun adjustSessionTime(durationSeconds: Int): PaidSessionState =
        sessionManager.adjustSessionTime(durationSeconds, onSessionStateChanged)

    fun adjustSessionTimeBlocking(durationSeconds: Int): PaidSessionState = runBlocking(Dispatchers.IO) {
        adjustSessionTime(durationSeconds)
    }

    fun resetSessionBlocking(): PaidSessionState = expireSessionBlocking()

    suspend fun checkpointSession(snapshotRevision: Long) =
        sessionManager.checkpointSession(snapshotRevision)

    fun checkpointSessionBlocking(snapshotRevision: Long) = runBlocking(Dispatchers.IO) {
        checkpointSession(snapshotRevision)
    }

    suspend fun recoverUncommittedTransactions(nowMonotonic: Long = SystemClock.elapsedRealtime()) =
        migrationManager.recoverUncommittedTransactions(nowMonotonic)

    fun recoverUncommittedTransactionsBlocking(nowMonotonic: Long = SystemClock.elapsedRealtime()) =
        runBlocking(Dispatchers.IO) {
            recoverUncommittedTransactions(nowMonotonic)
        }

    fun restoreSessionState(ctx: Context? = context): RestoredSessionState = runBlocking(Dispatchers.IO) {
        migrationManager.restoreSessionState(ctx ?: context, onSessionStateChanged)
    }

    fun migrateAndInitialize(ctx: Context) = runBlocking(Dispatchers.IO) {
        migrationManager.migrateAndInitialize(ctx)
    }

    fun getSessionState(): PaidSessionState? {
        return paymentDao.getSessionState()
    }
}
