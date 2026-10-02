package com.pisophone.kiosk.service

/**
 * The paid-session state machine, as pure functions.
 *
 * The state is still published as an Int (`KioskStateManager.appState`) because the number is part of
 * the heartbeat sent to the ESP32 and is persisted across reboots; this file is the only place that
 * knows what the numbers mean and which transitions are valid. Every transition used to be an inline
 * `when` scattered over the engine, the coordinators and the supervisor; they are unit-tested in
 * `SessionRulesTest`.
 */
enum class SessionState(val code: Int) {
    /** Phone locked, coin slot not armed. */
    LOCKED(0),

    /** Phone locked while the coin slot is armed and waiting for a coin. */
    ARMED_LOCKED(1),

    /** Paid time running, slot not armed. */
    UNLOCKED(2),

    /** Paid time running and the slot is armed for more time. */
    UNLOCKED_ARMED(3),
    ;

    companion object {
        fun fromCode(code: Int): SessionState? = entries.firstOrNull { it.code == code }
    }
}

object SessionRules {
    private val LOCKED = SessionState.LOCKED.code
    private val ARMED_LOCKED = SessionState.ARMED_LOCKED.code
    private val UNLOCKED = SessionState.UNLOCKED.code
    private val UNLOCKED_ARMED = SessionState.UNLOCKED_ARMED.code

    /** The coin slot is armed and the arming countdown is running. */
    fun isArmed(state: Int): Boolean = state == ARMED_LOCKED || state == UNLOCKED_ARMED

    /** The customer has paid time and the phone is usable. */
    fun isUnlocked(state: Int): Boolean = state == UNLOCKED || state == UNLOCKED_ARMED

    /** The full-screen lock/insert-coin overlay is shown. */
    fun isLockScreenShown(state: Int): Boolean = state == LOCKED || state == ARMED_LOCKED

    /** A coin was credited. Only a fully locked phone moves (to "armed, waiting"); an unlocked one keeps running. */
    fun afterCoinCredit(current: Int): Int? = if (current == LOCKED) ARMED_LOCKED else null

    /**
     * Non-coin credit (admin or complimentary time) unlocks the phone but must not drop an armed slot:
     * 0 -> 2, 1 -> 3, 2/3 unchanged.
     */
    fun afterNonCoinCredit(current: Int): Int? = when (current) {
        LOCKED -> UNLOCKED
        ARMED_LOCKED -> UNLOCKED_ARMED
        else -> null
    }

    /** Paid time ran out: an armed slot stays armed (1) so a coin being inserted is not lost, otherwise 0. */
    fun afterExpiry(current: Int): Int = if (isArmed(current)) ARMED_LOCKED else LOCKED

    /** Admin deducted time: only an unlocked session that reached zero locks. */
    fun afterDeduct(current: Int, remainingSeconds: Int): Int? =
        if (remainingSeconds <= 0 && isUnlocked(current)) afterExpiry(current) else null

    /** Admin bypass unlocks, but an armed slot stays armed (1 -> 3, 3 stays 3). */
    fun afterAdminBypass(current: Int): Int = if (isArmed(current)) UNLOCKED_ARMED else UNLOCKED

    /** The ESP32 confirmed the slot is armed. */
    fun afterArmSuccess(current: Int): Int = when (current) {
        UNLOCKED -> UNLOCKED_ARMED
        LOCKED -> ARMED_LOCKED
        else -> current
    }

    /**
     * An arm request failed. Never lock a paid session because an ADD TIME arm attempt failed:
     * 2 stays 2, 3 -> 2, 1 -> 0 unless the customer already has paid time (then 2), anything else unchanged.
     */
    fun afterArmFailure(current: Int, hasPaidTime: Boolean): Int = when (current) {
        UNLOCKED, UNLOCKED_ARMED -> UNLOCKED
        ARMED_LOCKED -> if (hasPaidTime) UNLOCKED else LOCKED
        else -> current
    }

    /** The arming window ran out with no coin: back to running (if paid time remains) or locked. */
    fun afterArmTimeoutNoCoin(current: Int, hasPaidTime: Boolean): Int =
        if (current == UNLOCKED_ARMED || hasPaidTime) UNLOCKED else LOCKED

    /** The customer finished paying (button or arming window with coins): 2 if coins were inserted or time is running, else 0. */
    fun afterFinishPayment(current: Int, coinsInserted: Int): Int =
        if (coinsInserted > 0 || current == UNLOCKED_ARMED) UNLOCKED else LOCKED

    /** Admin lock, slot lockdown and similar hard resets. */
    fun hardLocked(): Int = LOCKED
}
