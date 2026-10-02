package com.pisophone.kiosk.service

import android.content.Context
import android.util.Log
import com.pisophone.kiosk.repository.PaymentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val onFinishPayment: () -> Unit,
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
    }

    @Volatile
    private var lastCheckpointMonotonicMs: Long = 0L

    private var timerJob: Job? = null

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
                        if (stateManager.paymentTimeout.value > 0) {
                            stateManager.paymentTimeout.value -= 1
                        }
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
                        val deadline = stateManager.sessionExpiryDeadlineMs.value
                        val nowMonotonic = android.os.SystemClock.elapsedRealtime()
                        val remainingSec = if (deadline > 0L) {
                            maxOf(0, ((deadline - nowMonotonic) / 1000L).toInt())
                        } else {
                            maxOf(0, stateManager.sessionTimeRemaining.value - 1)
                        }

                        stateManager.sessionTimeRemaining.value = remainingSec

                        if (remainingSec <= 0) {
                            val expiryResult = paymentRepo.expireSessionIfDue()
                            if (expiryResult.didExpire) {
                                val stateBefore = stateManager.appState.value
                                val applied = stateManager.applySessionUpdate(
                                    deadlineMs = expiryResult.sessionState.sessionExpiryDeadlineMs,
                                    remainingSeconds = expiryResult.sessionState.sessionTimeRemaining,
                                    revision = expiryResult.sessionState.revision,
                                    targetAppState = lockedStateFor(stateBefore),
                                )
                                if (applied) {
                                    onSpeakWarning("Time expired")
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

                            when (remainingSec) {
                                300 -> onSpeakWarning("5 minutes time remaining")
                                180 -> onSpeakWarning("3 minutes time remaining")
                                60 -> onSpeakWarning("1 minute time remaining")
                                10 -> onSpeakWarning("10 seconds time remaining")
                                5 -> onSpeakWarning("Five")
                                4 -> onSpeakWarning("Four")
                                3 -> onSpeakWarning("Three")
                                2 -> onSpeakWarning("Two")
                                1 -> onSpeakWarning("One")
                            }
                        }
                    }

                    onCheckBatteryAlerts()
                } catch (e: Exception) {
                    Log.e(TAG, "Exception in timer loop: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        timerJob?.cancel()
        timerJob = null
    }
}
