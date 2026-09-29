package com.pisophone.kiosk.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.pisophone.kiosk.audio.KioskAudioManager
import com.pisophone.kiosk.repository.CoinEventRepository
import com.pisophone.kiosk.security.KioskActivationManager
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.server.KioskServerDelegate
import com.pisophone.kiosk.util.HardwareFeedback
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Handles all incoming local HTTP server requests (port 8080) and Master administrative commands,
 * isolating server business logic from KioskService.
 */
class KioskServerCoordinator(
    private val context: Context,
    private val stateManager: KioskStateManager,
    private val coinEventRepo: CoinEventRepository,
    private val getSecretKey: () -> String,
    private val getRealTimeBatteryInfo: () -> Pair<Int, Boolean>,
    private val getAudioManager: () -> KioskAudioManager?,
    private val onAddCoinTime: (seconds: Int, source: String, txId: String?, amount: Double) -> Boolean
) : KioskServerDelegate {

    companion object {
        private const val TAG = "KioskServerCoordinator"
    }

    override fun getSecretKey(): String = getSecretKey.invoke()

    override fun onHeartbeat(clientIp: String?) {
        stateManager.isEsp32Online.value = true
        if (!clientIp.isNullOrEmpty() && clientIp != "127.0.0.1") {
            if (stateManager.esp32Ip != clientIp) {
                stateManager.esp32Ip = clientIp
                stateManager.saveState()
            }
        }
    }

    override fun getStatusJson(): JSONObject {
        val (curBat, isChg) = getRealTimeBatteryInfo.invoke()
        return JSONObject().apply {
            put("device_id", stateManager.deviceId.value)
            put("alias", KioskSecurity.getDeviceAlias(context))
            put("state", stateManager.appState.value)
            put("time_remaining", stateManager.sessionTimeRemaining.value)
            put("battery", curBat)
            put("charging", isChg)
            put("online", true)
        }
    }

    override fun getSessionTimeRemaining(): Int = stateManager.sessionTimeRemaining.value
    override fun getAppState(): Int = stateManager.appState.value

    override fun getAuditEventsJson(): String {
        return kotlinx.coroutines.runBlocking {
            val events = coinEventRepo.getLatestEvents(100)
            val jsonArray = JSONArray()
            for (event in events) {
                val obj = JSONObject()
                obj.put("id", event.id)
                obj.put("txId", event.txId)
                obj.put("secondsAdded", event.secondsAdded)
                obj.put("source", event.source)
                obj.put("timestamp", event.timestamp)
                jsonArray.put(obj)
            }
            jsonArray.toString()
        }
    }

    override fun onCoinCredited(seconds: Int, source: String, txId: String?, amount: Double): Boolean {
        return onAddCoinTime(seconds, source, txId, amount)
    }

    override fun onDeductTime(seconds: Int) {
        val now = System.currentTimeMillis()
        val curDeadline = stateManager.sessionExpiryDeadlineMs.value
        val newDeadline = if (curDeadline > now) {
            maxOf(0L, curDeadline + (seconds * 1000L))
        } else {
            0L
        }
        stateManager.sessionExpiryDeadlineMs.value = newDeadline
        val remaining = if (newDeadline > now) ((newDeadline - now) / 1000L).toInt() else 0
        stateManager.sessionTimeRemaining.value = remaining
        if (remaining <= 0) {
            stateManager.sessionTimeRemaining.value = 0
            stateManager.sessionExpiryDeadlineMs.value = 0L
            stateManager.appState.value = 0
        }
        stateManager.saveState()
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "${-seconds / 60} minutes deducted!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onConfigUpdated(price: Double?, minutes: Int?, deviceName: String?, adminPin: String?, slotNum: Int?) {
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
        adminPin?.let { if (it.isNotBlank()) KioskSecurity.setAdminPin(context, it) }
        stateManager.saveState()
        val currentName = KioskSecurity.getDeviceAlias(context).takeIf { it.isNotBlank() } ?: "PisoPhone ${if (effectiveSlot > 0) effectiveSlot else 1}"
        Log.d(TAG, "Master pushed config update: Price=₱${stateManager.pricePerCoin.value}, Minutes=${stateManager.minutesPerCoin.value}m, DeviceName=$currentName, Pin=$adminPin, Slot=$effectiveSlot")
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "Config Synced: $currentName", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onTriggerAction(action: String, slotNum: Int?) {
        if (slotNum != null && slotNum > 0) {
            stateManager.slotNumber.value = slotNum
            KioskSecurity.setAssignedBoxSlot(context, slotNum)
            KioskSecurity.setDeviceAlias(context, "PisoPhone $slotNum")
        }
        Handler(Looper.getMainLooper()).post {
            when (action) {
                "slot_lockdown" -> {
                    stateManager.isSlotExpired.value = true
                    stateManager.sessionTimeRemaining.value = 0
                    stateManager.sessionExpiryDeadlineMs.value = 0L
                    stateManager.appState.value = 0
                    stateManager.saveState()
                    KioskActivationManager.setSlotLockdown(
                        context,
                        locked = true,
                        reason = "Device activation required.",
                        slotNum = stateManager.slotNumber.value ?: 1,
                        expiryTs = 0L
                    )
                    Toast.makeText(context, "Device activation required.", Toast.LENGTH_LONG).show()
                }
                "slot_restore", "slot_renew" -> {
                    stateManager.isSlotExpired.value = false
                    stateManager.slotExpiryMessage.value = ""
                    stateManager.slotWarningDaysLeft.value = null
                    KioskActivationManager.setSlotLockdown(
                        context,
                        false,
                        slotNum = stateManager.slotNumber.value ?: 1
                    )
                    stateManager.saveState()
                    Toast.makeText(context, "Device activated.", Toast.LENGTH_SHORT).show()
                }
                "vibrate" -> HardwareFeedback.triggerVibration(context, longArrayOf(0, 1500))
                "sound" -> {
                    getAudioManager.invoke()?.playHighBatteryAttentionTone()
                }
                "flash" -> HardwareFeedback.triggerFlashlight(context, 2000L)
                "enable_adb" -> {
                    KioskSecurity.emergencyEnableUsbDebugging(context)
                    Toast.makeText(context, "⚡ Remote: USB Debugging Re-Enabled!", Toast.LENGTH_LONG).show()
                }
                "recovery", "emergency_recovery" -> {
                    KioskSecurity.emergencyEnableUsbDebugging(context)
                    KioskSecurity.emergencyExitKiosk(context)
                    Toast.makeText(context, "⚠️ Remote: Emergency Recovery & ADB Enabled!", Toast.LENGTH_LONG).show()
                }
                "exit_kiosk" -> {
                    KioskSecurity.emergencyExitKiosk(context)
                }
                "deprovision" -> {
                    KioskSecurity.emergencyClearDeviceOwner(context)
                }
                "factory_reset" -> {
                    KioskSecurity.factoryResetDevice(context)
                }
            }
            Toast.makeText(context, "Device Identified: ${KioskSecurity.getDeviceAlias(context)}", Toast.LENGTH_LONG).show()
        }
    }

    override fun getCrashLog(): String? {
        return try {
            val logDir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(logDir, "crash.log")
            if (file.exists()) file.readText() else null
        } catch (_: Exception) {
            null
        }
    }
}
