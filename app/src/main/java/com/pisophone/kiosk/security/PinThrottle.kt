package com.pisophone.kiosk.security

/**
 * Slows down guessing of the admin PIN. Pure logic with no Android types, so it is unit-tested on its own.
 *
 * After [freeAttempts] wrong tries in a row the PIN is locked for [baseLockMs]; each further wrong try doubles
 * the lock up to [maxLockMs]. While locked even the correct PIN is refused. A correct PIN resets everything.
 * The state is a small value the caller keeps (the app stores it in preferences so a restart does not reset it).
 */
class PinThrottle(
    private val freeAttempts: Int = 5,
    private val baseLockMs: Long = 60_000L,
    private val maxLockMs: Long = 15 * 60_000L,
) {
    data class State(val failures: Int = 0, val lockedUntilMs: Long = 0L)

    /** Milliseconds left on the lock, 0 when a try is allowed. */
    fun remainingLockMs(state: State, nowMs: Long): Long {
        val left = state.lockedUntilMs - nowMs
        // A clock set far back must not extend a lock beyond the longest one it could ever have been.
        return if (left <= 0L) 0L else minOf(left, maxLockMs)
    }

    fun onFailure(state: State, nowMs: Long): State {
        val failures = state.failures + 1
        if (failures < freeAttempts) return State(failures, 0L)
        val doublings = (failures - freeAttempts).coerceAtMost(20)
        val lock = minOf(baseLockMs shl doublings, maxLockMs)
        return State(failures, nowMs + lock)
    }

    fun onSuccess(): State = State()
}
