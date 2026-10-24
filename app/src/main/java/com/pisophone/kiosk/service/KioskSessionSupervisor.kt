package com.pisophone.kiosk.service

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.pisophone.kiosk.repository.ExpiryResult
import com.pisophone.kiosk.repository.PaymentRepository
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.util.GuestReset
import com.pisophone.kiosk.util.KioskSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Ticks the paid session once per second: counts the remaining time down, persists a checkpoint
 * so a crash or reboot restores the session, and locks the kiosk when time runs out
 * (see [SessionRules.afterExpiry]). [isStalled] lets the watchdog detect a stuck loop.
 */
class KioskSessionSupervisor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val stateManager: KioskStateManager,
    private val paymentRepo: PaymentRepository,
    private val onSpeakWarning: (String) -> Unit,
    /** One number of the final countdown (5, 4, 3, 2, 1), at its own second. */
    private val onCountdownDigit: (Int) -> Unit = {},
    /** Time was added during the final countdown. */
    private val onCountdownCancelled: () -> Unit = {},
    private val onFinishPayment: () -> Unit,
    private val onTimeExpired: () -> Unit = {},
    private val onCloseSession: (Boolean) -> Unit,
    private val onCheckBatteryAlerts: () -> Unit,
    /** Centralized lock side effects (send customer app home, pause media, unarm if needed). */
    private val onSessionExpired: (cancelArm: Boolean) -> Unit = {},
) {
    companion object {
        private const val TAG = "KioskSessionSupervisor"
        private const val CHECKPOINT_INTERVAL_MS = 5_000L

        /**
         * State to enter when the paid balance runs out: an armed slot (1/3) stays armed in the
         * locked-waiting state 1 so coins being inserted are not lost; otherwise fully locked (0).
         */
        fun lockedStateFor(current: Int): Int = SessionRules.afterExpiry(current)

        /**
         * APP-01: an unlocked phone with deadline 0 and nothing left in Room was never "due", so it never locked; lock it too.
         */
        fun lockDue(current: Int, r: ExpiryResult): Boolean =
            r.didExpire ||
                SessionRules.isUnlockedWithNothingLeft(current, r.sessionState.sessionExpiryDeadlineMs, r.sessionState.sessionTimeRemaining)
    }

    @Volatile
    private var lastCheckpointMonotonicMs: Long = 0L

    private var timerJob: Job? = null
    private val announcer = TimeAnnouncer()

    @Volatile private var lockedSinceMs = 0L

    @Volatile private var guestClearDone = false

    @Volatile
    private var lastTickMonotonicMs: Long = 0L

    fun isStalled(maxLagMs: Long = 5000L): Boolean {
        val last = lastTickMonotonicMs
        if (last == 0L) return false
        return (android.os.SystemClock.elapsedRealtime() - last) > maxLagMs
    }

    fun ensureRunning() {
        if (timerJob == null || timerJob?.isActive != true || isStalled()) {
            Log.w(TAG, "Timer job inactive or stalled. Restarting supervisor loop.")
            start()
        }
    }

    fun start() {
        timerJob?.cancel()
        lastTickMonotonicMs = android.os.SystemClock.elapsedRealtime()
        timerJob = scope.launch {
            while (isActive) {
                try {
                    delay(1000)
                    lastTickMonotonicMs = android.os.SystemClock.elapsedRealtime()

                    val curState = stateManager.appState.value
                    // Session arming / waiting countdown
                    if (SessionRules.isArmed(curState)) {
                        // atomic: a coin arming the window at the same moment must not be overwritten (APP-09)
                        stateManager.paymentTimeout.update { if (it > 0) it - 1 else it }
                        if (stateManager.paymentTimeout.value == 0) {
                            if (stateManager.coinsInserted.value > 0) {
                                onFinishPayment()
                            } else {
                                onCloseSession(true)
                                val hasPaidTime = stateManager.sessionTimeRemaining.value > 0
                                stateManager.appState.value = SessionRules.afterArmTimeoutNoCoin(curState, hasPaidTime)
                                stateManager.saveState()
                            }
                        }
                    }

                    // Active session countdown
                    if (SessionRules.isUnlocked(curState)) {
                        startHeldClockIfAny()
                        val deadline = stateManager.sessionExpiryDeadlineMs.value
                        val nowMonotonic = android.os.SystemClock.elapsedRealtime()
                        val remainingSec = remainingSecondsAt(deadline, nowMonotonic)

                        stateManager.sessionTimeRemaining.value = remainingSec

                        if (remainingSec <= 0) {
                            val expiryResult = paymentRepo.expireSessionIfDue()
                            if (lockDue(curState, expiryResult)) {
                                val stateBefore = stateManager.appState.value
                                val applied = stateManager.applySessionUpdate(
                                    deadlineMs = expiryResult.sessionState.sessionExpiryDeadlineMs,
                                    remainingSeconds = expiryResult.sessionState.sessionTimeRemaining,
                                    revision = expiryResult.sessionState.revision,
                                    targetAppState = lockedStateFor(stateBefore),
                                )
                                if (applied) {
                                    // the time-up sound, then "Time is up, thank you for playing!" (both in onTimeExpired)
                                    onTimeExpired()
                                    stateManager.saveState()
                                    onSessionExpired(false)
                                } else {
                                    Log.d(TAG, "Skipping stale expiration lock and announcement because newer revision is active")
                                }
                            } else {
                                stateManager.applySessionUpdate(
                                    expiryResult.sessionState.sessionExpiryDeadlineMs,
                                    expiryResult.sessionState.sessionTimeRemaining,
                                    expiryResult.sessionState.revision,
                                )
                            }
                        } else {
                            // Time-based checkpoint: a modulo check on remainingSec silently skips
                            // whenever a tick is late, leaving a stale balance for reboot recovery.
                            if (nowMonotonic - lastCheckpointMonotonicMs >= CHECKPOINT_INTERVAL_MS) {
                                lastCheckpointMonotonicMs = nowMonotonic
                                paymentRepo.checkpointSession(stateManager.sessionRevision.value)
                                stateManager.saveState()
                            }

                            // every announcement exactly once, none skipped by a late tick; the final seconds one by one
                            when (val action = announcer.onTick(remainingSec)) {
                                is TimeAnnouncer.Action.Warning -> onSpeakWarning(action.text)
                                is TimeAnnouncer.Action.Digit -> onCountdownDigit(action.number)
                                TimeAnnouncer.Action.CancelCountdown -> onCountdownCancelled()
                                null -> {}
                            }
                        }
                    }

                    tickIdle(curState)

                    onCheckBatteryAlerts()
                } catch (e: Exception) {
                    Log.e(TAG, "Exception in timer loop: ${e.message}")
                }
            }
        }
    }

    /** Seconds left: from the deadline when there is one, otherwise one less than the balance shown. */
    private fun remainingSecondsAt(deadline: Long, nowMonotonic: Long): Int = if (deadline > 0L) {
        maxOf(0, ((deadline - nowMonotonic) / 1000L).toInt())
    } else {
        maxOf(0, stateManager.sessionTimeRemaining.value - 1)
    }

    /**
     * Safety net: whatever unlocked the phone (finish, arm timeout or failure, a reboot), paid time that has no clock yet starts
     * now. Without a deadline the balance would never run out.
     */
    private suspend fun startHeldClockIfAny() {
        if (stateManager.sessionExpiryDeadlineMs.value > 0L || stateManager.sessionTimeRemaining.value <= 0) return
        val started = paymentRepo.startHeldClock() ?: return
        stateManager.applySessionUpdate(started.sessionExpiryDeadlineMs, started.sessionTimeRemaining, started.revision)
    }

    /**
     * The idle rules for a phone that is locked without paid time: after the owner's number of minutes (5) the social apps are
     * wiped, once per idle period, and when nobody touches the lock screen for as long the screen is turned off.
     */
    private fun tickIdle(state: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (state != SessionState.LOCKED.code) {
            // time was paid or a coin is awaited: a new idle period starts when the phone locks again
            lockedSinceMs = 0L
            guestClearDone = false
            if (SessionRules.isArmed(state)) LockIdle.touch()
            return
        }
        if (lockedSinceMs == 0L) lockedSinceMs = now
        val signedIn = AccountController.signedIn.value.isNotEmpty()
        if (IdlePolicy.shouldClearGuest(
                enabled = KioskSettings.isGuestClearEnabled(context),
                lockedForMs = now - lockedSinceMs,
                afterMinutes = KioskSettings.getGuestClearMinutes(context),
                signedIn = signedIn,
                alreadyDone = guestClearDone,
            )
        ) {
            guestClearDone = true
            scope.launch(Dispatchers.IO) { GuestReset.run(context) }
        }
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (IdlePolicy.shouldScreenOff(
                enabled = KioskSettings.isScreenOffEnabled(context),
                lockedAndIdle = !signedIn,
                screenOn = power?.isInteractive == true,
                idleMs = LockIdle.idleMs(),
                afterMinutes = KioskSettings.getScreenOffMinutes(context),
            )
        ) {
            LockIdle.touch() // one try per idle period, even if the lock call is refused
            Log.i(TAG, "The lock screen was left alone: turning the screen off")
            KioskSecurity.turnScreenOff(context)
        }
    }

    fun stop() {
        timerJob?.cancel()
        timerJob = null
    }
}
