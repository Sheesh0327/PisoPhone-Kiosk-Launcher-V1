package com.pisophone.kiosk.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.pisophone.kiosk.audio.KioskAudioManager
import com.pisophone.kiosk.repository.CoinEventRepository
import com.pisophone.kiosk.repository.PaymentRepository
import com.pisophone.kiosk.repository.PaymentResult
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
    private val paymentRepo: PaymentRepository,
    private val getSecretKey: () -> String,
    private val getRealTimeBatteryInfo: () -> Pair<Int, Boolean>,
    private val getAudioManager: () -> KioskAudioManager?,
    private val onCreditPayment: (txId: String, seconds: Int, amount: Double) -> PaymentResult,
    private val isReady: () -> Boolean = { true },
    /** Contact from an address that is not the known ESP32; must be verified, never trusted. */
    private val onUnverifiedEsp32Contact: ((String) -> Unit)? = null,
    /** Centralized lock side effects (unarm, send customer app home, pause media). */
    private val onSessionLocked: (cancelArm: Boolean) -> Unit = {},
) : KioskServerDelegate {
    companion object {
        private const val TAG = "KioskServerCoordinator"
    }

    override fun isReady(): Boolean = isReady.invoke()

    override fun getSecretKey(): String = getSecretKey.invoke()

    override fun getDeviceId(): String = stateManager.deviceId.value.ifBlank { KioskSecurity.getHardwareId(context) }

    @Volatile private var lastUnverifiedDiscoveryMs = 0L

    override fun onHeartbeat(clientIp: String?) {
        if (clientIp.isNullOrEmpty() || clientIp == "127.0.0.1") return
        if (stateManager.esp32Ip == clientIp) {
            stateManager.isEsp32Online.value = true
            return
        }
        // The caller's address comes from the network, not from the signed message, so never re-point the
        // ESP32 address from here. Let the signed (MAC + HMAC) discovery confirm where the box is instead,
        // at most every 30 s.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastUnverifiedDiscoveryMs >= 30_000L) {
            lastUnverifiedDiscoveryMs = now
            onUnverifiedEsp32Contact?.invoke(clientIp)
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

    override fun getAuditEventsJson(): String = com.pisophone.kiosk.util.blockingIo {
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

    override fun creditPayment(txId: String, seconds: Int, amount: Double): PaymentResult = onCreditPayment(txId, seconds, amount)

    override fun onDeductTime(seconds: Int, txId: String?) {
        val previousState = stateManager.appState.value
        val updated = paymentRepo.deductTimeBlocking(seconds, txId)
        val targetState = SessionRules.afterDeduct(previousState, updated.sessionTimeRemaining)
        val sessionEnded = targetState != null
        val applied = stateManager.applySessionUpdate(
            deadlineMs = updated.sessionExpiryDeadlineMs,
            remainingSeconds = updated.sessionTimeRemaining,
            revision = updated.revision,
            targetAppState = targetState,
        )
        if (applied) {
            stateManager.saveState()
        }
        if (applied && sessionEnded) {
            onSessionLocked(false)
        }
        val displayMinutes = seconds / 60
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "$displayMinutes minutes deducted!", Toast.LENGTH_SHORT).show()
        }
    }

    /** Ends the paid session (blocking Room work — call off the main thread) and locks. */
    fun lockAndResetSession() {
        val previousState = stateManager.appState.value
        val resetState = paymentRepo.resetSessionBlocking()
        stateManager.applySessionUpdate(
            deadlineMs = resetState.sessionExpiryDeadlineMs,
            remainingSeconds = resetState.sessionTimeRemaining,
            revision = resetState.revision,
            targetAppState = SessionRules.hardLocked(),
        )
        stateManager.coinsInserted.value = 0
        stateManager.paymentTimeout.value = 0
        stateManager.saveState()
        if (previousState != 0) {
            onSessionLocked(SessionRules.isArmed(previousState))
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

    override fun onTriggerAction(action: String, slotNum: Int?, extra: Map<String, String>?) {
        if (slotNum != null && slotNum > 0) {
            stateManager.slotNumber.value = slotNum
            KioskSecurity.setAssignedBoxSlot(context, slotNum)
            KioskSecurity.setDeviceAlias(context, "PisoPhone $slotNum")
        }
        // Session-ending commands do blocking Room work: run them on the calling (HTTP worker)
        // thread, never inside the main-thread Handler below.
        when (action) {
            "slot_lockdown" -> {
                stateManager.isSlotExpired.value = true
                lockAndResetSession()
                KioskActivationManager.setSlotLockdown(
                    context,
                    locked = true,
                    reason = "Device activation required.",
                    slotNum = stateManager.slotNumber.value,
                    expiryTs = 0L,
                )
            }
            "reset_time" -> lockAndResetSession()
        }
        Handler(Looper.getMainLooper()).post {
            when (action) {
                "arena_mode_activate_p1" -> {
                    val stake = extra?.get("stake")?.toIntOrNull() ?: 15
                    stateManager.setArenaMode(active = true, role = 1, stake = stake, showBanner = true)
                    HardwareFeedback.triggerVibration(context, longArrayOf(0, 200, 100, 200, 100, 400))
                    getAudioManager.invoke()?.speakWarning("Arena Mode activated. You are Player 1.")
                }
                "arena_mode_activate_p2" -> {
                    val stake = extra?.get("stake")?.toIntOrNull() ?: 15
                    stateManager.setArenaMode(active = true, role = 2, stake = stake, showBanner = true)
                    HardwareFeedback.triggerVibration(context, longArrayOf(0, 200, 100, 200, 100, 400))
                    getAudioManager.invoke()?.speakWarning("Arena Mode activated. You are Player 2.")
                }
                "arena_mode_activate" -> {
                    val role = extra?.get("role")?.toIntOrNull() ?: 1
                    val stake = extra?.get("stake")?.toIntOrNull() ?: 15
                    stateManager.setArenaMode(active = true, role = role, stake = stake, showBanner = true)
                    HardwareFeedback.triggerVibration(context, longArrayOf(0, 200, 100, 200, 100, 400))
                    val roleStr = if (role == 1) "Player 1" else "Player 2"
                    getAudioManager.invoke()?.speakWarning("Arena Mode activated. You are $roleStr.")
                }
                "arena_mode_deactivate", "arena_mode_end" -> {
                    stateManager.setArenaMode(false)
                    Toast.makeText(context, "⚔️ 1v1 Arena Mode Concluded", Toast.LENGTH_SHORT).show()
                }
                "slot_lockdown" -> {
                    Toast.makeText(context, "Device activation required.", Toast.LENGTH_LONG).show()
                }
                "reset_time" -> {
                    Toast.makeText(context, "Session time reset.", Toast.LENGTH_SHORT).show()
                }
                "slot_restore", "slot_renew" -> {
                    stateManager.isSlotExpired.value = false
                    stateManager.slotExpiryMessage.value = ""
                    KioskActivationManager.setSlotLockdown(
                        context,
                        false,
                        slotNum = stateManager.slotNumber.value ?: 1,
                    )
                    stateManager.saveState()
                    Toast.makeText(context, "Device activated.", Toast.LENGTH_SHORT).show()
                }
                "vibrate" -> HardwareFeedback.triggerVibration(context, longArrayOf(0, 1500))
                "sound" -> {
                    getAudioManager.invoke()?.playHighBatteryAttentionTone()
                }
                "flash" -> HardwareFeedback.triggerFlashlight(context, 2000L)
                "locate" -> getAudioManager.invoke()?.startLocateAlarm()
                "locate_stop" -> getAudioManager.invoke()?.stopLocateAlarm()
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
                "identify" -> {
                    Toast.makeText(context, "Device Identified: ${KioskSecurity.getDeviceAlias(context)}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun getCrashLog(): String? = try {
        val logDir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(logDir, "crash.log")
        if (file.exists()) file.readText() else null
    } catch (_: Exception) {
        null
    }
}
