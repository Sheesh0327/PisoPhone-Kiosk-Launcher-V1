package com.pisophone.kiosk.service

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.pisophone.kiosk.repository.PaymentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * High-precision supervisor loop managing:
 * 1. 15-second arming payment window countdown.
 * 2. Monotonic rental session countdown and voice warnings.
 * 3. Session checkpointing and automatic timeout lock enforcement.
 */
class KioskSessionSupervisor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val stateManager: KioskStateManager,
    private val paymentRepo: PaymentRepository,
    private val onSpeakWarning: (String) -> Unit,
    private val onFinishPayment: () -> Unit,
    private val onCloseSession: (Boolean) -> Unit,
    private val onCheckBatteryAlerts: () -> Unit
) {
    companion object {
        private const val TAG = "KioskSessionSupervisor"
        private const val TICK_INTERVAL_MS = 1000L
        private const val MAX_STALL_LAG_MS = 5000L
        private const val CHECKPOINT_INTERVAL_SEC = 15
    }

    private var timerJob: Job? = null

    @Volatile
    private var lastTickMonotonicMs: Long = 0L

    /**
     * Checks if the supervisor timer loop has stalled due to OS throttling or thread suspension.
     */
    fun isStalled(maxLagMs: Long = MAX_STALL_LAG_MS): Boolean {
        val last = lastTickMonotonicMs
        if (last == 0L) return false
        return (SystemClock.elapsedRealtime() - last) > maxLagMs
    }

    /**
     * Ensures the supervisor coroutine is actively executing, restarting it if stalled or cancelled.
     */
    fun ensureRunning() {
        if (timerJob == null || timerJob?.isActive != true || isStalled()) {
            Log.w(TAG, "Supervisor loop inactive or stalled. Restarting ticker.")
            start()
        }
    }

    /**
     * Starts the 1 Hz monotonic supervisor loop.
     */
    fun start() {
        timerJob?.cancel()
        lastTickMonotonicMs = SystemClock.elapsedRealtime()

        timerJob = scope.launch {
            while (isActive) {
                try {
                    delay(TICK_INTERVAL_MS)
                    lastTickMonotonicMs = SystemClock.elapsedRealtime()

                    val curState = stateManager.appState.value

                    // 1. Arming / Coin-drop waiting window
                    if (curState == 1 || curState == 3) {
                        if (!stateManager.isArming.value) {
                            if (stateManager.paymentTimeout.value > 0) {
                                stateManager.paymentTimeout.value -= 1
                            }
                            if (stateManager.paymentTimeout.value == 0) {
                                if (stateManager.coinsInserted.value > 0) {
                                    onFinishPayment()
                                } else {
                                    onCloseSession(true)
                                    stateManager.appState.value = if (curState == 3) 2 else 0
                                    stateManager.saveState()
                                }
                            }
                        }
                    }

                    // 2. Active paid rental session countdown
                    if (curState == 2 || curState == 3) {
                        val deadline = stateManager.sessionExpiryDeadlineMs.value
                        val nowMonotonic = SystemClock.elapsedRealtime()
                        val remainingSec = if (deadline > 0L) {
                            maxOf(0, ((deadline - nowMonotonic) / 1000L).toInt())
                        } else {
                            maxOf(0, stateManager.sessionTimeRemaining.value - 1)
                        }

                        stateManager.sessionTimeRemaining.value = remainingSec

                        if (remainingSec <= 0) {
                            val expiryResult = paymentRepo.expireSessionIfDueBlocking()
                            if (expiryResult.didExpire) {
                                val applied = stateManager.applySessionUpdate(
                                    deadlineMs = expiryResult.sessionState.sessionExpiryDeadlineMs,
                                    remainingSeconds = expiryResult.sessionState.sessionTimeRemaining,
                                    revision = expiryResult.sessionState.revision,
                                    targetAppState = 0
                                )
                                if (applied) {
                                    onSpeakWarning("Time expired")
                                    stateManager.saveState()

                                    val startMain = Intent(Intent.ACTION_MAIN).apply {
                                        addCategory(Intent.CATEGORY_HOME)
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                                Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    }
                                    try {
                                        context.startActivity(startMain)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed bringing Home activity to front: ${e.message}")
                                    }
                                }
                            } else {
                                stateManager.applySessionUpdate(
                                    expiryResult.sessionState.sessionExpiryDeadlineMs,
                                    expiryResult.sessionState.sessionTimeRemaining,
                                    expiryResult.sessionState.revision
                                )
                            }
                        } else {
                            // Periodic state checkpointing
                            if (remainingSec % CHECKPOINT_INTERVAL_SEC == 0) {
                                paymentRepo.checkpointSessionBlocking(stateManager.sessionRevision.value)
                                stateManager.saveState()
                            }

                            // Progressive voice reminders
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

                    // 3. Periodic hardware battery health checks
                    onCheckBatteryAlerts()
                } catch (e: Exception) {
                    Log.e(TAG, "Supervisor loop iteration error: ${e.message}")
                }
            }
        }
    }

    /**
     * Stops the supervisor loop.
     */
    fun stop() {
        timerJob?.cancel()
        timerJob = null
    }
}
