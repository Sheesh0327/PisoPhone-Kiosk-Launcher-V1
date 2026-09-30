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
    fun getDeviceName(): String = "PisoPhone Terminal"
    fun getTargetIp(): String? = null
    fun onEsp32Discovered(ip: String)
    fun onOnlineStatusChanged(isOnline: Boolean, mac: String?)
    fun onConfigSynced(price: Double?, minutes: Int?, alias: String?, adminPin: String? = null, slotNum: Int? = null)
    fun onCoinMessageReceived(seconds: Int, amount: Double, txId: String?)
    fun onSlotBusy()
    fun onArmSuccess()
    fun onArmFailed(reason: String) {}
    fun onSlotWarning(daysLeft: Int, expiresAt: Long, slotNum: Int, message: String)
    fun onSlotLockdown(reason: String, slotNum: Int, expiresAt: Long)
    fun onSlotRestored(slotNum: Int = 0)
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
        private const val MAX_TIMESTAMP_SKEW_MS = 300000L
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
    private var armingTimeoutJob: Job? = null
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
        }
    }

    private fun fetchMasterConfig(ipHost: String, esp32Port: Int) {
        try {
            val deviceId = delegate.getDeviceId()
            val devName = delegate.getDeviceName()
            val encodedName = java.net.URLEncoder.encode(devName, "UTF-8")
            val req = Request.Builder()
                .url("http://$ipHost:${esp32Port}/identify?device_id=$deviceId&name=$encodedName")
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
                    val targetIp = esp32Ip ?: delegate.getTargetIp()
                    if (esp32Ip.isNullOrBlank() && !targetIp.isNullOrBlank()) {
                        esp32Ip = targetIp
                    }

                    if (!targetIp.isNullOrBlank()) {
                        val (host, esp32Port) = discoveryScanner.getEsp32HostAndPort(targetIp)
                        val deviceId = delegate.getDeviceId()
                        val ts = System.currentTimeMillis().toString()
                        val sig = KioskSecurity.generateTimestampSignature(deviceId, ts, delegate.getSecretKey())
                        val (curBat, isChg) = delegate.getRealTimeBatteryInfo()
                        val devName = delegate.getDeviceName()
                        val encodedName = java.net.URLEncoder.encode(devName, "UTF-8")

                        val req = Request.Builder()
                            .url("http://$host:${esp32Port}/heartbeat?device_id=$deviceId&name=$encodedName&ip=${if (currentIp == "127.0.0.1") "" else currentIp}&time=${delegate.getSessionTimeRemaining()}&state=${delegate.getAppState()}&battery=$curBat&charging=${if (isChg) 1 else 0}&ts=$ts&sig=$sig")
                            .build()
                        try {
                            httpClient.newCall(req).execute().use { response ->
                                val code = response.code
                                val body = response.body?.string() ?: ""

                                if (response.isSuccessful || code == 423) {
                                    consecutiveHeartbeatFailures = 0
                                    lastHeartbeatTime = System.currentTimeMillis()
                                    if (body.isNotBlank()) {
                                        try {
                                            Esp32PayloadHandler.parseHeartbeatJson(body, delegate.getSecretKey(), delegate)
                                        } catch (e: Exception) {
                                            Log.e(TAG, "[HEARTBEAT] Exception parsing JSON: ${e.message}")
                                        }
                                    } else {
                                        delegate.onOnlineStatusChanged(true, null)
                                    }
                                } else {
                                    Log.w(TAG, "[HEARTBEAT] Unsuccessful HTTP code: $code")
                                    consecutiveHeartbeatFailures++
                                    if (code == 403) {
                                        delegate.onOnlineStatusChanged(false, null)
                                    } else {
                                        checkOfflineThreshold(currentIp)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "[HEARTBEAT] Exception during HTTP request: ${e.message}")
                            consecutiveHeartbeatFailures++
                            checkOfflineThreshold(currentIp)
                        }
                    } else {
                        discoveryScanner.triggerDiscovery(currentIp)
                    }
                } catch (_: Exception) {}
                delay(4000)
            }
        }
    }

    private fun checkOfflineThreshold(currentIp: String) {
        val offlineDuration = System.currentTimeMillis() - lastHeartbeatTime
        if (consecutiveHeartbeatFailures >= 5 || offlineDuration > HEARTBEAT_TIMEOUT_MS) {
            delegate.onOnlineStatusChanged(false, null)
            discoveryScanner.triggerDiscovery(currentIp)
            if (offlineDuration > HEARTBEAT_TIMEOUT_MS) {
                Log.w(TAG, "ESP32 disconnected for >${HEARTBEAT_TIMEOUT_MS}ms, clearing stale cached IP for auto-rediscovery")
                esp32Ip = null
            }
        }
    }

    // ========================================================================
    // WEBSOCKET ARMING (PORT 81)
    // ========================================================================

    fun closeSession(sendUnarmToEsp: Boolean = false) {
        synchronized(connectionLock) {
            armingTimeoutJob?.cancel()
            armingTimeoutJob = null
            val ws = activeWebSocket
            if (ws == null) {
                isDraining = false
                drainJob?.cancel()
                drainJob = null
                return
            }

            if (sendUnarmToEsp) {
                if (isDraining) return
                isDraining = true
                try {
                    val enqueued = ws.send("DONE")
                    Log.d(TAG, "Sent 'DONE' to ESP32 WebSocket (enqueued=$enqueued). Entering draining state.")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send DONE to ESP32: ${e.message}")
                }
                drainJob?.cancel()
                val targetAttemptId = currentAttemptId
                drainJob = scope.launch(Dispatchers.IO) {
                    try {
                        delay(DRAIN_SAFETY_TIMEOUT_MS)
                        forceCloseWebSocketIfAttemptCurrent(ws, "Drain safety timeout", targetAttemptId)
                    } catch (_: kotlinx.coroutines.CancellationException) {}
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
            if (callerAttemptId != currentAttemptId && ws !== activeWebSocket) return
            forceCloseWebSocketLocked(ws, reason, callerAttemptId)
        }
    }

    private fun forceCloseWebSocketLocked(ws: WebSocket?, reason: String, callerAttemptId: Long) {
        val targetWs = ws ?: activeWebSocket
        if (targetWs != null) {
            if (targetWs === activeWebSocket) {
                activeWebSocket = null
                armingTimeoutJob?.cancel()
                armingTimeoutJob = null
                drainJob?.cancel()
                drainJob = null
                isDraining = false
            }
            try {
                if (!targetWs.close(1000, reason)) {
                    targetWs.cancel()
                }
            } catch (_: Exception) {
                try { targetWs.cancel() } catch (_: Exception) {}
            }
        }
    }

    fun armSlot(armingTimeoutSeconds: Int) {
        val secret = delegate.getSecretKey().trim()
        if (secret.isEmpty()) {
            Log.e(TAG, "Arming rejected: Missing or unreadable payment credentials. Payment setup required.")
            delegate.onArmFailed("Missing secret key")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Payment setup required: Please pair device with Box secret key.", Toast.LENGTH_LONG).show()
            }
            return
        }

        val attemptId: Long
        val ip: String?
        synchronized(connectionLock) {
            attemptId = ++currentAttemptId
            forceCloseWebSocketLocked(activeWebSocket, "Re-arming slot", attemptId)
            if (esp32Ip.isNullOrBlank()) {
                val fallback = delegate.getTargetIp()
                if (!fallback.isNullOrBlank()) {
                    esp32Ip = fallback
                }
            }
            ip = esp32Ip
        }

        if (ip.isNullOrBlank()) {
            Log.w(TAG, "Cannot arm slot immediately: No cached ESP32 IP. Initiating fast discovery and connect (attempt #$attemptId)...")
            discoveryScanner.triggerDiscovery("")
            scope.launch(Dispatchers.IO) {
                var resolvedIp: String? = null
                for (i in 0 until 35) { // Wait up to 3.5 seconds
                    delay(100)
                    synchronized(connectionLock) {
                        if (attemptId != currentAttemptId) return@launch
                        resolvedIp = esp32Ip ?: delegate.getTargetIp()
                    }
                    if (!resolvedIp.isNullOrBlank()) break
                }
                val target = resolvedIp
                if (!target.isNullOrBlank()) {
                    initiateWebSocketArming(attemptId, target, armingTimeoutSeconds)
                } else {
                    Log.e(TAG, "Arming failed: Could not discover ESP32 IP within timeout for attempt #$attemptId.")
                    delegate.onOnlineStatusChanged(false, null)
                    delegate.onArmFailed("ESP32 IP unreachable")
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "Cannot connect to ESP32. Please check Wi-Fi connection.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            return
        }

        initiateWebSocketArming(attemptId, ip, armingTimeoutSeconds)
    }

    private fun initiateWebSocketArming(attemptId: Long, ip: String, armingTimeoutSeconds: Int) {
        val secret = delegate.getSecretKey().trim()
        if (secret.isEmpty()) {
            Log.e(TAG, "Arming rejected: Missing payment credentials for attempt #$attemptId. Payment setup required.")
            delegate.onArmFailed("Missing secret key")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Payment setup required: No valid box key provisioned.", Toast.LENGTH_LONG).show()
            }
            return
        }

        synchronized(connectionLock) {
            if (attemptId != currentAttemptId) return
            armingTimeoutJob?.cancel()
            armingTimeoutJob = scope.launch(Dispatchers.IO) {
                delay(12000) // 12 seconds bounded arming timeout
                synchronized(connectionLock) {
                    if (attemptId == currentAttemptId) {
                        Log.w(TAG, "Arming attempt #$attemptId timed out (no ARMED received in 12s).")
                        forceCloseWebSocketLocked(activeWebSocket, "Arming timeout", attemptId)
                        delegate.onArmFailed("Arming timed out: No response from master")
                    }
                }
            }
        }

        val (host, _) = discoveryScanner.getEsp32HostAndPort(ip)
        val targetHost = if (host.isNotBlank()) host else ip
        val deviceId = delegate.getDeviceId()
        val ts = System.currentTimeMillis().toString()
        val sig = KioskSecurity.generateTimestampSignature(deviceId, ts, secret)

        val wsUrl = "ws://$targetHost:$ESP32_WS_PORT/ws?device_id=$deviceId&ts=$ts&sig=$sig"
        Log.i(TAG, "⚡ [Single-Path Arming] Connecting WebSocket to $targetHost:$ESP32_WS_PORT (attempt #$attemptId, deviceId=$deviceId)")

        val request = Request.Builder().url(wsUrl).build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(connectionLock) {
                    if (attemptId != currentAttemptId || (activeWebSocket != null && webSocket !== activeWebSocket)) {
                        try { webSocket.close(1000, "Obsolete attempt") } catch (_: Exception) {}
                        return
                    }
                    if (activeWebSocket == null) {
                        activeWebSocket = webSocket
                    }
                }
                lastHeartbeatTime = System.currentTimeMillis()
                delegate.onOnlineStatusChanged(true, null)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                synchronized(connectionLock) {
                    if (attemptId != currentAttemptId || webSocket !== activeWebSocket) {
                        Log.d(TAG, "Ignoring late/obsolete message on WebSocket (attempt #$attemptId): $text")
                        return
                    }
                }
                Log.d(TAG, "Master WebSocket onMessage (attempt #$attemptId): $text")
                try {
                    val json = JSONObject(text)
                    val event = json.optString("event", "")

                    if (event == "COIN_DETECTED") {
                        val parsed = Esp32PayloadHandler.parseWebSocketCoinEvent(text, delegate.getSecretKey(), MAX_TIMESTAMP_SKEW_MS)
                        if (parsed != null) {
                            Log.i(TAG, "⚡ Validated WebSocket Coin Processed: +${parsed.seconds}s, amount=₱${parsed.amount}, txId=${parsed.txId}")
                            delegate.onCoinMessageReceived(parsed.seconds, parsed.amount, parsed.txId)

                            synchronized(connectionLock) {
                                if (isDraining && webSocket === activeWebSocket) {
                                    drainJob?.cancel()
                                    drainJob = scope.launch(Dispatchers.IO) {
                                        try {
                                            delay(DRAIN_SAFETY_TIMEOUT_MS)
                                            forceCloseWebSocketIfAttemptCurrent(webSocket, "Drain safety timeout after coin", attemptId)
                                        } catch (_: kotlinx.coroutines.CancellationException) {}
                                    }
                                }
                            }
                        }
                    } else if (event == "ARMED") {
                        Log.i(TAG, "⚡ ESP32 Coin Slot ARMED confirmed via WebSocket (attempt #$attemptId)")
                        synchronized(connectionLock) {
                            if (attemptId == currentAttemptId) {
                                armingTimeoutJob?.cancel()
                                armingTimeoutJob = null
                            }
                        }
                        delegate.onArmSuccess()
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, "Coin slot ready (${armingTimeoutSeconds}s)", Toast.LENGTH_SHORT).show()
                        }
                    } else if (event == "TIMEOUT" || event == "CLOSED" || event == "SESSION_ENDED") {
                        forceCloseWebSocketIfAttemptCurrent(webSocket, "ESP32 event: $event", attemptId)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing WebSocket message: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val isCurrent: Boolean
                synchronized(connectionLock) {
                    isCurrent = (attemptId == currentAttemptId && webSocket === activeWebSocket)
                }
                if (!isCurrent) {
                    Log.d(TAG, "Ignoring onFailure on obsolete WebSocket (attempt #$attemptId)")
                    return
                }
                synchronized(connectionLock) {
                    if (attemptId == currentAttemptId) {
                        armingTimeoutJob?.cancel()
                        armingTimeoutJob = null
                    }
                }
                val code = response?.code ?: 0
                val msg = t.message ?: ""
                Log.e(TAG, "WebSocket failure (attempt #$attemptId, HTTP $code): $msg")
                val errorReason = when {
                    code == 409 -> {
                        delegate.onSlotBusy()
                        "Slot is currently busy with another device."
                    }
                    code == 403 -> {
                        delegate.onOnlineStatusChanged(false, null)
                        "Box authentication failed: Secret key mismatch."
                    }
                    code == 423 || msg.contains("SLOT_EXPIRED", ignoreCase = true) -> {
                        delegate.onSlotLockdown("Please activate device slot on ESP32 Portal.", 0, 0L)
                        "Device not activated on ESP32."
                    }
                    else -> "Cannot connect to ESP32 coin slot."
                }
                delegate.onArmFailed(errorReason)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, errorReason, Toast.LENGTH_LONG).show()
                }
                forceCloseWebSocketIfAttemptCurrent(webSocket, "WebSocket failure: $msg", attemptId)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(connectionLock) {
                    if (webSocket === activeWebSocket) {
                        armingTimeoutJob?.cancel()
                        armingTimeoutJob = null
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
