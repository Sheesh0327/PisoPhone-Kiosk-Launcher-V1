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
    fun onConfigSynced(price: Double?, minutes: Int?, alias: String?)
    fun onCoinMessageReceived(seconds: Int, amount: Double, txId: String?)
    fun onSlotBusy()
    fun onArmSuccess()
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
        private const val HEARTBEAT_TIMEOUT_MS = 20000L
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3500, TimeUnit.MILLISECONDS)
        .readTimeout(3500, TimeUnit.MILLISECONDS)
        .build()

    private var esp32Ip: String? = null
    private var lastHeartbeatTime: Long = System.currentTimeMillis()
    private var consecutiveHeartbeatFailures: Int = 0
    private var activeWebSocket: WebSocket? = null
    private var heartbeatJob: Job? = null

    private val discoveryScanner = Esp32DiscoveryScanner(
        context = context,
        scope = scope,
        delegate = object : Esp32DiscoveryDelegate {
            override fun onEsp32Discovered(ip: String, rawResponseBody: String?) {
                handleEsp32Discovered(ip, rawResponseBody)
            }
        },
        isAlreadyBound = { esp32Ip != null }
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
        }
    }

    private fun fetchMasterConfig(ipHost: String, esp32Port: Int) {
        try {
            val req = Request.Builder()
                .url("http://$ipHost:${esp32Port}/identify")
                .build()
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string() ?: ""
                val json = JSONObject(body)
                val price = if (json.has("price")) json.optDouble("price", 5.0) else null
                val minutes = if (json.has("minutes")) json.optInt("minutes", 30) else null
                val alias = if (json.has("device_name")) json.optString("device_name", "").trim() else null
                delegate.onConfigSynced(price, minutes, alias)
            }
            resp.close()
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
                    val targetIp = esp32Ip ?: KioskSecurity.getConfiguredEsp32Ip(context).takeIf { it.isNotBlank() }

                    if (!targetIp.isNullOrBlank()) {
                        val (host, esp32Port) = discoveryScanner.getEsp32HostAndPort(targetIp)
                        val deviceId = delegate.getDeviceId()
                        val ts = System.currentTimeMillis().toString()
                        val sig = KioskSecurity.generateTimestampSignature(deviceId, ts, delegate.getSecretKey())
                        val (curBat, isChg) = delegate.getRealTimeBatteryInfo()

                        val req = Request.Builder()
                            .url("http://$host:${esp32Port}/heartbeat?device_id=$deviceId&ip=${if (currentIp == "127.0.0.1") "" else currentIp}&time=${delegate.getSessionTimeRemaining()}&state=${delegate.getAppState()}&battery=$curBat&charging=${if (isChg) 1 else 0}&ts=$ts&sig=$sig")
                            .build()
                        try {
                            val response = httpClient.newCall(req).execute()
                            if (response.isSuccessful) {
                                consecutiveHeartbeatFailures = 0
                                lastHeartbeatTime = System.currentTimeMillis()
                                val body = response.body?.string() ?: ""
                                if (body.isNotBlank()) {
                                    try {
                                        val json = JSONObject(body)
                                        val mac = if (json.has("mac")) json.optString("mac", "") else null
                                        val alias = if (json.has("device_name")) json.optString("device_name", "").trim() else null
                                        val price = if (json.has("price")) json.optDouble("price", 5.0) else null
                                        val minutes = if (json.has("minutes")) json.optInt("minutes", 30) else null

                                        delegate.onOnlineStatusChanged(true, mac)
                                        delegate.onConfigSynced(price, minutes, alias)
                                    } catch (_: Exception) {}
                                } else {
                                    delegate.onOnlineStatusChanged(true, null)
                                }
                            } else {
                                consecutiveHeartbeatFailures++
                                checkOfflineThreshold(currentIp)
                            }
                            response.close()
                        } catch (_: Exception) {
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
        if (consecutiveHeartbeatFailures >= 3 && offlineDuration > HEARTBEAT_TIMEOUT_MS) {
            delegate.onOnlineStatusChanged(false, null)
            // Broadcast discovery probe while offline to quickly rediscover if ESP32 changed IP
            discoveryScanner.sendUdpDiscoveryBroadcast(currentIp)
            if (offlineDuration > 30000L && KioskSecurity.getConfiguredEsp32Ip(context).isBlank()) {
                Log.w(TAG, "ESP32 disconnected for >30s, resetting cached IP for auto-rediscovery")
                esp32Ip = null
                discoveryScanner.triggerDiscovery(currentIp)
            }
        }
    }

    // ========================================================================
    // WEBSOCKET ARMING (PORT 81)
    // ========================================================================

    fun closeSession(sendUnarmToEsp: Boolean = false) {
        if (sendUnarmToEsp) {
            try {
                activeWebSocket?.send("DONE")
            } catch (_: Exception) {}
        }
        try {
            activeWebSocket?.close(1000, "Session closed")
        } catch (_: Exception) {}
        activeWebSocket = null
    }

    fun armSlot(armingTimeoutSeconds: Int) {
        closeSession(sendUnarmToEsp = false)
        val ip = esp32Ip ?: KioskSecurity.getConfiguredEsp32Ip(context).takeIf { it.isNotBlank() }
        if (ip.isNullOrBlank()) {
            Log.e(TAG, "Cannot arm slot: No active or configured ESP32 IP available")
            delegate.onOnlineStatusChanged(false, null)
            return
        }

        val deviceId = delegate.getDeviceId()
        val ts = System.currentTimeMillis().toString()
        val sig = KioskSecurity.generateTimestampSignature(deviceId, ts, delegate.getSecretKey())

        val wsUrl = "ws://$ip:$ESP32_WS_PORT/ws?device_id=$deviceId&ts=$ts&sig=$sig"
        Log.d(TAG, "Connecting to Master WebSocket on Port 81: $wsUrl")

        val request = Request.Builder().url(wsUrl).build()

        activeWebSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                lastHeartbeatTime = System.currentTimeMillis()
                delegate.onOnlineStatusChanged(true, null)
                delegate.onArmSuccess()
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Coin slot locked (Ready for coin - ${armingTimeoutSeconds}s)", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Master WebSocket onMessage (Port 81): $text")
                try {
                    val json = JSONObject(text)
                    val event = json.optString("event", "")

                    if (event == "COIN_DETECTED") {
                        val payload = json.optString("payload", "")
                        if (payload.isNotBlank()) {
                            val decryptedStr = KioskSecurity.decrypt(payload, delegate.getSecretKey())
                            if (decryptedStr.isNotBlank()) {
                                val decryptedJson = JSONObject(decryptedStr)
                                val seconds = decryptedJson.optInt("seconds", 1800)
                                val amount = decryptedJson.optDouble("amount", 5.0)
                                val txId = decryptedJson.optString("tx_id", "")
                                if (txId.isNotBlank()) {
                                    delegate.onCoinMessageReceived(seconds, amount, txId)
                                } else {
                                    Log.e(TAG, "Missing tx_id in decrypted WebSocket payload")
                                }
                            } else {
                                Log.e(TAG, "Failed to decrypt WebSocket coin payload")
                            }
                        } else {
                            Log.e(TAG, "Missing encrypted payload in WebSocket COIN_DETECTED event")
                        }
                    } else if (event == "TIMEOUT" || event == "CLOSED") {
                        Log.d(TAG, "Received $event event from ESP32 WebSocket")
                        closeSession(sendUnarmToEsp = false)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing WebSocket message: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: 0
                Log.e(TAG, "WebSocket failure (HTTP $code): ${t.message}")
                if (code == 409) {
                    Log.e(TAG, "Slot is BUSY with another session (HTTP 409)")
                    delegate.onSlotBusy()
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "Slot is currently busy with another device.", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Log.w(TAG, "WebSocket arming failed (HTTP $code) - letting heartbeat loop manage connectivity")
                }
                closeSession(sendUnarmToEsp = false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed (code=$code, reason=$reason)")
                activeWebSocket = null
            }
        })
    }

    fun sendActivationCode(code: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val targetIp = esp32Ip ?: KioskSecurity.getConfiguredEsp32Ip(context).takeIf { it.isNotBlank() }
                if (!targetIp.isNullOrBlank()) {
                    val (host, esp32Port) = discoveryScanner.getEsp32HostAndPort(targetIp)
                    val bodyReq = okhttp3.FormBody.Builder().add("code", code).build()
                    val req = Request.Builder()
                        .url("http://$host:${esp32Port}/activate")
                        .post(bodyReq)
                        .build()
                    httpClient.newCall(req).execute().close()
                }
            } catch (_: Exception) {}
        }
    }

    fun shutdown() {
        heartbeatJob?.cancel()
        closeSession(sendUnarmToEsp = false)
        discoveryScanner.shutdown()
    }
}
