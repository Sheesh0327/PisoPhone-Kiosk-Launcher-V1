package com.pisophone.kiosk.service

import android.content.Context
import android.util.Log
import com.pisophone.kiosk.network.Esp32ConnectionDelegate
import com.pisophone.kiosk.security.KioskActivationManager
import com.pisophone.kiosk.security.KioskSecurity

/**
 * Handles all ESP32 event and telemetry lifecycle callbacks, separating Master Box network
 * business logic from KioskService.
 */
class KioskEsp32Coordinator(
    private val context: Context,
    private val stateManager: KioskStateManager,
    private val armingTimeoutSeconds: Int,
    private val getSecretKey: () -> String,
    private val getRealTimeBatteryInfo: () -> Pair<Int, Boolean>,
    private val onAddCoinTime: (seconds: Int, source: String, txId: String?, amount: Double) -> Unit,
    private val onSlotBusyTriggered: () -> Unit
) : Esp32ConnectionDelegate {

    companion object {
        private const val TAG = "KioskEsp32Coordinator"
    }

    override fun getDeviceId(): String = stateManager.deviceId.value
    override fun getSecretKey(): String = getSecretKey.invoke()
    override fun getAppState(): Int = stateManager.appState.value
    override fun getSessionTimeRemaining(): Int = stateManager.sessionTimeRemaining.value
    override fun getRealTimeBatteryInfo(): Pair<Int, Boolean> = getRealTimeBatteryInfo.invoke()

    override fun onEsp32Discovered(ip: String) {
        stateManager.esp32Ip = ip
        stateManager.isEsp32Online.value = true
        stateManager.saveState()
    }

    override fun onOnlineStatusChanged(isOnline: Boolean, mac: String?) {
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

    override fun onCoinMessageReceived(seconds: Int, amount: Double, txId: String?) {
        if (!KioskActivationManager.isAppAllowedToRun(context)) {
            Log.e(TAG, "Device not provisioned: Discarding coin event.")
            return
        }
        if (txId.isNullOrBlank()) {
            Log.e(TAG, "Invalid coin message over WebSocket: missing transaction ID")
            return
        }

        Log.d(TAG, "Received validated coin via WebSocket: seconds=$seconds, amount=₱$amount, tx_id=$txId")
        onAddCoinTime(seconds, "WebSocket Port 81", txId, amount)
        stateManager.paymentTimeout.value = armingTimeoutSeconds
    }

    override fun onSlotBusy() {
        onSlotBusyTriggered()
        if (stateManager.appState.value == 3) {
            stateManager.appState.value = 2
        } else {
            stateManager.appState.value = 0
        }
    }

    override fun onArmSuccess() {
        stateManager.isEsp32Online.value = true
    }

    override fun onSlotWarning(daysLeft: Int, expiresAt: Long, slotNum: Int, message: String) {
        stateManager.slotWarningDaysLeft.value = daysLeft
        stateManager.slotExpiryMessage.value = message
        stateManager.slotNumber.value = slotNum
    }

    override fun onSlotLockdown(reason: String, slotNum: Int, expiresAt: Long) {
        stateManager.isSlotExpired.value = true
        stateManager.slotExpiryMessage.value = if (reason.isNotBlank()) reason else "Device activation required."
        stateManager.slotNumber.value = slotNum
        stateManager.slotWarningDaysLeft.value = 0
        stateManager.sessionTimeRemaining.value = 0
        stateManager.sessionExpiryDeadlineMs.value = 0L
        stateManager.appState.value = 0
        stateManager.saveState()
        KioskActivationManager.setSlotLockdown(context, true, reason, slotNum, expiresAt)
    }

    override fun onSlotRestored(slotNum: Int) {
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
}
