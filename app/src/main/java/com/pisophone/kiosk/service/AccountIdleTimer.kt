package com.pisophone.kiosk.service

/**
 * Signs a player out after the phone has been left alone: when the screen has stayed off for [offLimitMs]. The system's
 * own screen timeout is the "nobody is touching it" detector (a video that keeps playing keeps the screen on, so it
 * never counts as idle), and turning the screen back on before the limit cancels everything.
 *
 * Pure logic with an injected clock; [KioskEngine] feeds it screen events and ticks it once a second.
 */
class AccountIdleTimer(
    private val offLimitMs: Long = SCREEN_OFF_LIMIT_MS,
    private val warnBeforeMs: Long = WARN_BEFORE_MS,
) {
    enum class Action { NONE, WARN, SIGN_OUT }

    companion object {
        /** Screen off this long while signed in: sign out. */
        const val SCREEN_OFF_LIMIT_MS = 120_000L

        /** A spoken heads-up this long before that. */
        const val WARN_BEFORE_MS = 30_000L
    }

    private var screenOffSinceMs = -1L
    private var warned = false

    @Synchronized
    fun onScreenOff(nowMs: Long) {
        if (screenOffSinceMs < 0) {
            screenOffSinceMs = nowMs
            warned = false
        }
    }

    @Synchronized
    fun onScreenOn() {
        screenOffSinceMs = -1L
        warned = false
    }

    /** Call about once a second. Each of [Action.WARN] and [Action.SIGN_OUT] is returned once per idle stretch. */
    @Synchronized
    fun tick(nowMs: Long, signedIn: Boolean): Action {
        if (!signedIn || screenOffSinceMs < 0) return Action.NONE
        val off = nowMs - screenOffSinceMs
        if (off >= offLimitMs) {
            screenOffSinceMs = -1L
            warned = false
            return Action.SIGN_OUT
        }
        if (!warned && off >= offLimitMs - warnBeforeMs) {
            warned = true
            return Action.WARN
        }
        return Action.NONE
    }
}
