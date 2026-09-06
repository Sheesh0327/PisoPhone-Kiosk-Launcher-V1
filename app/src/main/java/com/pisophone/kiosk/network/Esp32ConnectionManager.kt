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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
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
        val host = parts[0]
        val port = if (parts.size > 1) parts[1].toIntOrNull() ?: ESP32_WEB_PORT else ESP32_WEB_PORT
        return Pair(host, port)
    }

    // ========================================================================
    // IP PROBING & UDP BROADCAST HEARTBEATS (STRICT ESP32 MAC MATCHING)
    // ========================================================================

    private var udpListenerJob: Job? = null

    fun triggerCandidateDiscovery(localIp: String) {
        startUdpListener()
        scope.launch(Dispatchers.IO) {
            sendUdpBroadcastHeartbeat(localIp)
            probeCandidateIps(localIp)
        }
    }

    fun startUdpListener() {
        if (udpListenerJob?.isActive == true) return
        udpListenerJob = scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(8888)
                socket.broadcast = true
                val buffer = ByteArray(2048)
                Log.d(TAG, "Started UDP Broadcast Listener on port 8888")
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val senderIp = packet.address?.hostAddress
                    val message = String(packet.data, 0, packet.length)
                    if (!senderIp.isNullOrBlank() && senderIp != "127.0.0.1" && senderIp != getLocalIpAddress()) {
                        if (isEsp32MacMatching(senderIp, message)) {
                            handleEsp32Discovered(senderIp)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "UDP listener closed: ${e.message}")
            } finally {
                socket?.close()
            }
        }
    }

    fun sendUdpBroadcastHeartbeat(localIp: String) {
        try {
            val deviceId = delegate.getDeviceId()
            val ts = System.currentTimeMillis().toString()
            val sig = KioskSecurity.generateTimestampSignature(deviceId, ts, delegate.getSecretKey())
            val (curBat, isChg) = delegate.getRealTimeBatteryInfo()

            val json = JSONObject().apply {
                put("type", "HEARTBEAT")
                put("device_id", deviceId)
                put("ip", localIp)
                put("time", delegate.getSessionTimeRemaining())
                put("state", delegate.getAppState())
                put("battery", curBat)
                put("charging", if (isChg) 1 else 0)
                put("ts", ts)
                put("sig", sig)
            }

            val data = json.toString().toByteArray(Charsets.UTF_8)
            val broadcastTargets = mutableListOf<String>()
            broadcastTargets.add("255.255.255.255")

            if (localIp.isNotBlank() && localIp.contains(".")) {
                val subnet = localIp.substringBeforeLast(".")
                broadcastTargets.add("$subnet.255")
            }

            val ports = listOf(8080, 8888, 81)
            val socket = DatagramSocket()
            socket.broadcast = true

            for (target in broadcastTargets) {
                try {
                    val address = InetAddress.getByName(target)
                    for (port in ports) {
                        val packet = DatagramPacket(data, data.size, address, port)
                        socket.send(packet)
                    }
                } catch (_: Exception) {}
            }
            socket.close()
            Log.d(TAG, "Sent UDP Broadcast Heartbeat to targets: $broadcastTargets")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending UDP broadcast heartbeat: ${e.message}")
        }
    }

    fun probeCandidateIps(localIp: String) {
        val candidates = mutableListOf<String>()
        val configured = KioskSecurity.getConfiguredEsp32Ip(context)
        if (configured.isNotBlank()) candidates.add(configured)

        esp32Ip?.let { if (it.isNotBlank() && !candidates.contains(it)) candidates.add(it) }

        // Hostname resolution for mDNS hostnames
        val hostnames = listOf("kioskmanager.local", "pisokiosk.local", "esp32.local", "piso.local", "master.local")
        for (hn in hostnames) {
            try {
                val addr = InetAddress.getByName(hn)
                val resolvedIp = addr.hostAddress
                if (!resolvedIp.isNullOrBlank() && !candidates.contains(resolvedIp)) {
                    candidates.add(resolvedIp)
                }
            } catch (_: Exception) {}
        }

        val activeIp = if (localIp.isNotBlank() && localIp != "127.0.0.1") localIp else getLocalIpAddress()
        var subnet = ""
        if (activeIp.isNotBlank() && activeIp.contains(".")) {
            subnet = activeIp.substringBeforeLast(".")
            val gw = "$subnet.1"
            if (!candidates.contains(gw)) candidates.add(gw)
        }

        if (!candidates.contains("192.168.4.1")) candidates.add("192.168.4.1")

        // 1. Probe explicit candidate list first
        for (cand in candidates) {
            val success = probeEsp32Connection(cand)
            if (success) {
                Log.i(TAG, "Candidate probe succeeded for ESP32 at $cand")
                return
            }
        }

        // 2. Fast parallel sweep across active subnet if still not found
        if (subnet.isNotBlank() && subnet != "127.0.0" && esp32Ip == null) {
            Log.d(TAG, "Starting fast parallel subnet sweep on $subnet.1 - $subnet.254")
            probeSubnetInParallel(subnet, activeIp)
        }
    }

    private fun probeSubnetInParallel(subnet: String, myIp: String) {
        scope.launch(Dispatchers.IO) {
            val jobs = (1..254).map { i ->
                val targetIp = "$subnet.$i"
                if (targetIp == myIp) return@map null
                async {
                    if (esp32Ip != null) return@async false
                    probeEsp32Connection(targetIp)
                }
            }.filterNotNull()

            val results = jobs.awaitAll()
            if (results.any { it }) {
                Log.i(TAG, "Subnet parallel sweep successfully found ESP32 on $subnet.x")
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
                            if (networkInterface.name.contains("wlan") || networkInterface.name.contains("eth") || networkInterface.name.contains("ap") || networkInterface.name.contains("rndis")) {
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
        if (ip.isBlank()) return false
        val (host, esp32Port) = getEsp32HostAndPort(ip)
        val deviceId = delegate.getDeviceId()
        val ts = System.currentTimeMillis().toString()
        val sig = KioskSecurity.generateTimestampSignature(deviceId, ts, delegate.getSecretKey())
        val localIp = getLocalIpAddress()

        val probeKeywords = listOf("price", "minutes", "piso", "esp32", "status", "mac", "device", "chip", "ok", "version", "state", "time", "{")

        // 1. Try /identify
        try {
            val req = Request.Builder()
                .url("http://$host:${esp32Port}/identify")
                .build()
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val rawBody = resp.body?.string() ?: ""
                resp.close()
                val bodyLower = rawBody.lowercase()
                if (probeKeywords.any { bodyLower.contains(it) }) {
                    if (isEsp32MacMatching(host, rawBody)) {
                        handleEsp32Discovered(host)
                        return true
                    }
                }
            } else {
                resp.close()
            }
        } catch (_: Exception) {}

        // 2. Try /status or /
        try {
            val req = Request.Builder()
                .url("http://$host:${esp32Port}/status")
                .build()
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val rawBody = resp.body?.string() ?: ""
                resp.close()
                val bodyLower = rawBody.lowercase()
                if (probeKeywords.any { bodyLower.contains(it) }) {
                    if (isEsp32MacMatching(host, rawBody)) {
                        handleEsp32Discovered(host)
                        return true
                    }
                }
            } else {
                resp.close()
            }
        } catch (_: Exception) {}

        // 3. Try /heartbeat
        try {
            val (curBat, isChg) = delegate.getRealTimeBatteryInfo()
            val req = Request.Builder()
                .url("http://$host:${esp32Port}/heartbeat?device_id=$deviceId&ip=$localIp&time=${delegate.getSessionTimeRemaining()}&state=${delegate.getAppState()}&battery=$curBat&charging=${if (isChg) 1 else 0}&ts=$ts&sig=$sig")
                .build()
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val rawBody = resp.body?.string() ?: ""
                resp.close()
                if (isEsp32MacMatching(host, rawBody)) {
                    handleEsp32Discovered(host)
                    return true
                }
            }
            resp.close()
        } catch (_: Exception) {}

        return false
    }

    fun getMacFromArpTable(ip: String): String? {
        try {
            val file = java.io.File("/proc/net/arp")
            if (!file.exists()) return null
            file.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val parts = line!!.split("\\s+".toRegex())
                    if (parts.size >= 4 && parts[0] == ip) {
                        val mac = parts[3]
                        if (mac != "00:00:00:00:00:00" && mac.contains(":")) {
                            return mac.uppercase()
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun isEsp32MacMatching(ip: String, rawResponseBody: String?): Boolean {
        val configuredMac = KioskSecurity.getConfiguredEsp32Mac(context)
        if (configuredMac.isBlank()) return true // No hardware MAC lock configured

        var deviceMac = ""
        if (!rawResponseBody.isNullOrBlank()) {
            try {
                val json = JSONObject(rawResponseBody)
                deviceMac = json.optString("mac", "")
                    .ifBlank { json.optString("esp32_mac", "") }
                    .ifBlank { json.optString("hardware_mac", "") }
            } catch (_: Exception) {}
        }

        if (deviceMac.isBlank()) {
            deviceMac = getMacFromArpTable(ip) ?: ""
        }

        if (deviceMac.isNotBlank()) {
            val cleanDeviceMac = KioskSecurity.formatMacAddress(deviceMac)
            val cleanConfiguredMac = KioskSecurity.formatMacAddress(configuredMac)
            if (cleanDeviceMac.equals(cleanConfiguredMac, ignoreCase = true)) {
                return true
            } else {
                Log.w(TAG, "Rejected ESP32 at $ip: MAC '$cleanDeviceMac' does not match configured box MAC '$cleanConfiguredMac'")
                return false
            }
        }

        return true
    }

    private fun handleEsp32Discovered(ip: String) {
        val (ipHost, esp32Port) = getEsp32HostAndPort(ip)
        esp32Ip = ip
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
                            }
                        }
                    } else {
                        probeCandidateIps(currentIp)
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
    }
}
