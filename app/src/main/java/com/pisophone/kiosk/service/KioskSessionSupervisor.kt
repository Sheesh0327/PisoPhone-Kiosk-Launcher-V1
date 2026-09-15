package com.pisophone.kiosk.service

import android.content.Context
import android.content.Intent
import android.util.Log
import com.pisophone.kiosk.repository.PaymentRepository
import com.pisophone.kiosk.system.KioskSystemMonitor
import kotlinx.coroutines.CoroutineScope
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
    private val onCloseSession: (Boolean) -> Unit,
    private val onCheckBatteryAlerts: () -> Unit
) {
    companion object {
        private const val TAG = "KioskSessionSupervisor"
    }

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
                    if (curState == 1 || curState == 3) {
                        if (stateManager.paymentTimeout.value > 0) {
                            stateManager.paymentTimeout.value -= 1
                        }
                        if (stateManager.paymentTimeout.value == 0) {
                            if (stateManager.coinsInserted.value > 0) {
                                onFinishPayment()
                            } else {
                                onCloseSession(true)
                                if (curState == 3) {
                                    stateManager.appState.value = 2
                                } else {
                                    stateManager.appState.value = 0
                                }
                                stateManager.saveState()
                            }
                        }
                    }
                    
                    // Active session countdown
                    if (curState == 2 || curState == 3) {
                        val deadline = stateManager.sessionExpiryDeadlineMs.value
                        val nowMonotonic = android.os.SystemClock.elapsedRealtime()
                        val remainingSec = if (deadline > 0L) {
                            maxOf(0, ((deadline - nowMonotonic) / 1000L).toInt())
                        } else {
                            maxOf(0, stateManager.sessionTimeRemaining.value - 1)
                        }

                        stateManager.sessionTimeRemaining.value = remainingSec

                        if (remainingSec <= 0) {
                            val expiryResult = paymentRepo.expireSessionIfDueBlocking()
                            if (expiryResult.didExpire) {
                                stateManager.appState.value = 0 // Lock screen
                                stateManager.sessionExpiryDeadlineMs.value = 0L
                                stateManager.sessionTimeRemaining.value = 0
                                stateManager.sessionRevision.value = expiryResult.sessionState.revision
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
                            } else {
                                stateManager.applySessionUpdate(
                                    expiryResult.sessionState.sessionExpiryDeadlineMs,
                                    expiryResult.sessionState.sessionTimeRemaining,
                                    expiryResult.sessionState.revision
                                )
                            }
                        } else {
                            if (remainingSec % 15 == 0) {
                                paymentRepo.checkpointSessionBlocking(stateManager.sessionRevision.value)
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
