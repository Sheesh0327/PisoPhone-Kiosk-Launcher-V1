package com.pisophone.kiosk.service

import android.content.Context
import android.util.Log
import com.pisophone.kiosk.network.Esp32ConnectionDelegate
import com.pisophone.kiosk.repository.PaymentRepository
import com.pisophone.kiosk.repository.PaymentResult
import com.pisophone.kiosk.security.KioskActivationManager
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.util.DiagnosticsLog
import com.pisophone.kiosk.util.HardwareFeedback

/**
 * Handles all ESP32 event and telemetry lifecycle callbacks, separating Master Box network
 * business logic from KioskService.
 */
class KioskEsp32Coordinator(
    private val context: Context,
    private val stateManager: KioskStateManager,
    private val paymentRepo: PaymentRepository,
    private val armingTimeoutSeconds: Int,
    private val getSecretKey: () -> String,
    private val getRealTimeBatteryInfo: () -> Pair<Int, Boolean>,
    private val onCreditPayment: (txId: String, seconds: Int, amount: Double) -> PaymentResult,
    private val onSlotBusyTriggered: () -> Unit,
    private val getAudioManager: (() -> com.pisophone.kiosk.audio.KioskAudioManager?)? = null,
    /** Centralized lock side effects (unarm, send customer app home, pause media). */
    private val onSessionLocked: (cancelArm: Boolean) -> Unit = {},
) : Esp32ConnectionDelegate {
    companion object {
        private const val TAG = "KioskEsp32Coordinator"
    }

    private var lastArmTimestampMs: Long = 0L

    override fun getDeviceId(): String = stateManager.deviceId.value
    override fun getSecretKey(): String = getSecretKey.invoke()
    override fun getAppState(): Int = stateManager.appState.value
    override fun getSessionTimeRemaining(): Int = stateManager.sessionTimeRemaining.value
    override fun getRealTimeBatteryInfo(): Pair<Int, Boolean> = getRealTimeBatteryInfo.invoke()
    override fun getStoredEsp32Ip(): String? = stateManager.esp32Ip

    override fun onEsp32Discovered(ip: String) {
        stateManager.esp32Ip = ip
        stateManager.isEsp32Online.value = true
        stateManager.saveState()
    }

    override fun onOnlineStatusChanged(isOnline: Boolean, mac: String?) {
        if (stateManager.isEsp32Online.value != isOnline) {
            DiagnosticsLog.add("ESP32", if (isOnline) "link up" else "link down")
        }
        stateManager.isEsp32Online.value = isOnline
        if (!mac.isNullOrBlank()) stateManager.esp32MacAddress.value = mac
    }

    override fun onConfigSynced(price: Double?, minutes: Int?, alias: String?, adminPin: String?, slotNum: Int?) {
        price?.let { stateManager.pricePerCoin.value = it }
        minutes?.let { stateManager.minutesPerCoin.value = it }
        val effectiveSlot = if (slotNum != null && slotNum > 0) slotNum else stateManager.slotNumber.value
        if (effectiveSlot > 0) {
            stateManager.slotNumber.value = effectiveSlot
            KioskSecurity.setAssignedBoxSlot(context, effectiveSlot)
            val autoName = "PisoPhone $effectiveSlot"
            KioskSecurity.setDeviceAlias(context, autoName)
            Log.d(TAG, "[+] Device Name automatically linked to Slot #$effectiveSlot -> $autoName")
        }
        adminPin?.takeIf { it.isNotBlank() }?.let {
            val currentPin = KioskSecurity.getAdminPin(context)
            if (currentPin != it) {
                KioskSecurity.setAdminPin(context, it)
                Log.d(TAG, "[+] Synchronized Admin PIN from Master heartbeat: $it")
            }
        }
        stateManager.saveState()
    }

    override fun onCoinMessageReceived(seconds: Int, amount: Double, txId: String?): PaymentResult {
        if (txId.isNullOrBlank()) {
            Log.e(TAG, "Invalid coin message over WebSocket: missing transaction ID")
            return PaymentResult.FAILED
        }

        Log.d(TAG, "Received validated coin via WebSocket: seconds=$seconds, amount=₱$amount, tx_id=$txId")
        val result = onCreditPayment(txId, seconds, amount)
        DiagnosticsLog.add("COIN", "tx $txId: ${seconds}s, amount $amount -> $result")
        when (result) {
            PaymentResult.APPLIED -> {
                // paymentTimeout / appState are published by KioskEngine.onPaymentApplied.
                Log.i(TAG, "WebSocket coin applied: +${seconds}s, ₱$amount (txId=$txId)")
            }
            PaymentResult.ALREADY_APPLIED -> {
                Log.d(TAG, "WebSocket coin already applied: txId=$txId")
            }
            PaymentResult.CONFLICT -> {
                Log.w(TAG, "WebSocket coin conflict: txId=$txId")
            }
            PaymentResult.NOT_ELIGIBLE -> {
                Log.w(TAG, "WebSocket coin rejected: Not eligible (txId=$txId)")
            }
            PaymentResult.FAILED -> {
                Log.e(TAG, "WebSocket coin database failure: txId=$txId")
            }
        }
        return result
    }

    /**
     * The arm request failed (slot busy, ESP32 unreachable, connection error).
     * Never lock a paid session because an ADD TIME arm attempt failed:
     *  - 2 (unlocked)          -> stays 2
     *  - 3 (unlocked + armed)  -> 2
     *  - 1 (locked + armed)    -> 0, unless the customer already has paid time, then 2
     *  - 0 / 4                 -> unchanged
     */
    override fun onSlotBusy() {
        DiagnosticsLog.add("ARM", "slot busy (state ${stateManager.appState.value})")
        stateManager.isArmingInProgress.value = false
        onSlotBusyTriggered()
        val current = stateManager.appState.value
        val hasPaidTime = stateManager.sessionTimeRemaining.value > 0
        val next = SessionRules.afterArmFailure(current, hasPaidTime)
        if (next != current) {
            stateManager.appState.value = next
        }
        stateManager.coinsInserted.value = 0
        stateManager.paymentTimeout.value = 0
        stateManager.saveState()
    }

    override fun onArmSuccess() {
        DiagnosticsLog.add("ARM", "armed (state ${stateManager.appState.value})")
        lastArmTimestampMs = System.currentTimeMillis()
        stateManager.isEsp32Online.value = true
        // Publish the arming window BEFORE flipping appState so the supervisor tick never
        // observes state 1/3 with a stale paymentTimeout of 0 (which would close the session).
        stateManager.coinsInserted.value = 0
        stateManager.paymentTimeout.value = armingTimeoutSeconds
        stateManager.appState.value = SessionRules.afterArmSuccess(stateManager.appState.value)
        stateManager.isArmingInProgress.value = false
    }

    override fun onSlotWarning(daysLeft: Int, expiresAt: Long, slotNum: Int, message: String) {
        stateManager.slotWarningDaysLeft.value = daysLeft
        stateManager.slotExpiryMessage.value = message
        stateManager.slotNumber.value = slotNum
    }

    override fun onSlotLockdown(reason: String, slotNum: Int, expiresAt: Long) {
        DiagnosticsLog.add("SLOT", "lockdown on slot $slotNum: $reason")
        // Lockdown is a terminal failure for any in-flight arm attempt.
        stateManager.isArmingInProgress.value = false

        val previousState = stateManager.appState.value
        val wasAlreadyLocked = stateManager.isSlotExpired.value
        val hadActiveSession = previousState != 0 || stateManager.sessionTimeRemaining.value > 0

        stateManager.isSlotExpired.value = true
        stateManager.slotExpiryMessage.value = if (reason.isNotBlank()) reason else "Device activation required."
        stateManager.slotNumber.value = slotNum
        stateManager.slotWarningDaysLeft.value = 0
        // Heartbeats keep reporting lockdown every few seconds; only hit the database on transition.
        if (!wasAlreadyLocked || hadActiveSession) {
            paymentRepo.expireSessionBlocking()
        }
        stateManager.sessionTimeRemaining.value = 0
        stateManager.sessionExpiryDeadlineMs.value = 0L
        stateManager.coinsInserted.value = 0
        stateManager.paymentTimeout.value = 0
        stateManager.appState.value = SessionRules.hardLocked()
        stateManager.saveState()
        KioskActivationManager.setSlotLockdown(context, true, reason, slotNum, expiresAt)

        if (previousState != 0) {
            onSessionLocked(SessionRules.isArmed(previousState))
        }
    }

    override fun onSlotRestored(slotNum: Int) {
        DiagnosticsLog.add("SLOT", "restored slot $slotNum")
        if (slotNum > 0) {
            stateManager.slotNumber.value = slotNum
            KioskSecurity.setAssignedBoxSlot(context, slotNum)
            KioskSecurity.setDeviceAlias(context, "PisoPhone $slotNum")
        }
        if (stateManager.isSlotExpired.value) {
            stateManager.isSlotExpired.value = false
            stateManager.slotExpiryMessage.value = ""
            stateManager.slotWarningDaysLeft.value = null
            KioskActivationManager.setSlotLockdown(context, false, slotNum = if (slotNum > 0) slotNum else stateManager.slotNumber.value)
            Log.i(TAG, "Slot activated on ESP32: Ready for coins (Slot #$slotNum).")
        }
    }

    override fun onArenaModeSynced(active: Boolean, role: Int, stake: Int) {
        val wasActive = stateManager.isArenaMode.value
        if (active) {
            if (!wasActive) {
                stateManager.setArenaMode(active = true, role = role, stake = stake, showBanner = true)
                HardwareFeedback.triggerVibration(context, longArrayOf(0, 200, 100, 200, 100, 400))
                val roleStr = if (role == 1) {
                    "Player 1"
                } else if (role == 2) {
                    "Player 2"
                } else {
                    "Participant"
                }
                getAudioManager?.invoke()?.speakWarning("Arena Mode activated. You are $roleStr.")
            } else {
                stateManager.setArenaMode(active = true, role = role, stake = stake, showBanner = false)
            }
        } else if (wasActive) {
            stateManager.setArenaMode(false)
        }
    }
}
