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
 * Manages all network communications with the ESP32 Master Kiosk Controller:
 * 1. Background HTTP Telemetry & Heartbeat loop on port 80.
 * 2. Dedicated single-session RFC 6455 WebSocket on port 81 for slot arming and encrypted coin event ingestion.
 * 3. Automatic terminal discovery delegation via [Esp32DiscoveryScanner].
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
        private const val ARMING_WATCHDOG_TIMEOUT_MS = 12000L
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
                    val mac = if (json.has("mac")) json.optString("mac", "") else null
                    delegate.onConfigSynced(price, minutes, alias)
                    if (!mac.isNullOrBlank()) {
                        delegate.onOnlineStatusChanged(true, mac)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    // ========================================================================
    // TELEMETRY & HEARTBEAT LOOP (PORT 80)
    // ========================================================================

    fun startHeartbeatLoop(deviceIpProvider: () -> String) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    delay(5000)
                    var targetIp = esp32Ip
                    if (targetIp.isNullOrBlank()) {
                        targetIp = delegate.getTargetIp()
                        if (!targetIp.isNullOrBlank()) {
                            esp32Ip = targetIp
                        }
                    }

                    if (!targetIp.isNullOrBlank()) {
                        val (ipHost, esp32Port) = discoveryScanner.getEsp32HostAndPort(targetIp)
                        val deviceId = delegate.getDeviceId()
                        val secret = delegate.getSecretKey()
                        val (batteryPct, isCharging) = delegate.getRealTimeBatteryInfo()
                        val timeRem = delegate.getSessionTimeRemaining()
                        val state = delegate.getAppState()
                        val ts = System.currentTimeMillis().toString()
                        val sig = KioskSecurity.generateTimestampSignature(deviceId, ts, secret)
                        val localIp = deviceIpProvider()
                        val devName = delegate.getDeviceName()
                        val encodedName = java.net.URLEncoder.encode(devName, "UTF-8")

                        val url = "http://$ipHost:$esp32Port/heartbeat?device_id=$deviceId&time_remaining=$timeRem&app_state=$state&battery=$batteryPct&charging=$isCharging&ts=$ts&sig=$sig&ip=$localIp&name=$encodedName"
                        val req = Request.Builder().url(url).build()

                        httpClient.newCall(req).execute().use { response ->
                            val code = response.code
                            if (response.isSuccessful || code == 423) {
                                consecutiveHeartbeatFailures = 0
                                lastHeartbeatTime = System.currentTimeMillis()
                                val body = response.body?.string() ?: ""
                                if (body.isNotBlank()) {
                                    try {
                                        Esp32PayloadHandler.parseHeartbeatJson(body, secret, delegate)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed parsing heartbeat JSON: ${e.message}")
                                    }
                                }
                            } else {
                                if (code == 403) {
                                    Log.w(TAG, "Heartbeat auth rejected by ESP32 (HTTP 403)")
                                }
                                consecutiveHeartbeatFailures++
                                if (consecutiveHeartbeatFailures >= 5) {
                                    delegate.onOnlineStatusChanged(false, null)
                                }
                            }
                        }
                    } else {
                        consecutiveHeartbeatFailures++
                        if (consecutiveHeartbeatFailures >= 5) {
                            delegate.onOnlineStatusChanged(false, null)
                        }
                    }
                } catch (e: Exception) {
                    consecutiveHeartbeatFailures++
                    if (consecutiveHeartbeatFailures >= 5) {
                        delegate.onOnlineStatusChanged(false, null)
                    }
                }
            }
        }
    }

    // ========================================================================
    // SINGLE-PATH WEBSOCKET ARMING & PAYMENT INGESTION (PORT 81)
    // ========================================================================

    fun armSlot(armingTimeoutSeconds: Int = 15) {
        val secret = delegate.getSecretKey().trim()
        if (secret.isEmpty()) {
            Log.e(TAG, "Arming rejected: Missing box secret key.")
            delegate.onArmFailed("Missing secret key")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Payment setup required: Please enter Box Secret Key in Security Vault.", Toast.LENGTH_LONG).show()
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
            Log.w(TAG, "Cannot arm slot immediately: No cached ESP32 IP. Initiating fast discovery...")
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
                    Log.e(TAG, "Arming failed: Could not discover ESP32 IP within timeout.")
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
            delegate.onArmFailed("Missing secret key")
            return
        }

        synchronized(connectionLock) {
            if (attemptId != currentAttemptId) return
            armingTimeoutJob?.cancel()
            armingTimeoutJob = scope.launch(Dispatchers.IO) {
                delay(ARMING_WATCHDOG_TIMEOUT_MS)
                synchronized(connectionLock) {
                    if (attemptId == currentAttemptId) {
                        Log.w(TAG, "Arming attempt #$attemptId timed out (no ARMED confirmation).")
                        forceCloseWebSocketLocked(activeWebSocket, "Arming watchdog timeout", attemptId)
                        delegate.onArmFailed("Arming timed out")
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
        Log.i(TAG, "⚡ Connecting arming WebSocket: $wsUrl (attempt #$attemptId)")

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
                        return
                    }
                }
                Log.d(TAG, "Master WebSocket onMessage (#$attemptId): $text")
                try {
                    val json = JSONObject(text)
                    val event = json.optString("event", "")

                    if (event == "COIN_DETECTED") {
                        val parsed = Esp32PayloadHandler.parseWebSocketCoinEvent(text, delegate.getSecretKey(), MAX_TIMESTAMP_SKEW_MS)
                        if (parsed != null) {
                            Log.i(TAG, "⚡ Validated Coin: +${parsed.seconds}s, amount=₱${parsed.amount}, txId=${parsed.txId}")
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
                        Log.i(TAG, "⚡ ESP32 ARMED confirmed (#$attemptId)")
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
                        synchronized(connectionLock) {
                            if (attemptId == currentAttemptId) {
                                armingTimeoutJob?.cancel()
                                armingTimeoutJob = null
                                forceCloseWebSocketLocked(webSocket, "Session terminated: $event", attemptId)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling WebSocket message: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(connectionLock) {
                    if (attemptId == currentAttemptId && webSocket === activeWebSocket) {
                        activeWebSocket = null
                        armingTimeoutJob?.cancel()
                        armingTimeoutJob = null
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: 0
                val msg = response?.message ?: t.message ?: ""
                Log.w(TAG, "Master WebSocket failure (attempt #$attemptId, HTTP $code): $msg")

                synchronized(connectionLock) {
                    if (attemptId == currentAttemptId) {
                        armingTimeoutJob?.cancel()
                        armingTimeoutJob = null
                        if (webSocket === activeWebSocket) {
                            activeWebSocket = null
                        }
                    }
                }

                when {
                    code == 409 -> {
                        delegate.onSlotBusy()
                        delegate.onArmFailed("Coin slot is currently busy with another device")
                    }
                    code == 403 -> {
                        delegate.onArmFailed("Authentication rejected by Master (check Secret Key)")
                    }
                    code == 423 || msg.contains("SLOT_EXPIRED", ignoreCase = true) -> {
                        delegate.onSlotLockdown("Slot license expired or locked down", 0, 0L)
                        delegate.onArmFailed("Device slot expired or locked down")
                    }
                    else -> {
                        delegate.onArmFailed("Connection to ESP32 failed: ${t.message ?: "Code $code"}")
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

    fun sendDonePayment() {
        synchronized(connectionLock) {
            val ws = activeWebSocket ?: return
            isDraining = true
            try {
                ws.send("DONE")
                Log.i(TAG, "Sent 'DONE' frame over WebSocket. Waiting for in-flight coin pulses...")
            } catch (e: Exception) {
                Log.e(TAG, "Failed sending 'DONE' frame: ${e.message}")
            }

            drainJob?.cancel()
            val attemptId = currentAttemptId
            drainJob = scope.launch(Dispatchers.IO) {
                try {
                    delay(DRAIN_SAFETY_TIMEOUT_MS)
                    forceCloseWebSocketIfAttemptCurrent(ws, "Drain timeout expired", attemptId)
                } catch (_: kotlinx.coroutines.CancellationException) {}
            }
        }
    }

    private fun forceCloseWebSocketIfAttemptCurrent(ws: WebSocket, reason: String, attemptId: Long) {
        synchronized(connectionLock) {
            if (attemptId == currentAttemptId && ws === activeWebSocket) {
                forceCloseWebSocketLocked(ws, reason, attemptId)
            }
        }
    }

    private fun forceCloseWebSocketLocked(ws: WebSocket?, reason: String, attemptId: Long) {
        if (ws == null) return
        try {
            ws.close(1000, reason)
        } catch (_: Exception) {}
        try {
            ws.cancel()
        } catch (_: Exception) {}
        if (ws === activeWebSocket) {
            activeWebSocket = null
        }
        isDraining = false
        drainJob?.cancel()
        drainJob = null
    }

    fun closeSession(sendUnarmToEsp: Boolean = false) {
        if (sendUnarmToEsp) {
            sendDonePayment()
        } else {
            synchronized(connectionLock) {
                currentAttemptId++
                armingTimeoutJob?.cancel()
                armingTimeoutJob = null
                forceCloseWebSocketLocked(activeWebSocket, "Close session", currentAttemptId)
            }
        }
    }

    fun shutdown() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        synchronized(connectionLock) {
            currentAttemptId++
            armingTimeoutJob?.cancel()
            armingTimeoutJob = null
            drainJob?.cancel()
            drainJob = null
            forceCloseWebSocketLocked(activeWebSocket, "Manager shutdown", currentAttemptId)
        }
        discoveryScanner.shutdown()
    }
}
