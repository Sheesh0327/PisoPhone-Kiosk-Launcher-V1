package com.pisophone.kiosk.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.pisophone.kiosk.security.KioskSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

interface Esp32ConnectionDelegate {
    fun getDeviceId(): String
    fun getSecretKey(): String
    fun getAppState(): Int
    fun getSessionTimeRemaining(): Int
    fun getRealTimeBatteryInfo(): Pair<Int, Boolean>
    fun onEsp32Discovered(ip: String)
    fun onOnlineStatusChanged(isOnline: Boolean, mac: String?)
    fun onConfigSynced(price: Double?, minutes: Int?, alias: String?, adminPin: String? = null, slotNum: Int? = null)
    fun onCoinMessageReceived(seconds: Int, amount: Double, txId: String?)
    fun onSlotBusy()
    fun onArmSuccess()
    fun onSlotWarning(daysLeft: Int, expiresAt: Long, slotNum: Int, message: String)
    fun onSlotLockdown(reason: String, slotNum: Int, expiresAt: Long)
    fun onSlotRestored(slotNum: Int = 0)
    fun onArenaModeSynced(active: Boolean, role: Int, stake: Int) {}
}

/**
 * Manages active communication with the ESP32 Master kiosk box:
 * - HTTP Telemetry & Config Sync Loop (Port 80)
 * - WebSocket Slot Arming & Encrypted Coin Event Listener (Port 81)
 * - Discovery delegation via [Esp32DiscoveryScanner]
 */
class Esp32ConnectionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val delegate: Esp32ConnectionDelegate
) {
    companion object {
        private const val TAG = "Esp32ConnectionManager"
        private const val ESP32_WS_PORT = 81
        private const val HEARTBEAT_TIMEOUT_MS = 45000L
        private const val MAX_TIMESTAMP_SKEW_MS = 60000L
        private const val DRAIN_SAFETY_TIMEOUT_MS = 15000L
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .build()

    private var esp32Ip: String? = null
    private var lastHeartbeatTime: Long = System.currentTimeMillis()
    private var consecutiveHeartbeatFailures: Int = 0
    private val connectionLock = Any()
    private var currentAttemptId = 0L
    private var activeWebSocket: WebSocket? = null
    private var isDraining: Boolean = false
    private var drainJob: Job? = null
    private var heartbeatJob: Job? = null

    private val discoveryScanner = Esp32DiscoveryScanner(
        context = context,
        scope = scope,
        delegate = object : Esp32DiscoveryDelegate {
            override fun onEsp32Discovered(ip: String, rawResponseBody: String?) {
                handleEsp32Discovered(ip, rawResponseBody)
            }
        },
        isAlreadyBound = {
            val isOnline = (System.currentTimeMillis() - lastHeartbeatTime < HEARTBEAT_TIMEOUT_MS) && consecutiveHeartbeatFailures < 5
            isOnline && !esp32Ip.isNullOrBlank()
        }
    )

    fun getEsp32Ip(): String? = esp32Ip

    fun setEsp32Ip(ip: String?) {
        esp32Ip = ip
    }

    fun markHeartbeatReceived() {
        lastHeartbeatTime = System.currentTimeMillis()
        delegate.onOnlineStatusChanged(true, null)
    }

    // ========================================================================
    // DISCOVERY & PROBING DELEGATION
    // ========================================================================

    fun triggerCandidateDiscovery(localIp: String) {
        discoveryScanner.triggerDiscovery(localIp)
    }

    fun probeEsp32Connection(ip: String): Boolean {
        return discoveryScanner.probeEsp32Connection(ip)
    }

    private fun handleEsp32Discovered(ip: String, rawResponseBody: String? = null) {
        val (ipHost, esp32Port) = discoveryScanner.getEsp32HostAndPort(ip)
        esp32Ip = ip
        lastHeartbeatTime = System.currentTimeMillis()
        consecutiveHeartbeatFailures = 0
        delegate.onEsp32Discovered(ip)
        delegate.onOnlineStatusChanged(true, null)
        Log.d(TAG, "[+] ESP32 Master bound at $ipHost")

        // Parse immediate config from UDP response if present
        if (!rawResponseBody.isNullOrBlank()) {
            try {
                val json = JSONObject(rawResponseBody)
                val price = if (json.has("price")) json.optDouble("price", 5.0) else null
                val minutes = if (json.has("minutes")) json.optInt("minutes", 30) else null
                val alias = if (json.has("device_name")) json.optString("device_name", "").trim() else null
                val mac = if (json.has("mac")) json.optString("mac", "") else null
                delegate.onConfigSynced(price, minutes, alias)
                if (!mac.isNullOrBlank()) {
                    delegate.onOnlineStatusChanged(true, mac)
                }
            } catch (_: Exception) {}
        }

        scope.launch(Dispatchers.IO) {
            fetchMasterConfig(ipHost, esp32Port)
            sendPairingRequest(ipHost)
        }
    }

    fun sendPairingRequest(targetIp: String? = null) {
        val host = targetIp ?: esp32Ip ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val (ipHost, esp32Port) = discoveryScanner.getEsp32HostAndPort(host)
                val deviceId = KioskSecurity.getHardwareId(context)
                val myIp = discoveryScanner.getLocalIpAddress()
                val (curBat, isChg) = delegate.getRealTimeBatteryInfo()
                val myName = KioskSecurity.getDeviceAlias(context).takeIf { it.isNotBlank() } ?: "PisoPhone Terminal"
                val encodedName = java.net.URLEncoder.encode(myName, "UTF-8")
                val url = "http://$ipHost:$esp32Port/api/slots/pair_request?device_id=$deviceId&ip=$myIp&name=$encodedName&battery=$curBat&charging=${if (isChg) 1 else 0}&source=app&app=1&client=pisophone_app"
                val req = Request.Builder().url(url).build()
                httpClient.newCall(req).execute().use { resp ->
                    Log.d(TAG, "Explicit pair_request sent to $ipHost:$esp32Port, status: ${resp.code}")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Pair request non-fatal error: ${e.message}")
            }
        }
    }

    private fun fetchMasterConfig(ipHost: String, esp32Port: Int) {
        try {
            val req = Request.Builder()
                .url("http://$ipHost:${esp32Port}/identify")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JSONObject(body)
                    val price = if (json.has("price")) json.optDouble("price", 5.0) else null
                    val minutes = if (json.has("minutes")) json.optInt("minutes", 30) else null
                    val alias = if (json.has("device_name")) json.optString("device_name", "").trim() else null
                    delegate.onConfigSynced(price, minutes, alias)
                }
            }
        } catch (_: Exception) {}
    }

    // ========================================================================
    // HEARTBEAT LOOP (PORT 80)
    // ========================================================================

    fun startHeartbeatLoop(deviceIpProvider: () -> String) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val currentIp = deviceIpProvider()
                    val targetIp = esp32Ip

                    if (!targetIp.isNullOrBlank()) {
                        val (host, esp32Port) = discoveryScanner.getEsp32HostAndPort(targetIp)
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
                                            sendPairingRequest(targetIp)
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
                        // Not bound yet: trigger clean discovery probe and direct candidate check
                        discoveryScanner.triggerDiscovery(currentIp)
                    }
                } catch (_: Exception) {}
                delay(4000)
            }
        }
    }

    private fun checkOfflineThreshold(currentIp: String) {
        val offlineDuration = System.currentTimeMillis() - lastHeartbeatTime
        if (consecutiveHeartbeatFailures >= 2 || offlineDuration > 8000L) {
            delegate.onOnlineStatusChanged(false, null)
            // Immediately trigger discovery to locate ESP32 if assigned a new DHCP IP
            discoveryScanner.triggerDiscovery(currentIp)
            Log.w(TAG, "ESP32 heartbeat failed ($consecutiveHeartbeatFailures failures, ${offlineDuration}ms offline), clearing stale cached IP for fast rediscovery")
            esp32Ip = null
        }
    }

    // ========================================================================
    // WEBSOCKET ARMING (PORT 81)
    // ========================================================================

    fun closeSession(sendUnarmToEsp: Boolean = false) {
        synchronized(connectionLock) {
            val ws = activeWebSocket
            if (ws == null) {
                isDraining = false
                drainJob?.cancel()
                drainJob = null
                return
            }

            if (sendUnarmToEsp) {
                if (isDraining) {
                    Log.d(TAG, "closeSession(sendUnarmToEsp=true) invoked while already draining; skipping duplicate send.")
                    return
                }
                isDraining = true
                try {
                    val enqueued = ws.send("DONE")
                    Log.d(TAG, "Sent 'DONE' to ESP32 WebSocket (enqueued=$enqueued). Entering draining state (safety timeout: ${DRAIN_SAFETY_TIMEOUT_MS}ms).")
                    if (!enqueued) {
                        Log.w(TAG, "Failed to send DONE to ESP32: socket send buffer full or closing")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send DONE to ESP32: ${e.message}")
                }
                drainJob?.cancel()
                val targetAttemptId = currentAttemptId
                drainJob = scope.launch(Dispatchers.IO) {
                    try {
                        delay(DRAIN_SAFETY_TIMEOUT_MS)
                        Log.w(TAG, "Drain safety timeout reached (${DRAIN_SAFETY_TIMEOUT_MS}ms) without ESP32 closure; closing WebSocket.")
                        forceCloseWebSocketIfAttemptCurrent(ws, "Drain safety timeout", targetAttemptId)
                    } catch (_: kotlinx.coroutines.CancellationException) {
                        // Normal cancellation if ESP32 closed first or armSlot called
                    }
                }
            } else {
                forceCloseWebSocketLocked(ws, "Session aborted", currentAttemptId)
            }
        }
    }

    private fun forceCloseWebSocket(ws: WebSocket?, reason: String) {
        synchronized(connectionLock) {
            forceCloseWebSocketLocked(ws, reason, currentAttemptId)
        }
    }

    private fun forceCloseWebSocketIfAttemptCurrent(ws: WebSocket?, reason: String, callerAttemptId: Long) {
        synchronized(connectionLock) {
            if (callerAttemptId != currentAttemptId && ws !== activeWebSocket) {
                Log.d(TAG, "Ignoring forceClose for obsolete WebSocket attempt ($callerAttemptId vs current $currentAttemptId)")
                return
            }
            forceCloseWebSocketLocked(ws, reason, callerAttemptId)
        }
    }

    private fun forceCloseWebSocketLocked(ws: WebSocket?, reason: String, callerAttemptId: Long) {
        val targetWs = ws ?: activeWebSocket
        if (targetWs != null) {
            val isActiveTarget = (targetWs === activeWebSocket)
            if (isActiveTarget) {
                activeWebSocket = null
                drainJob?.cancel()
                drainJob = null
                isDraining = false
            }
            try {
                if (!targetWs.close(1000, reason)) {
                    targetWs.cancel()
                }
            } catch (_: Exception) {
                try {
                    targetWs.cancel()
                } catch (_: Exception) {}
            }
        }
    }

    fun armSlot(armingTimeoutSeconds: Int) {
        val attemptId: Long
        val ip: String?
        synchronized(connectionLock) {
            attemptId = ++currentAttemptId
            forceCloseWebSocketLocked(activeWebSocket, "Re-arming slot", attemptId)
            ip = esp32Ip
        }

        if (ip.isNullOrBlank()) {
            Log.w(TAG, "Cannot arm slot: No discovered ESP32 IP available. Triggering discovery...")
            delegate.onOnlineStatusChanged(false, null)
            discoveryScanner.triggerDiscovery("")
            return
        }

        val deviceId = delegate.getDeviceId()
        val ts = System.currentTimeMillis().toString()
        val sig = KioskSecurity.generateTimestampSignature(deviceId, ts, delegate.getSecretKey())

        val wsUrl = "ws://$ip:$ESP32_WS_PORT/ws?device_id=$deviceId&ts=$ts&sig=$sig"
        Log.d(TAG, "Connecting to Master WebSocket at $ip:$ESP32_WS_PORT (attempt #$attemptId)")

        val request = Request.Builder().url(wsUrl).build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(connectionLock) {
                    if (attemptId != currentAttemptId || webSocket !== activeWebSocket) {
                        Log.d(TAG, "Ignoring onOpen for stale WebSocket attempt #$attemptId")
                        try { webSocket.close(1000, "Obsolete attempt") } catch (_: Exception) {}
                        return
                    }
                }
                lastHeartbeatTime = System.currentTimeMillis()
                delegate.onOnlineStatusChanged(true, null)
                delegate.onArmSuccess()
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Coin slot locked (Ready for coin - ${armingTimeoutSeconds}s)", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Master WebSocket onMessage (attempt #$attemptId): $text")
                try {
                    val json = JSONObject(text)
                    val event = json.optString("event", "")

                    if (event == "COIN_DETECTED") {
                        val payload = json.optString("payload", "")
                        if (payload.isBlank()) {
                            Log.w(TAG, "Rejected WebSocket coin event: Missing encrypted payload")
                            return
                        }

                        val secretKey = delegate.getSecretKey()
                        val decryptedStr = KioskSecurity.decrypt(payload, secretKey)
                        if (decryptedStr.isBlank()) {
                            Log.w(TAG, "Rejected WebSocket coin event: Decryption failed or invalid secret key")
                            return
                        }

                        val decryptedJson = try {
                            JSONObject(decryptedStr)
                        } catch (e: Exception) {
                            Log.e(TAG, "Rejected WebSocket coin event: Malformed decrypted JSON: ${e.message}")
                            return
                        }

                        val txId = decryptedJson.optString("tx_id", "").trim()
                        if (txId.isBlank()) {
                            Log.w(TAG, "Rejected WebSocket coin event: Missing tx_id in encrypted payload")
                            return
                        }

                        val targetDev = decryptedJson.optString("device_id", "").trim()
                        val myDevId = delegate.getDeviceId()
                        if (targetDev.isNotBlank() && myDevId.isNotBlank() && !targetDev.equals(myDevId, ignoreCase = true)) {
                            Log.w(TAG, "Rejected WebSocket coin event: Recipient mismatch (target='$targetDev', local='$myDevId')")
                            return
                        }

                        val tsStr = decryptedJson.optString("ts", "").trim()
                        val ts = tsStr.toLongOrNull() ?: 0L
                        val now = System.currentTimeMillis()
                        val skew = Math.abs(now - ts)
                        if (ts <= 0L || skew > MAX_TIMESTAMP_SKEW_MS) {
                            Log.w(TAG, "Rejected WebSocket coin event: Stale/invalid timestamp ($ts, now=$now, skew=${skew}ms, max=${MAX_TIMESTAMP_SKEW_MS}ms)")
                            return
                        }

                        val minutesLong = decryptedJson.optLong("minutes", 0L)
                        val secondsOptLong = decryptedJson.optLong("seconds", 0L)
                        val rawSeconds = if (secondsOptLong > 0L) secondsOptLong else (minutesLong * 60L)
                        val amount = decryptedJson.optDouble("amount", 0.0)

                        if (rawSeconds !in 1L..Int.MAX_VALUE.toLong() || amount.isNaN() || amount.isInfinite() || amount <= 0.0) {
                            Log.w(TAG, "Rejected WebSocket coin event: Invalid seconds ($rawSeconds) or amount ($amount)")
                            return
                        }
                        val seconds = rawSeconds.toInt()
                        val amountPulses = amount.toInt()

                        val vSig = decryptedJson.optString("v_sig", "").trim()
                        if (vSig.isNotBlank()) {
                            val expectedVSig = KioskSecurity.calculateHmac("v1:$targetDev:$txId:$amountPulses:$tsStr", secretKey)
                            if (!KioskSecurity.constantTimeEquals(vSig.lowercase(), expectedVSig.lowercase())) {
                                Log.w(TAG, "Rejected WebSocket coin event: Invalid versioned HMAC signature for $txId")
                                return
                            }
                        }

                        Log.i(TAG, "⚡ Validated WebSocket Coin Processed: +${seconds}s, amount=₱$amount, txId=$txId")
                        delegate.onCoinMessageReceived(seconds, amount, txId)

                        // Send signed durable ACK back to ESP32 over WebSocket
                        try {
                            val ackNow = System.currentTimeMillis()
                            val ackPayload = "v1:$targetDev:$txId:$amountPulses:$ackNow"
                            val ackSig = KioskSecurity.calculateHmac(ackPayload, secretKey)
                            val ackJson = JSONObject().apply {
                                put("event", "ACK")
                                put("device_id", targetDev)
                                put("tx_id", txId)
                                put("amount", amountPulses)
                                put("ts", ackNow.toString())
                                put("v_sig", ackSig)
                            }
                            webSocket.send(ackJson.toString())
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to send WebSocket ACK for $txId: ${e.message}")
                        }

                        // If coin arrives during drain window, reset drain timeout to allow subsequent pulses
                        synchronized(connectionLock) {
                            if (isDraining && webSocket === activeWebSocket) {
                                Log.d(TAG, "Coin received during active drain window; extending drain safety guard.")
                                drainJob?.cancel()
                                drainJob = scope.launch(Dispatchers.IO) {
                                    try {
                                        delay(DRAIN_SAFETY_TIMEOUT_MS)
                                        Log.w(TAG, "Extended drain safety timeout reached; closing WebSocket.")
                                        forceCloseWebSocketIfAttemptCurrent(webSocket, "Drain safety timeout after coin", attemptId)
                                    } catch (_: kotlinx.coroutines.CancellationException) {}
                                }
                            }
                        }
                    } else if (event == "TIMEOUT" || event == "CLOSED" || event == "SESSION_ENDED") {
                        Log.d(TAG, "Received $event event from ESP32 WebSocket (attempt #$attemptId)")
                        forceCloseWebSocketIfAttemptCurrent(webSocket, "ESP32 event: $event", attemptId)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing WebSocket message: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val isCurrent: Boolean
                synchronized(connectionLock) {
                    isCurrent = (attemptId == currentAttemptId || webSocket === activeWebSocket)
                }
                val code = response?.code ?: 0
                val msg = t.message ?: ""
                Log.e(TAG, "WebSocket failure (attempt #$attemptId, HTTP $code): $msg")
                if (isCurrent) {
                    if (code == 409) {
                        Log.e(TAG, "Slot is BUSY with another session (HTTP 409)")
                        delegate.onSlotBusy()
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, "Slot is currently busy with another device.", Toast.LENGTH_LONG).show()
                        }
                    } else if (code == 403 || code == 423 || msg.contains("SLOT_EXPIRED", ignoreCase = true) || msg.contains("423", ignoreCase = true)) {
                        Log.e(TAG, "Slot is EXPIRED on ESP32 (HTTP $code). Enforcing lockdown.")
                        delegate.onSlotLockdown("Please activate device slot on ESP32 Portal.", 0, 0L)
                    } else {
                        Log.w(TAG, "WebSocket arming failed (HTTP $code: $msg)")
                        // If we are waiting for payment, revert state and alert user
                        val appState = delegate.getAppState()
                        if (appState == 1 || appState == 3) {
                            delegate.onSlotBusy()
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(context, "Could not connect to coin slot. Please try again.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    Log.d(TAG, "Suppressing stale WebSocket failure lifecycle side effects for attempt #$attemptId")
                }
                forceCloseWebSocketIfAttemptCurrent(webSocket, "WebSocket failure: $msg", attemptId)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed (attempt #$attemptId, code=$code, reason=$reason)")
                synchronized(connectionLock) {
                    if (webSocket === activeWebSocket) {
                        drainJob?.cancel()
                        drainJob = null
                        isDraining = false
                        activeWebSocket = null
                    }
                }
            }
        }

        synchronized(connectionLock) {
            if (attemptId == currentAttemptId) {
                activeWebSocket = okHttpClient.newWebSocket(request, listener)
            }
        }
    }

    fun shutdown() {
        heartbeatJob?.cancel()
        forceCloseWebSocket(activeWebSocket, "Shutdown")
        discoveryScanner.shutdown()
    }
}
