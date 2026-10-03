package com.pisophone.kiosk.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.pisophone.kiosk.repository.PaymentResult
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

    /**
     * Credits a coin reported by the ESP32. The returned [PaymentResult] decides whether the
     * transaction is acknowledged to the ESP32: only APPLIED / ALREADY_APPLIED are acked, so the
     * ESP32 keeps the coin queued (and retries) when crediting failed.
     */
    fun onCoinMessageReceived(seconds: Int, amount: Double, txId: String?): PaymentResult
    fun onSlotBusy()
    fun onArmSuccess()
    fun onSlotWarning(daysLeft: Int, expiresAt: Long, slotNum: Int, message: String)
    fun onSlotLockdown(reason: String, slotNum: Int, expiresAt: Long)
    fun onSlotRestored(slotNum: Int = 0)
    fun onArenaModeSynced(active: Boolean, role: Int, stake: Int) {}
    fun getStoredEsp32Ip(): String? = null
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
    private val delegate: Esp32ConnectionDelegate,
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
    private var coinSyncJob: Job? = null
    private val processedTxIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
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
        },
    )

    fun setEsp32Ip(ip: String?) {
        esp32Ip = ip
        if (!ip.isNullOrBlank()) {
            sendPairingRequest(ip)
        }
    }

    // ========================================================================
    // DISCOVERY & PROBING DELEGATION
    // ========================================================================

    fun triggerCandidateDiscovery(localIp: String) {
        discoveryScanner.triggerDiscovery(localIp)
    }

    fun probeEsp32Connection(ip: String): Boolean = discoveryScanner.probeEsp32Connection(ip)

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
                val rawIp = discoveryScanner.getLocalIpAddress()
                val myIp = if (rawIp == "127.0.0.1" || rawIp.isNullOrBlank()) "" else rawIp
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
            val deviceId = KioskSecurity.getHardwareId(context)
            val myName = KioskSecurity.getDeviceAlias(context).takeIf { it.isNotBlank() } ?: "PisoPhone Terminal"
            val encodedName = java.net.URLEncoder.encode(myName, "UTF-8")
            val req = Request.Builder()
                .url("http://$ipHost:$esp32Port/identify?device_id=$deviceId&name=$encodedName&app=1&client=pisophone_app")
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
                        val myName = KioskSecurity.getDeviceAlias(context).takeIf { it.isNotBlank() } ?: "PisoPhone Terminal"
                        val encodedName = java.net.URLEncoder.encode(myName, "UTF-8")
                        val cleanIp = if (currentIp == "127.0.0.1" || currentIp.isBlank()) "" else currentIp

                        val req = Request.Builder()
                            .url("http://$host:$esp32Port/heartbeat?device_id=$deviceId&ip=$cleanIp&name=$encodedName&time=${delegate.getSessionTimeRemaining()}&state=${delegate.getAppState()}&battery=$curBat&charging=${if (isChg) 1 else 0}&ts=$ts&sig=$sig&source=app&app=1&client=pisophone_app")
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
                                            } else {
                                                null
                                            }

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
    // ROBUST COIN SLOT ARMING & PAYMENT LOGIC (PORT 80 HTTP + PORT 81 WS)
    // ========================================================================

    fun closeSession(sendUnarmToEsp: Boolean = false) {
        coinSyncJob?.cancel()
        coinSyncJob = null

        val ip = esp32Ip ?: delegate.getStoredEsp32Ip()
        val deviceId = delegate.getDeviceId()

        if (sendUnarmToEsp && !ip.isNullOrBlank()) {
            val (ipHost, _) = discoveryScanner.getEsp32HostAndPort(ip)
            if (ipHost.isNotBlank()) {
                scope.launch(Dispatchers.IO) {
                    sendHttpUnarm(ipHost, deviceId)
                }
            }
        }

        synchronized(connectionLock) {
            val ws = activeWebSocket
            if (ws != null) {
                if (sendUnarmToEsp) {
                    try { ws.send("DONE") } catch (_: Exception) {}
                }
                forceCloseWebSocketLocked(ws, "Session closed", currentAttemptId)
            }
        }
    }

    private fun sendHttpUnarm(ipHost: String, deviceId: String) {
        try {
            val unarmUrl = Esp32CoinslotRequests.signedUrl(
                host = ipHost,
                action = Esp32CoinslotRequests.ACTION_UNARM,
                deviceId = deviceId,
                secret = delegate.getSecretKey(),
            )
            val req = Request.Builder().url(unarmUrl).build()
            httpClient.newCall(req).execute().close()
            Log.d(TAG, "Sent HTTP unarm to $ipHost for $deviceId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send HTTP unarm to $ipHost: ${e.message}")
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
        var ip: String?
        synchronized(connectionLock) {
            attemptId = ++currentAttemptId
            forceCloseWebSocketLocked(activeWebSocket, "Re-arming slot", attemptId)
            coinSyncJob?.cancel()
            coinSyncJob = null
            processedTxIds.clear()
            ip = esp32Ip
            if (ip.isNullOrBlank()) {
                ip = delegate.getStoredEsp32Ip()
                if (!ip.isNullOrBlank()) {
                    esp32Ip = ip
                }
            }
        }

        // armSlot is invoked from UI click handlers on the main thread. Fast-path discovery and
        // the HTTP arm request are blocking network I/O, so run everything on the IO dispatcher.
        val initialIp = ip
        scope.launch(Dispatchers.IO) {
            try {
                performArm(attemptId, initialIp, armingTimeoutSeconds)
            } catch (e: Exception) {
                Log.e(TAG, "armSlot failed unexpectedly (attempt #$attemptId): ${e.message}", e)
                if (isAttemptCurrent(attemptId)) {
                    delegate.onSlotBusy()
                }
            }
        }
    }

    private fun isAttemptCurrent(attemptId: Long): Boolean = synchronized(connectionLock) {
        attemptId == currentAttemptId
    }

    private fun performArm(attemptId: Long, initialIp: String?, armingTimeoutSeconds: Int) {
        var (ipHost, _) = discoveryScanner.getEsp32HostAndPort(initialIp)
        if (ipHost.isBlank()) {
            val localIp = discoveryScanner.getLocalIpAddress()
            discoveryScanner.probeFastPathTargets(localIp)
            val refreshedIp = esp32Ip ?: delegate.getStoredEsp32Ip()
            ipHost = discoveryScanner.getEsp32HostAndPort(refreshedIp).first
        }

        if (!isAttemptCurrent(attemptId)) {
            Log.d(TAG, "Arm attempt #$attemptId superseded during discovery")
            return
        }

        if (ipHost.isBlank()) {
            Log.w(TAG, "Cannot arm slot: No discovered ESP32 IP available. Triggering discovery...")
            delegate.onOnlineStatusChanged(false, null)
            discoveryScanner.triggerDiscovery("")
            delegate.onSlotBusy()
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Searching for ESP32 hardware controller...", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val targetIpHost = ipHost
        val deviceId = delegate.getDeviceId()
        val localIp = discoveryScanner.getLocalIpAddress() ?: "127.0.0.1"

        // Execute primary reliable HTTP arming
        var httpArmSuccess = false
        try {
            val armUrl = Esp32CoinslotRequests.signedUrl(
                host = targetIpHost,
                action = Esp32CoinslotRequests.ACTION_ARM,
                deviceId = deviceId,
                secret = delegate.getSecretKey(),
                extraQuery = "ip=$localIp&duration=$armingTimeoutSeconds",
            )
            Log.d(TAG, "Requesting coin slot arm via HTTP: $armUrl (attempt #$attemptId)")
            val req = Request.Builder().url(armUrl).build()
            val resp = httpClient.newCall(req).execute()
            val code = resp.code
            val body = resp.body?.string() ?: ""
            resp.close()

            if (!isAttemptCurrent(attemptId)) {
                Log.d(TAG, "Ignoring HTTP arm response for stale attempt #$attemptId")
                return
            }

            if (code == 200) {
                httpArmSuccess = true
                Log.i(TAG, "⚡ ESP32 Coin Slot successfully ARMED via HTTP: $body")
                lastHeartbeatTime = System.currentTimeMillis()
                delegate.onOnlineStatusChanged(true, null)
                delegate.onArmSuccess()
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Coin slot ready (Insert coins - ${armingTimeoutSeconds}s)", Toast.LENGTH_SHORT).show()
                }

                // Start background polling synchronization loop to guarantee zero-drop reconciliation
                startCoinSyncLoop(targetIpHost, deviceId, armingTimeoutSeconds, attemptId)

                // Also connect WebSocket as opportunistic low-latency push channel
                try {
                    connectWebSocket(targetIpHost, deviceId, armingTimeoutSeconds, attemptId, isHttpArmed = true)
                } catch (e: Exception) {
                    Log.w(TAG, "Opportunistic WebSocket connect failed (HTTP arm still active): ${e.message}")
                }
            } else if (code == 409) {
                Log.w(TAG, "ESP32 Coin Slot is BUSY with another session (HTTP 409)")
                delegate.onSlotBusy()
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Slot is currently busy with another device.", Toast.LENGTH_LONG).show()
                }
            } else if (code == 423) {
                Log.e(TAG, "ESP32 Coin Slot is LOCKED/EXPIRED (HTTP 423): $body")
                if (body.contains("SLOT_NOT_PAIRED")) {
                    sendPairingRequest(targetIpHost)
                    delegate.onSlotBusy()
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "Device connection pending admin approval on ESP32 portal.", Toast.LENGTH_LONG).show()
                    }
                } else {
                    // onSlotLockdown clears the arming-in-progress flag.
                    delegate.onSlotLockdown("Please activate device slot on ESP32 Portal.", 0, 0L)
                }
            } else {
                Log.w(TAG, "HTTP armSlot returned unexpected status $code: $body; falling back to WebSocket")
                connectWebSocketOrFail(targetIpHost, deviceId, armingTimeoutSeconds, attemptId)
            }
        } catch (e: Exception) {
            if (!httpArmSuccess) {
                Log.w(TAG, "HTTP armSlot connection failed (${e.message}); attempting WebSocket direct arming")
                connectWebSocketOrFail(targetIpHost, deviceId, armingTimeoutSeconds, attemptId)
            }
        }
    }

    /**
     * WebSocket arming fallback. Any synchronous failure to even start the connection must
     * release the arming-in-progress flag, otherwise the INSERT COIN button stays disabled.
     */
    private fun connectWebSocketOrFail(ipHost: String, deviceId: String, armingTimeoutSeconds: Int, attemptId: Long) {
        try {
            connectWebSocket(ipHost, deviceId, armingTimeoutSeconds, attemptId, isHttpArmed = false)
        } catch (e: Exception) {
            Log.e(TAG, "WebSocket arming fallback could not start: ${e.message}")
            if (isAttemptCurrent(attemptId)) {
                delegate.onSlotBusy()
            }
        }
    }

    private fun startCoinSyncLoop(ipHost: String, deviceId: String, armingTimeoutSeconds: Int, attemptId: Long) {
        coinSyncJob?.cancel()
        coinSyncJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Started coin synchronization polling loop for $deviceId at $ipHost")
            while (isActive) {
                delay(1000L)
                val appState = delegate.getAppState()
                if (!com.pisophone.kiosk.service.SessionRules.isArmed(appState)) {
                    Log.d(TAG, "Coin sync loop exiting: appState is $appState")
                    break
                }
                synchronized(connectionLock) {
                    if (attemptId != currentAttemptId) return@launch
                }
                try {
                    // Signed: the box lists coins only to the phone they belong to, and only on a signed request.
                    val statusUrl = Esp32CoinslotRequests.signedUrl(
                        host = ipHost,
                        action = Esp32CoinslotRequests.ACTION_STATUS,
                        deviceId = deviceId,
                        secret = delegate.getSecretKey(),
                    )
                    val req = Request.Builder().url(statusUrl).build()
                    val resp = httpClient.newCall(req).execute()
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        if (body.isNotBlank()) {
                            val json = JSONObject(body)
                            for (coin in CoinStatusParser.ownedCoins(json, deviceId)) {
                                if (processedTxIds.add(coin.txId)) {
                                    Log.i(TAG, "⚡ Coin received via HTTP status sync: +${coin.seconds}s, ₱${coin.amount} (txId=${coin.txId})")
                                    handleCoinAndAck(ipHost, deviceId, coin.txId, coin.seconds, coin.amount)
                                }
                            }
                        }
                    }
                    resp.close()
                } catch (e: Exception) {
                    Log.d(TAG, "Polling status check error: ${e.message}")
                }
            }
        }
    }

    /**
     * Credits a coin and acknowledges it to the ESP32 ONLY when the credit is durably committed
     * (APPLIED) or was already committed earlier (ALREADY_APPLIED). On any other outcome the
     * txId is released from [processedTxIds] so the next status poll / WS push retries it, and
     * the ESP32 keeps the coin in its payment queue instead of discarding paid money.
     */
    private fun handleCoinAndAck(ipHost: String, deviceId: String, txId: String, seconds: Int, amount: Double) {
        val result = try {
            delegate.onCoinMessageReceived(seconds, amount, txId)
        } catch (e: Exception) {
            Log.e(TAG, "Coin credit threw for txId=$txId: ${e.message}", e)
            PaymentResult.FAILED
        }
        if (result == PaymentResult.APPLIED || result == PaymentResult.ALREADY_APPLIED) {
            sendTxAck(ipHost, deviceId, txId)
        } else {
            Log.w(TAG, "Coin txId=$txId not credited ($result); withholding ACK so ESP32 retries")
            processedTxIds.remove(txId)
        }
    }

    private fun sendTxAck(ipHost: String, deviceId: String, txId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val ackUrl = Esp32CoinslotRequests.signedUrl(
                    host = ipHost,
                    action = Esp32CoinslotRequests.ACTION_ACK,
                    deviceId = deviceId,
                    secret = delegate.getSecretKey(),
                    txId = txId,
                )
                val req = Request.Builder().url(ackUrl).build()
                httpClient.newCall(req).execute().close()
            } catch (_: Exception) {}
        }
    }

    private fun connectWebSocket(ipHost: String, deviceId: String, armingTimeoutSeconds: Int, attemptId: Long, isHttpArmed: Boolean = false) {
        val ts = System.currentTimeMillis().toString()
        val sig = KioskSecurity.generateTimestampSignature(deviceId, ts, delegate.getSecretKey())

        val wsUrl = "ws://$ipHost:$ESP32_WS_PORT/ws?device_id=$deviceId&ts=$ts&sig=$sig"
        Log.d(TAG, "Connecting to Master WebSocket at $ipHost:$ESP32_WS_PORT (attempt #$attemptId, isHttpArmed=$isHttpArmed)")

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
                if (!isHttpArmed) {
                    delegate.onArmSuccess()
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "Coin slot ready (Insert coins - ${armingTimeoutSeconds}s)", Toast.LENGTH_SHORT).show()
                    }
                    startCoinSyncLoop(ipHost, deviceId, armingTimeoutSeconds, attemptId)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Master WebSocket onMessage (attempt #$attemptId): $text")
                try {
                    val json = JSONObject(text)
                    val event = json.optString("event", "")

                    if (event == "COIN_DETECTED") {
                        var txId = json.optString("tx_id", "").trim()
                        var seconds = json.optInt("seconds", 0)
                        var amount = json.optDouble("amount", 0.0)

                        val payload = json.optString("payload", "")
                        if (payload.isNotBlank()) {
                            val secretKey = delegate.getSecretKey()
                            val decryptedStr = KioskSecurity.decrypt(payload, secretKey)
                            if (decryptedStr.isNotBlank()) {
                                try {
                                    val decryptedJson = JSONObject(decryptedStr)
                                    if (txId.isBlank()) txId = decryptedJson.optString("tx_id", "").trim()
                                    if (seconds <= 0) {
                                        val min = decryptedJson.optLong("minutes", 0L)
                                        val sec = decryptedJson.optLong("seconds", 0L)
                                        seconds = if (sec > 0L) sec.toInt() else (min * 60L).toInt()
                                    }
                                    if (amount <= 0.0) amount = decryptedJson.optDouble("amount", 0.0)
                                } catch (_: Exception) {}
                            }
                        }

                        if (txId.isNotBlank() && seconds > 0 && amount > 0.0) {
                            if (processedTxIds.add(txId)) {
                                Log.i(TAG, "⚡ Validated WebSocket Coin Processed: +${seconds}s, amount=₱$amount, txId=$txId")
                                handleCoinAndAck(ipHost, deviceId, txId, seconds, amount)
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
                Log.e(TAG, "WebSocket failure (attempt #$attemptId, HTTP $code, isHttpArmed=$isHttpArmed): $msg")
                if (isCurrent && !isHttpArmed) {
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
                        Log.w(TAG, "WebSocket connection failed (HTTP $code: $msg)")
                        delegate.onSlotBusy()
                    }
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
        coinSyncJob?.cancel()
        heartbeatJob?.cancel()
        forceCloseWebSocket(activeWebSocket, "Shutdown")
        discoveryScanner.shutdown()
    }
}
