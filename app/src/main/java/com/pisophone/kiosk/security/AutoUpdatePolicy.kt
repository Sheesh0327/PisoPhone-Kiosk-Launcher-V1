package com.pisophone.kiosk.security

/**
 * When the phone installs a new app release by itself. The check is cheap (one small file), so it runs every hour; the
 * install only happens while nobody is using the phone, because installing restarts the app.
 */
internal object AutoUpdatePolicy {
    /** The first look after the service starts, so a phone that just booted is left alone while it settles. */
    const val FIRST_CHECK_DELAY_MS = 5 * 60_000L

    const val CHECK_INTERVAL_MS = 60 * 60_000L

    /** A build that failed to install this many times is left alone until a newer one is published. */
    const val MAX_ATTEMPTS_PER_BUILD = 3

    /**
     * Idle: the lock screen is showing (state 0), no rental time is left, nobody is signed in and no arena match is on.
     * (States 1 to 3 mean a customer is paying or playing; 4 is not licensed and has nothing to protect either, but it
     * is left to the owner.)
     */
    fun isIdle(appState: Int, secondsRemaining: Int, signedInAccount: String, arenaMode: Boolean): Boolean =
        appState == 0 && secondsRemaining <= 0 && signedInAccount.isEmpty() && !arenaMode

    fun mayAttempt(attemptsForThisBuild: Int): Boolean = attemptsForThisBuild < MAX_ATTEMPTS_PER_BUILD
}
