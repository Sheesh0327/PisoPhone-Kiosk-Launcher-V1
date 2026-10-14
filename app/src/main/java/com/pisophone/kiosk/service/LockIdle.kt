package com.pisophone.kiosk.service

import android.os.SystemClock

/**
 * How long the lock screen has been left alone: the last touch on it, or the last time the screen came on. The supervisor reads it
 * once per second to decide when the screen goes off ([IdlePolicy]).
 */
object LockIdle {
    @Volatile private var lastInteractionMs = SystemClock.elapsedRealtime()

    /** Someone touched the lock screen, or the screen came on. */
    fun touch() {
        lastInteractionMs = SystemClock.elapsedRealtime()
    }

    fun idleMs(): Long = SystemClock.elapsedRealtime() - lastInteractionMs
}

/** The two idle rules as pure functions: wipe the social apps, and turn the screen off. */
object IdlePolicy {
    private const val MINUTE_MS = 60_000L

    /**
     * Wipe the social apps once per idle period: the phone has been locked without paid time for [lockedForMs] (at least
     * [afterMinutes]), nobody is signed in with a card, and it was not done yet in this period.
     */
    fun shouldClearGuest(enabled: Boolean, lockedForMs: Long, afterMinutes: Int, signedIn: Boolean, alreadyDone: Boolean): Boolean =
        enabled && !signedIn && !alreadyDone && lockedForMs >= afterMinutes * MINUTE_MS

    /**
     * Turn the screen off: the phone is locked and not waiting for a coin, the screen is on, and nobody touched it for
     * [afterMinutes].
     */
    fun shouldScreenOff(enabled: Boolean, lockedAndIdle: Boolean, screenOn: Boolean, idleMs: Long, afterMinutes: Int): Boolean =
        enabled && lockedAndIdle && screenOn && idleMs >= afterMinutes * MINUTE_MS
}
