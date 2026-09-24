package com.pisophone.kiosk.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.pisophone.kiosk.audio.KioskAudioManager
import com.pisophone.kiosk.db.CoinEvent
import com.pisophone.kiosk.repository.CoinEventRepository
import com.pisophone.kiosk.repository.SessionSnapshot
import com.pisophone.kiosk.util.HardwareFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles UI notifications, audit log recording, audio sonification,
 * and hardware feedback triggers upon payment commitment.
 */
class KioskCreditNotifier(
    private val context: Context,
    private val scope: CoroutineScope,
    private val stateManager: KioskStateManager,
    private val coinEventRepo: CoinEventRepository,
    private val audioManager: KioskAudioManager,
    private val armingTimeoutSeconds: Int
) {
    companion object {
        private const val TAG = "KioskCreditNotifier"
    }

    fun publishCommittedCreditSnapshot(
        txId: String,
        seconds: Int,
        amount: Double,
        operationKind: String,
        snapshot: SessionSnapshot
    ) {
        val isQuickAdd = (amount <= 0.0) || operationKind.equals("QUICK_ADJUST", ignoreCase = true)
        val pesoAmount = if (amount >= 1.0) amount.toInt() else 0

        val targetState = if (isQuickAdd) {
            if (stateManager.appState.value == 0 || stateManager.appState.value == 1) 2 else null
        } else {
            if (stateManager.appState.value == 0) 1 else null
        }
        val applied = stateManager.applySessionUpdate(snapshot, targetState)
        if (applied) {
            if (isQuickAdd) {
                if (targetState == 2) {
                    stateManager.paymentTimeout.value = 0
                }
                Log.d(TAG, "Quick Add adjustment credited: +${seconds}s (targetState=$targetState, rev=${snapshot.revision})")
            } else {
                stateManager.paymentTimeout.value = armingTimeoutSeconds
                if (stateManager.appState.value == 1 || stateManager.appState.value == 3) {
                    stateManager.coinsInserted.value += pesoAmount
                } else if (stateManager.appState.value == 2) {
                    Log.d(TAG, "Coin credited directly to active session: +${seconds}s (₱$pesoAmount, rev=${snapshot.revision})")
                }
            }
            stateManager.saveState()
        }

        scope.launch(Dispatchers.IO) {
            try {
                val eventSource = if (isQuickAdd) "Admin Quick Adjust" else "Piso Coin (₱$pesoAmount)"
                coinEventRepo.insertEvent(
                    CoinEvent(
                        txId = txId,
                        secondsAdded = seconds,
                        source = eventSource
                    )
                )
                coinEventRepo.deleteOldEvents(500)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log coin event to audit ledger: ${e.message}")
            }
        }

        if (!isQuickAdd) {
            try {
                audioManager.playCoinSound()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play coin sound: ${e.message}")
            }
            try {
                HardwareFeedback.triggerFlashlight(context, 150L)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to trigger flashlight: ${e.message}")
            }
        }

        try {
            Handler(Looper.getMainLooper()).post {
                val addedMins = seconds / 60
                if (isQuickAdd) {
                    Toast.makeText(context, "${addedMins}m added by admin!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "₱$pesoAmount coin accepted! (+${addedMins}m)", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post credit toast: ${e.message}")
        }
    }

    fun publishCommittedDeductSnapshot(
        seconds: Int,
        snapshot: SessionSnapshot
    ) {
        val targetState = if (snapshot.remainingSeconds <= 0) 0 else null
        val applied = stateManager.applySessionUpdate(snapshot, targetState)
        if (applied) {
            stateManager.saveState()
        }
        try {
            val displayMinutes = seconds / 60
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "$displayMinutes minutes deducted!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post deduction toast: ${e.message}")
        }
    }
}
