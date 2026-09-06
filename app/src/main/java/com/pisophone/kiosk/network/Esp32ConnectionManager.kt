package com.pisophone.kiosk.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
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
    fun onConfigSynced(price: Double?, minutes: Int?, alias: String?, isUnlicensed: Boolean)
    fun onCoinMessageReceived(seconds: Int, amount: Double, txId: String?)
    fun onSlotBusy()
    fun onArmSuccess()
}

class Esp32ConnectionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val delegate: Esp32ConnectionDelegate
) {
    companion object {
        private const val TAG = "Esp32ConnectionManager"
        private const val NSD_SERVICE_TYPE = "_pisokiosk._tcp"
        private const val ESP32_WEB_PORT = 80
        private const val ESP32_WS_PORT = 81
        private const val HEARTBEAT_TIMEOUT_MS = 12000L
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    private var nsdManager: NsdManager? = null
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var isNsdDiscovering = false
    private var multicastLock: WifiManager.MulticastLock? = null

    private var esp32Ip: String? = null
    private var lastHeartbeatTime: Long = 0
    private var activeWebSocket: WebSocket? = null
    private var heartbeatJob: Job? = null

    fun getEsp32Ip(): String? = esp32Ip

    fun setEsp32Ip(ip: String?) {
        esp32Ip = ip
    }

    fun markHeartbeatReceived() {
        lastHeartbeatTime = System.currentTimeMillis()
        delegate.onOnlineStatusChanged(true, null)
    }

    fun getEsp32HostAndPort(rawIp: String?): Pair<String, Int> {
        if (rawIp.isNullOrBlank()) return Pair("", ESP32_WEB_PORT)
        val parts = rawIp.split(":")
        var host = parts[0]
        val port = if (parts.size > 1) parts[1].toIntOrNull() ?: ESP32_WEB_PORT else ESP32_WEB_PORT
        if (host == "127.0.0.1" || host == "localhost") host = "10.0.2.2"
        return Pair(host, port)
    }

    // ========================================================================
    // mDNS NSD DISCOVERY
    // ========================================================================

    fun startNsdDiscovery() {
        if (nsdManager == null) {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        }
        stopNsdDiscovery()

        if (multicastLock == null) {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("KioskMDNSLock")
            multicastLock?.setReferenceCounted(false)
        }
        if (multicastLock?.isHeld == false) {
            multicastLock?.acquire()
        }

        nsdDiscoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "mDNS Service discovery started for $regType")
                isNsdDiscovering = true
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "mDNS Service discovered: ${service.serviceName} (${service.serviceType})")
                resolveNsdService(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "mDNS Service lost: ${service.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "mDNS Discovery stopped: $serviceType")
                isNsdDiscovering = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "mDNS Discovery start failed: $errorCode")
                stopNsdDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "mDNS Discovery stop failed: $errorCode")
            }
        }

        try {
            nsdManager?.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, nsdDiscoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate NSD discovery: ${e.message}")
        }
    }

    private fun resolveNsdService(service: NsdServiceInfo) {
        try {
            nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "mDNS Service resolve failed: $errorCode")
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val resolvedHost = serviceInfo.host?.hostAddress
                    Log.d(TAG, "mDNS Service resolved: ${serviceInfo.serviceName} at $resolvedHost:${serviceInfo.port}")
                    if (!resolvedHost.isNullOrBlank() && resolvedHost != "127.0.0.1") {
                        handleEsp32Discovered(resolvedHost)
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "ResolveService error: ${e.message}")
        }
    }

    fun stopNsdDiscovery() {
        if (isNsdDiscovering && nsdDiscoveryListener != null) {
            try {
                nsdManager?.stopServiceDiscovery(nsdDiscoveryListener)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping NSD discovery: ${e.message}")
            }
            nsdDiscoveryListener = null
            isNsdDiscovering = false
        }
        if (multicastLock?.isHeld == true) {
            multicastLock?.release()
        }
    }

    // ========================================================================
    // IP PROBING
    // ========================================================================

    fun triggerCandidateDiscovery(localIp: String) {
        startNsdDiscovery()
        scope.launch(Dispatchers.IO) {
            probeCandidateIps(localIp)
        }
    }

    fun probeCandidateIps(localIp: String) {
        val candidates = mutableListOf<String>()
        val configured = KioskSecurity.getConfiguredEsp32Ip(context)
        if (configured.isNotBlank()) candidates.add(configured)

        esp32Ip?.let { if (it.isNotBlank() && !candidates.contains(it)) candidates.add(it) }

        if (localIp.isNotBlank() && localIp != "127.0.0.1" && localIp.contains(".")) {
            val subnet = localIp.substringBeforeLast(".")
            val gw = "$subnet.1"
            if (!candidates.contains(gw)) candidates.add(gw)
        }

        if (!candidates.contains("192.168.4.1")) candidates.add("192.168.4.1")
        if (!candidates.contains("10.0.2.2")) candidates.add("10.0.2.2")

        for (cand in candidates) {
            val success = probeEsp32Connection(cand)
            if (success) {
                Log.i(TAG, "Candidate probe succeeded for ESP32 at $cand")
                break
            }
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        val ip = address.hostAddress
                        if (ip != null) {
                            if (networkInterface.name.contains("wlan") || networkInterface.name.contains("eth")) {
                                return ip
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    fun probeEsp32Connection(ip: String): Boolean {
        val (host, esp32Port) = getEsp32HostAndPort(ip)
        val deviceId = delegate.getDeviceId()
        val ts = System.currentTimeMillis().toString()
        val sig = KioskSecurity.generateTimestampSignature(deviceId, ts, delegate.getSecretKey())
        val localIp = getLocalIpAddress()

        // 1. Try /identify
        try {
            val req = Request.Builder()
                .url("http://$host:${esp32Port}/identify")
                .build()
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string() ?: ""
                resp.close()
                if (body.contains("price") || body.contains("minutes") || body.contains("piso") || body.contains("esp32")) {
                    handleEsp32Discovered(host)
                    return true
                }
            } else {
                resp.close()
            }
        } catch (_: Exception) {}

        // 2. Try /heartbeat
        try {
            val (curBat, isChg) = delegate.getRealTimeBatteryInfo()
            val req = Request.Builder()
                .url("http://$host:${esp32Port}/heartbeat?device_id=$deviceId&ip=$localIp&time=${delegate.getSessionTimeRemaining()}&state=${delegate.getAppState()}&battery=$curBat&charging=${if (isChg) 1 else 0}&ts=$ts&sig=$sig")
                .build()
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                resp.close()
                handleEsp32Discovered(host)
                return true
            }
            resp.close()
        } catch (_: Exception) {}

        return false
    }

    private fun handleEsp32Discovered(ip: String) {
        val (ipHost, esp32Port) = getEsp32HostAndPort(ip)
        esp32Ip = ip
        stopNsdDiscovery()
        lastHeartbeatTime = System.currentTimeMillis()
        delegate.onEsp32Discovered(ip)
        delegate.onOnlineStatusChanged(true, null)
        Log.d(TAG, "[+] ESP32 Master bound at $ipHost")

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
                delegate.onConfigSynced(price, minutes, alias, false)
            }
            resp.close()
        } catch (_: Exception) {}
    }

    // ========================================================================
    // HEARTBEAT LOOP
    // ========================================================================

    fun startHeartbeatLoop(deviceIpProvider: () -> String) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val currentIp = deviceIpProvider()
                    val targetIp = esp32Ip ?: KioskSecurity.getConfiguredEsp32Ip(context).takeIf { it.isNotBlank() }

                    if (!targetIp.isNullOrBlank()) {
                        val (host, esp32Port) = getEsp32HostAndPort(targetIp)
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
                                lastHeartbeatTime = System.currentTimeMillis()
                                val body = response.body?.string() ?: ""
                                if (body.isNotBlank()) {
                                    try {
                                        val json = JSONObject(body)
                                        val mac = if (json.has("mac")) json.optString("mac", "") else null
                                        val isUnlicensed = json.optString("status") == "unlicensed"
                                        val alias = if (json.has("device_name")) json.optString("device_name", "").trim() else null
                                        val price = if (json.has("price")) json.optDouble("price", 5.0) else null
                                        val minutes = if (json.has("minutes")) json.optInt("minutes", 30) else null

                                        delegate.onOnlineStatusChanged(true, mac)
                                        delegate.onConfigSynced(price, minutes, alias, isUnlicensed)
                                    } catch (_: Exception) {}
                                } else {
                                    delegate.onOnlineStatusChanged(true, null)
                                }
                            }
                            response.close()
                        } catch (_: Exception) {
                            if (System.currentTimeMillis() - lastHeartbeatTime > HEARTBEAT_TIMEOUT_MS) {
                                delegate.onOnlineStatusChanged(false, null)
                                if (!isNsdDiscovering) {
                                    startNsdDiscovery()
                                }
                            }
                        }
                    } else {
                        probeCandidateIps(currentIp)
                        if (!isNsdDiscovering) {
                            startNsdDiscovery()
                        }
                    }
                } catch (_: Exception) {}
                delay(4000)
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
        var ip = esp32Ip ?: "10.0.2.2"
        if (ip == "127.0.0.1" || ip == "localhost") {
            ip = "10.0.2.2"
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
                    delegate.onOnlineStatusChanged(false, null)
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
                    val (host, esp32Port) = getEsp32HostAndPort(targetIp)
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
        stopNsdDiscovery()
    }
}
