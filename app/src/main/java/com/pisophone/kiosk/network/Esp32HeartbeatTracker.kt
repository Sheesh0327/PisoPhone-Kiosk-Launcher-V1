package com.pisophone.kiosk.network

import android.content.Context
import android.util.Log
import com.pisophone.kiosk.security.KioskSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Handles HTTP periodic telemetry heartbeat loop to ESP32 (Port 80)
 * and parses dynamic price, minutes, admin PIN, and lockdown status.
 */
class Esp32HeartbeatTracker(
    private val context: Context,
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient,
    private val delegate: Esp32ConnectionDelegate,
    private val onTriggerDirectPairing: (String?) -> Unit
) {
    companion object {
        private const val TAG = "Esp32HeartbeatTracker"
    }

    private var lastHeartbeatTime: Long = System.currentTimeMillis()
    private var consecutiveHeartbeatFailures: Int = 0
    private var heartbeatJob: Job? = null

    fun markHeartbeatReceived() {
        lastHeartbeatTime = System.currentTimeMillis()
        delegate.onOnlineStatusChanged(true, null)
    }

    fun startHeartbeatLoop(
        esp32IpProvider: () -> String?,
        deviceIpProvider: () -> String
    ) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val currentIp = deviceIpProvider()
                    val targetIp = esp32IpProvider()

                    if (!targetIp.isNullOrBlank()) {
                        val (host, esp32Port) = Esp32DirectPairing.getEsp32HostAndPort(targetIp)
                        val deviceId = delegate.getDeviceId()
                        val ts = System.currentTimeMillis().toString()
                        val sig = KioskSecurity.generateTimestampSignature(deviceId, ts, delegate.getSecretKey())
                        val (curBat, isChg) = delegate.getRealTimeBatteryInfo()

                        val req = Request.Builder()
                            .url("http://$host:${esp32Port}/heartbeat?device_id=$deviceId&ip=${if (currentIp == "127.0.0.1") "" else currentIp}&time=${delegate.getSessionTimeRemaining()}&state=${delegate.getAppState()}&battery=$curBat&charging=${if (isChg) 1 else 0}&ts=$ts&sig=$sig&source=app&app=1&client=pisophone_app")
                            .build()
                        try {
                            httpClient.newCall(req).execute().use { response ->
                                val code = response.code
                                val body = response.body?.string() ?: ""

                                if (response.isSuccessful || code == 403 || code == 423) {
                                    consecutiveHeartbeatFailures = 0
                                    lastHeartbeatTime = System.currentTimeMillis()
                                    if (body.isNotBlank()) {
                                        parseHeartbeatResponse(body, targetIp)
                                    } else {
                                        Log.d(TAG, "[HEARTBEAT] Body is blank. Setting online to true.")
                                        delegate.onOnlineStatusChanged(true, null)
                                    }
                                } else {
                                    Log.w(TAG, "[HEARTBEAT] Unsuccessful HTTP code: $code")
                                    consecutiveHeartbeatFailures++
                                    checkOfflineThreshold(currentIp)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "[HEARTBEAT] Exception during HTTP request: ${e.message}", e)
                            consecutiveHeartbeatFailures++
                            checkOfflineThreshold(currentIp)
                        }
                    } else {
                        onTriggerDirectPairing(null)
                    }
                } catch (e: Exception) {}
                delay(4000)
            }
        }
    }

    private fun parseHeartbeatResponse(body: String, targetIp: String) {
        try {
            val json = JSONObject(body)
            val slotNum = json.optInt("slot_num", json.optInt("slot", 0))
            val isUnassigned = json.optString("status", "") == "unassigned" ||
                    json.optString("slot_status", "") == "unassigned" ||
                    (!json.optBoolean("is_paired", true) && slotNum <= 0)
            val isExpired = isUnassigned ||
                    json.optBoolean("slot_expired", false) ||
                    json.optBoolean("lockdown", false) ||
                    json.optString("status", "") == "expired" ||
                    json.optString("slot_status", "") == "expired"
            val expiresAt = json.optLong("expires_at", 0L)
            if (isUnassigned) {
                onTriggerDirectPairing(targetIp)
            }
            val errorMsg = if (json.has("message") && json.optString("message").isNotBlank()) {
                json.optString("message")
            } else {
                json.optString("error", "Please activate device slot on ESP32 Portal.")
            }

            if (isExpired) {
                delegate.onSlotLockdown(errorMsg, slotNum, expiresAt)
            } else {
                delegate.onSlotRestored(slotNum)

                val isWarning = json.optBoolean("slot_warning", false) ||
                        json.optString("slot_status", "") == "warning"
                val daysLeft = if (json.has("days_left")) json.optInt("days_left", -1) else -1
                val warnMsg = json.optString("warning_message", "Slot license nearing expiration")
                if (isWarning && daysLeft in 0..7) {
                    delegate.onSlotWarning(daysLeft, expiresAt, slotNum, warnMsg)
                }
            }

            val mac = if (json.has("mac")) json.optString("mac", "") else null
            val alias = if (json.has("device_name")) json.optString("device_name", "").trim() else null
            val price = if (json.has("price")) json.optDouble("price", 5.0) else null
            val minutes = if (json.has("minutes")) json.optInt("minutes", 30) else null
            val encryptedPin = json.optString("admin_pin", "")
            val decryptedPin = if (encryptedPin.isNotBlank()) {
                val dec = KioskSecurity.decrypt(encryptedPin, delegate.getSecretKey()).trim()
                if (dec.startsWith("PIN:")) dec.substring(4).trim().takeIf { it.isNotBlank() } else null
            } else null
            
            Log.d(TAG, "[HEARTBEAT] JSON parsing successful. Setting online to true.")
            delegate.onOnlineStatusChanged(true, mac)
            delegate.onConfigSynced(price, minutes, alias, decryptedPin, slotNum)

            if (json.has("arena_active")) {
                val arenaActive = json.optBoolean("arena_active", false)
                val arenaRole = json.optInt("arena_role", 0)
                val arenaStake = json.optInt("arena_stake", 15)
                delegate.onArenaModeSynced(arenaActive, arenaRole, arenaStake)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[HEARTBEAT] Exception parsing JSON body: ${e.message}", e)
        }
    }

    private fun checkOfflineThreshold(currentIp: String) {
        val offlineDuration = System.currentTimeMillis() - lastHeartbeatTime
        if (consecutiveHeartbeatFailures >= 2 || offlineDuration > 8000L) {
            delegate.onOnlineStatusChanged(false, null)
            val staticIp = KioskSecurity.getConfiguredEsp32Ip(context).ifBlank { Esp32DirectPairing.DEFAULT_STATIC_ESP32_IP }
            onTriggerDirectPairing(staticIp)
            Log.w(TAG, "ESP32 heartbeat failed ($consecutiveHeartbeatFailures failures, ${offlineDuration}ms offline), sent direct pairing request to $staticIp")
        }
    }

    fun stop() {
        heartbeatJob?.cancel()
    }
}
