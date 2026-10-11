package com.pisophone.kiosk.server

import kotlin.math.abs

/**
 * Whether a signed message from the box is recent enough to be believed. A copy of an old command captured on the network is
 * refused because its timestamp is too far from the box's clock; a copy sent within the window is caught by [ReplayGuard],
 * which accepts each signed message once.
 *
 * "The box's clock" is [com.pisophone.kiosk.network.BoxClock], the time the box itself reported. Until the phone has heard it
 * ([boxClockKnown]), or when the box sent no time, there is nothing to compare with and the message is [Verdict.UNVERIFIED]:
 * only [ReplayGuard] protects then, so a box that has not learned the time yet can never be locked out.
 *
 * Payments do not use this: they are idempotent by transaction id and the box repeats them with their original timestamp.
 */
class RequestFreshness(
    private val boxNowMs: () -> Long,
    private val boxClockKnown: () -> Boolean,
) {
    enum class Verdict {
        /** Within the window of the box's clock. */
        FRESH,

        /** Nothing to compare with (no box clock yet, or the box sent no time): left to [ReplayGuard]. */
        UNVERIFIED,

        OUTSIDE_WINDOW,
    }

    companion object {
        /** How far a message's time may be from the box's clock (the phone learns the box's time every few seconds). */
        const val WINDOW_MS = 120_000L

        /** [ReplayGuard] must remember a message at least as long as it can still pass the window: 2 x window. */
        const val MIN_REPLAY_MEMORY_MS = 2 * WINDOW_MS
    }

    fun check(ts: Long): Verdict {
        if (!boxClockKnown() || ts <= 0L) return Verdict.UNVERIFIED
        return if (abs(ts - boxNowMs()) > WINDOW_MS) Verdict.OUTSIDE_WINDOW else Verdict.FRESH
    }
}
