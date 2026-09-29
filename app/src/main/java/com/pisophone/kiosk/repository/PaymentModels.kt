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
