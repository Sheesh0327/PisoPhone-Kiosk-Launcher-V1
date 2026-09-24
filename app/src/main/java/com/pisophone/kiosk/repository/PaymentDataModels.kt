package com.pisophone.kiosk.repository

import com.pisophone.kiosk.db.PaidSessionState

/**
 * Immutable snapshot of active paid session state.
 */
data class SessionSnapshot(
    val deadlineMs: Long,
    val remainingSeconds: Int,
    val revision: Long
)

/**
 * Per-call outcome combining payment result status and optional session snapshot from committed transactions.
 */
data class PaymentOutcome(
    val result: PaymentResult,
    val snapshot: SessionSnapshot? = null
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
