package com.pisophone.kiosk.repository

import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import com.pisophone.kiosk.db.AppDatabase
import com.pisophone.kiosk.db.PaidSessionState
import com.pisophone.kiosk.db.PaymentReceipt

/**
 * Handles session timer state adjustments, expiration evaluation, checkpointing,
 * and low-level deduction calculations inside Room transactions.
 */
class PaymentSessionManager(
    private val db: AppDatabase
) {
    companion object {
        private const val TAG = "PaymentSessionManager"
    }

    private val paymentDao = db.paymentDao()

    suspend fun deductTime(
        secondsDelta: Int,
        txId: String? = null,
        onSessionStateChanged: ((snapshot: SessionSnapshot) -> Unit)? = null
    ): PaidSessionState {
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

    suspend fun expireSession(onSessionStateChanged: ((snapshot: SessionSnapshot) -> Unit)? = null): PaidSessionState {
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

    suspend fun expireSessionIfDue(onSessionStateChanged: ((snapshot: SessionSnapshot) -> Unit)? = null): ExpiryResult {
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

    suspend fun adjustSessionTime(
        durationSeconds: Int,
        onSessionStateChanged: ((snapshot: SessionSnapshot) -> Unit)? = null
    ): PaidSessionState {
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
}
