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
    fun onCoinMessageReceived(
        seconds: Int,
        amount: Double,
        txId: String?,
        operationKind: String,
        coinAmount: Int,
        pricePerCoin: Double,
        boxInstallationEpoch: Long,
        phonePairingEpoch: Long
    ): com.pisophone.kiosk.repository.PaymentResult
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
 * - Direct pairing via /api/slots/pair_request
 */
class Esp32ConnectionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val delegate: Esp32ConnectionDelegate
) {
    companion object {
        private const val TAG = "Esp32ConnectionManager"
        const val DEFAULT_STATIC_ESP32_IP = "192.168.1.10"
        const val DEFAULT_WEB_PORT = 80
        private const val ESP32_WS_PORT = 81
        private const val HEARTBEAT_TIMEOUT_MS = 45000L
        private const val MAX_TIMESTAMP_SKEW_MS = 60000L
        private const val DRAIN_SAFETY_TIMEOUT_MS = 15000L

        fun getEsp32HostAndPort(rawIp: String?): Pair<String, Int> {
            if (rawIp.isNullOrBlank()) return Pair(DEFAULT_STATIC_ESP32_IP, DEFAULT_WEB_PORT)
            val parts = rawIp.split(":")
            val host = parts[0].ifBlank { DEFAULT_STATIC_ESP32_IP }
            val port = if (parts.size > 1) parts[1].toIntOrNull() ?: DEFAULT_WEB_PORT else DEFAULT_WEB_PORT
            return Pair(host, port)
        }

        fun getLocalIpAddress(): String {
            try {
                var fallbackIp = ""
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces != null && interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                            val ip = address.hostAddress
                            if (!ip.isNullOrBlank() && ip != "127.0.0.1") {
                                val name = networkInterface.name.lowercase()
                                if (name.contains("wlan") ||
                                    name.contains("eth") ||
                                    name.contains("ap") ||
                                    name.contains("rndis")) {
                                    return ip
                                }
                                if (fallbackIp.isBlank()) fallbackIp = ip
                            }
                        }
                    }
                }
                return fallbackIp
            } catch (_: Exception) {}
            return ""
        }

        fun isEsp32MacMatching(expectedMac: String?, candidateMac: String?): Boolean {
            val cleanExpected = KioskSecurity.formatMacAddress(expectedMac ?: "")
            if (cleanExpected.isBlank()) return true
            val cleanCandidate = KioskSecurity.formatMacAddress(candidateMac ?: "")
            if (cleanCandidate.isBlank()) return false
            return cleanExpected.equals(cleanCandidate, ignoreCase = true)
        }
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

    private var esp32Ip: String? = KioskSecurity.getConfiguredEsp32Ip(context)
    private var lastHeartbeatTime: Long = System.currentTimeMillis()
    private var consecutiveHeartbeatFailures: Int = 0
    private val connectionLock = Any()
    private var currentAttemptId = 0L
    private var activeWebSocket: WebSocket? = null
    private var isDraining: Boolean = false
    private var drainJob: Job? = null
    private var heartbeatJob: Job? = null

    fun getEsp32Ip(): String? = esp32Ip

    fun setEsp32Ip(ip: String?) {
        esp32Ip = ip
    }

    fun markHeartbeatReceived() {
        lastHeartbeatTime = System.currentTimeMillis()
        delegate.onOnlineStatusChanged(true, null)
    }

    // ========================================================================
    // DIRECT PAIRING & CONNECTION (REPLACES LEGACY DISCOVERY)
    // ========================================================================

    fun sendDirectPairingRequest(
        targetIp: String? = null,
        targetMac: String? = null,
        onResult: ((Boolean, String?) -> Unit)? = null
    ): Job {
        val configuredIp = KioskSecurity.getConfiguredEsp32Ip(context).ifBlank { DEFAULT_STATIC_ESP32_IP }
        val host = (targetIp ?: esp32Ip ?: configuredIp).trim()
        val expectedMac = targetMac ?: KioskSecurity.getConfiguredEsp32Mac(context)

        return scope.launch(Dispatchers.IO) {
            try {
                val (ipHost, esp32Port) = getEsp32HostAndPort(host)
                val deviceId = KioskSecurity.getHardwareId(context)
                val myIp = getLocalIpAddress()
                val (curBat, isChg) = delegate.getRealTimeBatteryInfo()
                val myName = KioskSecurity.getDeviceAlias(context).takeIf { it.isNotBlank() } ?: "PisoPhone Terminal"
                val encodedName = java.net.URLEncoder.encode(myName, "UTF-8")
                val cleanMacParam = KioskSecurity.formatMacAddress(expectedMac)
                val macQuery = if (cleanMacParam.isNotBlank()) "&target_mac=$cleanMacParam" else ""

                val url = "http://$ipHost:$esp32Port/api/slots/pair_request?device_id=$deviceId&ip=$myIp&name=$encodedName&battery=$curBat&charging=${if (isChg) 1 else 0}&source=app&app=1&client=pisophone_app$macQuery"
                Log.i(TAG, "[DIRECT_PAIR] Sending pairing request to $ipHost:$esp32Port (Target MAC: ${cleanMacParam.ifEmpty { "Any" }})")

                val req = Request.Builder().url(url).build()
                httpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val json = JSONObject(body)
                        if (json.optBoolean("success", false)) {
                            val responseMac = json.optString("mac", "").trim()
                            if (cleanMacParam.isNotBlank() && responseMac.isNotBlank()) {
                                if (!isEsp32MacMatching(cleanMacParam, responseMac)) {
                                    val err = "MAC mismatch: target $cleanMacParam != ESP32 $responseMac"
                                    Log.w(TAG, "[-] Direct pairing rejected: $err")
                                    onResult?.invoke(false, err)
                                    return@launch
                                }
                            }

                            // If no MAC was previously saved, adopt and persist the ESP32's hardware MAC
                            if (cleanMacParam.isBlank() && responseMac.isNotBlank()) {
                                KioskSecurity.setConfiguredEsp32Mac(context, responseMac)
                            }

                            esp32Ip = ipHost
                            lastHeartbeatTime = System.currentTimeMillis()
                            consecutiveHeartbeatFailures = 0
                            val pairedSlot = json.optInt("slot", 0)
                            Log.i(TAG, "[+] Direct pairing SUCCESS at $ipHost (MAC=$responseMac, slot=$pairedSlot)")

                            delegate.onEsp32Discovered(ipHost)
                            delegate.onOnlineStatusChanged(true, responseMac.ifBlank { null })
                            if (pairedSlot > 0) {
                                delegate.onSlotRestored(pairedSlot)
                            }

                            fetchMasterConfig(ipHost, esp32Port)
                            onResult?.invoke(true, null)
                        } else {
                            val err = json.optString("error", "ESP32 rejected pair request")
                            Log.w(TAG, "[-] Pairing rejected by ESP32: $err")
                            onResult?.invoke(false, err)
                        }
                    } else {
                        Log.w(TAG, "[-] HTTP error ${resp.code} during pair request to $ipHost")
                        onResult?.invoke(false, "HTTP ${resp.code}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[-] Direct pairing failed: ${e.message}")
                onResult?.invoke(false, e.message)
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
                        val (host, esp32Port) = getEsp32HostAndPort(targetIp)
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
                                            sendDirectPairingRequest(targetIp)
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
                        // Not bound yet: directly send pairing request to configured IP & MAC
                        sendDirectPairingRequest()
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
            val staticIp = KioskSecurity.getConfiguredEsp32Ip(context).ifBlank { DEFAULT_STATIC_ESP32_IP }
            esp32Ip = staticIp
            sendDirectPairingRequest(targetIp = staticIp)
            Log.w(TAG, "ESP32 heartbeat failed ($consecutiveHeartbeatFailures failures, ${offlineDuration}ms offline), sent direct pairing request to $staticIp")
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
            Log.w(TAG, "Cannot arm slot: ESP32 IP not bound. Sending direct pairing request...")
            delegate.onOnlineStatusChanged(false, null)
            sendDirectPairingRequest()
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

                    if (event == "PULSE" || event == "COIN_DETECTED") {
                        val outerPayload = json.optString("payload", "")
                        if (outerPayload.isBlank()) {
                            val pulses = json.optInt("pulses", json.optInt("amount", 0))
                            if (pulses <= 0) {
                                Log.w(TAG, "Rejected WebSocket coin event: Invalid pulse count ($pulses)")
                                return
                            }
                            val secondsFromMsg = json.optInt("seconds", 0)
                            val minutesFromMsg = json.optInt("minutes", 0)
                            val seconds = if (secondsFromMsg > 0) {
                                secondsFromMsg
                            } else if (minutesFromMsg > 0) {
                                minutesFromMsg * 60
                            } else {
                                pulses * 600
                            }
                            val txId = json.optString("tx_id", "pulse_${System.currentTimeMillis()}_$pulses")
                            
                            Log.i(TAG, "⚡ Clean Pulse Received: +${pulses} pulses -> +${seconds}s (txId: $txId)")
                            delegate.onCoinMessageReceived(
                                seconds = seconds,
                                amount = pulses.toDouble(),
                                txId = txId,
                                operationKind = "COIN",
                                coinAmount = pulses,
                                pricePerCoin = 1.0,
                                boxInstallationEpoch = 0L,
                                phonePairingEpoch = 0L
                            )
                            
                            val ackJson = JSONObject().apply {
                                put("event", "ACK")
                                put("tx_id", txId)
                                put("amount", pulses)
                                put("seconds", seconds)
                                put("status", "OK")
                            }
                            webSocket.send(ackJson.toString())
                            return
                        }

                        val outerHmac = json.optString("v_sig", "").trim()
                        if (outerHmac.isBlank()) {
                            Log.w(TAG, "Rejected WebSocket coin event: Missing signature")
                            return
                        }

                        val outerTxId = json.optString("tx_id", "").trim()
                        if (outerTxId.isBlank()) {
                            Log.w(TAG, "Rejected WebSocket coin event: Missing tx_id in envelope")
                            return
                        }

                        val outerTsStr = json.optString("ts", "").trim()
                        val outerTs = outerTsStr.toLongOrNull() ?: 0L
                        val now = System.currentTimeMillis()
                        val skew = Math.abs(now - outerTs)
                        if (outerTs <= 0L || skew > MAX_TIMESTAMP_SKEW_MS) {
                            Log.w(TAG, "Rejected WebSocket coin event: Stale/invalid envelope timestamp ($outerTs, now=$now, skew=${skew}ms, max=${MAX_TIMESTAMP_SKEW_MS}ms)")
                            return
                        }

                        val outerDevId = json.optString("device_id", "").trim().ifBlank { delegate.getDeviceId() }
                        val myDevId = delegate.getDeviceId()
                        if (outerDevId.isNotBlank() && myDevId.isNotBlank() && !outerDevId.equals(myDevId, ignoreCase = true)) {
                            Log.w(TAG, "Rejected WebSocket coin event: Recipient mismatch (target='$outerDevId', local='$myDevId')")
                            return
                        }

                        val outerSeconds = if (json.has("seconds")) json.optInt("seconds") else null
                        val outerAmount = if (json.has("amount")) json.optDouble("amount") else null

                        val secretKey = delegate.getSecretKey()

                        // STEP 1: Verify integrity BEFORE decryption
                        if (!KioskSecurity.verifyWsPaySignature(
                                event = "COIN_DETECTED",
                                recipient = outerDevId,
                                txId = outerTxId,
                                ts = outerTsStr,
                                payload = outerPayload,
                                sig = outerHmac,
                                secret = secretKey
                            )) {
                            Log.w(TAG, "Rejected WebSocket coin event: Signature verification failed BEFORE decryption")
                            return
                        }

                        // STEP 2: Decrypt payload only after signature is verified
                        val decryptedStr = KioskSecurity.decrypt(outerPayload, secretKey)
                        if (decryptedStr.isBlank()) {
                            Log.w(TAG, "Rejected WebSocket coin event: Decryption failed or empty plaintext")
                            return
                        }

                        val decryptedJson = try {
                            JSONObject(decryptedStr)
                        } catch (e: Exception) {
                            Log.e(TAG, "Rejected WebSocket coin event: Malformed decrypted JSON: ${e.message}")
                            return
                        }

                        val innerTxId = decryptedJson.optString("tx_id", "").trim()
                        val innerDev = decryptedJson.optString("device_id", "").trim()
                        val innerTsStr = decryptedJson.optString("ts", "").trim()
                        val innerTs = innerTsStr.toLongOrNull() ?: 0L
                        val minutesLong = decryptedJson.optLong("minutes", 0L)
                        val secondsOptLong = decryptedJson.optLong("seconds", 0L)
                        val rawSeconds = if (secondsOptLong > 0L) secondsOptLong else (minutesLong * 60L)
                        val amount = decryptedJson.optDouble("amount", 0.0)

                        // STEP 3: Reconcile decrypted context with outer verified envelope
                        val envelope = com.pisophone.kiosk.protocol.KioskProtocol.VerifiedEnvelope(
                            event = "COIN_DETECTED",
                            recipient = outerDevId,
                            txId = outerTxId,
                            ts = outerTs,
                            payload = outerPayload,
                            seconds = outerSeconds,
                            amount = outerAmount
                        )
                        val innerContext = com.pisophone.kiosk.protocol.KioskProtocol.DecryptedPaymentContext(
                            deviceId = innerDev,
                            txId = innerTxId,
                            ts = innerTs,
                            seconds = rawSeconds.toInt(),
                            amount = amount
                        )
                        val reconResult = com.pisophone.kiosk.protocol.KioskProtocol.reconcilePaymentContext(envelope, innerContext)
                        if (reconResult is com.pisophone.kiosk.protocol.KioskProtocol.ProtocolValidationResult.Invalid) {
                            Log.w(TAG, "Rejected WebSocket coin event: Context reconciliation failed: ${reconResult.reason} (${reconResult.code})")
                            return
                        }

                        if (rawSeconds !in 1L..Int.MAX_VALUE.toLong() || amount.isNaN() || amount.isInfinite() || amount <= 0.0) {
                            Log.w(TAG, "Rejected WebSocket coin event: Invalid seconds ($rawSeconds) or amount ($amount)")
                            return
                        }
                        val seconds = rawSeconds.toInt()
                        val amountPulses = amount.toInt()
                        val txId = innerTxId.ifBlank { outerTxId }
                        val targetDev = innerDev.ifBlank { outerDevId }

                        val rawOpKind = decryptedJson.optString("op_kind", "1")
                        val opKindStr = when (rawOpKind) {
                            "1" -> "COIN"
                            "2" -> "QUICK_ADJUST"
                            "3" -> "MANUAL_DEDUCTION"
                            "4" -> "MATCH_TRANSFER"
                            "5" -> "CONTROLLER"
                            else -> rawOpKind
                        }
                        val pricePerCoin = decryptedJson.optDouble("price_per_coin", 0.0)
                        val boxEpoch = decryptedJson.optLong("box_installation_epoch", 0L)
                        val phoneEpoch = decryptedJson.optLong("phone_pairing_epoch", 0L)

                        Log.i(TAG, "⚡ Validated WebSocket Coin Processed: +${seconds}s, amount=₱$amount, txId=$txId, opKind=$opKindStr")
                        val result = delegate.onCoinMessageReceived(
                            seconds = seconds,
                            amount = amount,
                            txId = txId,
                            operationKind = opKindStr,
                            coinAmount = amountPulses,
                            pricePerCoin = pricePerCoin,
                            boxInstallationEpoch = boxEpoch,
                            phonePairingEpoch = phoneEpoch
                        )

                        // Send signed durable ACK back to ESP32 over WebSocket only if APPLIED or ALREADY_APPLIED
                        if (result == com.pisophone.kiosk.repository.PaymentResult.APPLIED || 
                            result == com.pisophone.kiosk.repository.PaymentResult.ALREADY_APPLIED) {
                            try {
                                val ackNow = System.currentTimeMillis()
                                val statusStr = if (result == com.pisophone.kiosk.repository.PaymentResult.ALREADY_APPLIED) "ALREADY_PROCESSED" else "OK"
                                val ackSig = KioskSecurity.calculateAckSignature(
                                    deviceId = targetDev,
                                    txId = txId,
                                    amount = amountPulses,
                                    seconds = seconds,
                                    ts = ackNow.toString(),
                                    status = statusStr,
                                    secret = secretKey
                                )
                                val ackJson = JSONObject().apply {
                                    put("event", "ACK")
                                    put("device_id", targetDev)
                                    put("tx_id", txId)
                                    put("amount", amountPulses)
                                    put("seconds", seconds)
                                    put("ts", ackNow.toString())
                                    put("v_sig", ackSig)
                                    put("status", statusStr)
                                }
                                webSocket.send(ackJson.toString())
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to send WebSocket ACK for $txId: ${e.message}")
                            }
                        } else {
                            Log.w(TAG, "WebSocket ACK suppressed for $txId due to payment result: $result")
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
    }
}
