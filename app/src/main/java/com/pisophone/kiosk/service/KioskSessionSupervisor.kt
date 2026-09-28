package com.pisophone.kiosk.service

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.pisophone.kiosk.repository.PaymentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class KioskSessionSupervisor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val stateManager: KioskStateManager,
    private val paymentRepo: PaymentRepository,
    private val onSpeakWarning: (String) -> Unit,
    private val onFinishPayment: () -> Unit,
    private val onCancelPayment: () -> Unit = {},
    private val onCloseSession: (Boolean) -> Unit,
    private val onCheckBatteryAlerts: () -> Unit,
    private val onPeriodicHealthCheck: () -> Unit = {}
) {
    companion object {
        private const val TAG = "KioskSessionSupervisor"
    }

    private var timerJob: Job? = null
    @Volatile
    private var lastTickMonotonicMs: Long = 0L
    private var tickCounter: Long = 0L

    fun isStalled(maxLagMs: Long = 5000L): Boolean {
        val last = lastTickMonotonicMs
        if (last == 0L) return false
        return (SystemClock.elapsedRealtime() - last) > maxLagMs
    }

    fun ensureRunning() {
        if (timerJob == null || timerJob?.isActive != true || isStalled()) {
            Log.w(TAG, "Timer job inactive or stalled. Restarting supervisor loop.")
            start()
        }
    }

    fun start() {
        timerJob?.cancel()
        lastTickMonotonicMs = SystemClock.elapsedRealtime()
        timerJob = scope.launch {
            while (isActive) {
                try {
                    delay(1000)
                    lastTickMonotonicMs = SystemClock.elapsedRealtime()

                    val curState = stateManager.appState.value
                    // 1. Session arming / waiting countdown (Payment mode)
                    if (curState == 1 || curState == 3) {
                        val deadline = stateManager.paymentTimeoutDeadlineMs.value
                        val nowMonotonic = SystemClock.elapsedRealtime()
                        if (deadline > 0L) {
                            val remainingSec = maxOf(0, Math.ceil((deadline - nowMonotonic) / 1000.0).toInt())
                            stateManager.paymentTimeout.value = remainingSec
                            if (nowMonotonic >= deadline || remainingSec <= 0) {
                                stateManager.paymentTimeoutDeadlineMs.value = 0L
                                stateManager.paymentTimeout.value = 0
                                if (stateManager.coinsInserted.value > 0) {
                                    onFinishPayment()
                                } else {
                                    onCancelPayment()
                                }
                            }
                        } else {
                            val curTimeout = stateManager.paymentTimeout.value
                            if (curTimeout > 0) {
                                val remainingSec = maxOf(0, curTimeout - 1)
                                stateManager.paymentTimeout.value = remainingSec
                                if (remainingSec <= 0) {
                                    if (stateManager.coinsInserted.value > 0) {
                                        onFinishPayment()
                                    } else {
                                        onCancelPayment()
                                    }
                                }
                            }
                        }
                    }

                    // 2. Active session countdown
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
                            scope.launch(Dispatchers.IO) {
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
                                            Log.e(TAG, "Failed to start HOME activity: ${e.message}")
                                        }
                                    }
                                } else {
                                    stateManager.applySessionUpdate(
                                        expiryResult.sessionState.sessionExpiryDeadlineMs,
                                        expiryResult.sessionState.sessionTimeRemaining,
                                        expiryResult.sessionState.revision
                                    )
                                }
                            }
                        } else {
                            if (remainingSec % 15 == 0) {
                                scope.launch(Dispatchers.IO) {
                                    paymentRepo.checkpointSessionBlocking(stateManager.sessionRevision.value)
                                    stateManager.saveState()
                                }
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
                    tickCounter++
                    if (tickCounter % 10L == 0L) {
                        onPeriodicHealthCheck()
                    }
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
