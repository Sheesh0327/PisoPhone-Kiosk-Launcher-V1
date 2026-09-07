package com.pisophone.kiosk.network

import android.content.Context
import android.util.Log
import com.pisophone.kiosk.security.KioskSecurity
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit

interface Esp32DiscoveryDelegate {
    fun getDeviceId(): String
    fun getSecretKey(): String
    fun getAppState(): Int
    fun getSessionTimeRemaining(): Int
    fun getRealTimeBatteryInfo(): Pair<Int, Boolean>
    fun onEsp32Discovered(ip: String, rawResponseBody: String?)
}

/**
 * Handles network discovery of the ESP32 Master kiosk box:
 * - UDP broadcast listening & transmission
 * - Candidate hostname / gateway / AP resolution
 * - Fast parallel subnet sweep (1..254)
 * - ARP table inspection & Hardware MAC address verification
 */
class Esp32DiscoveryScanner(
    private val context: Context,
    private val scope: CoroutineScope,
    private val delegate: Esp32DiscoveryDelegate,
    private val isAlreadyBound: () -> Boolean
) {
    companion object {
        private const val TAG = "Esp32DiscoveryScanner"
        const val DEFAULT_WEB_PORT = 80
        const val UDP_DISCOVERY_PORT = 8888
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .writeTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    private var udpListenerJob: Job? = null

    fun triggerDiscovery(localIp: String) {
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
                socket = DatagramSocket(UDP_DISCOVERY_PORT)
                socket.broadcast = true
                val buffer = ByteArray(1024)
                Log.d(TAG, "Started UDP Broadcast Listener on port $UDP_DISCOVERY_PORT")
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val senderIp = packet.address?.hostAddress
                    val message = String(packet.data, 0, packet.length)
                    if (!senderIp.isNullOrBlank() && senderIp != "127.0.0.1" && senderIp != getLocalIpAddress()) {
                        if (isEsp32MacMatching(senderIp, message)) {
                            handleEsp32Found(senderIp, message)
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

            val ports = listOf(8080, UDP_DISCOVERY_PORT, 81)
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
        if (subnet.isNotBlank() && subnet != "127.0.0" && !isAlreadyBound()) {
            Log.d(TAG, "Starting fast parallel subnet sweep on $subnet.1 - $subnet.254")
            probeSubnetInParallel(subnet, activeIp)
        }
    }

    fun probeSubnetInParallel(subnet: String, myIp: String) {
        scope.launch(Dispatchers.IO) {
            val jobs = (1..254).map { i ->
                val targetIp = "$subnet.$i"
                if (targetIp == myIp) return@map null
                async {
                    if (isAlreadyBound()) return@async false
                    probeEsp32Connection(targetIp)
                }
            }.filterNotNull()

            val results = jobs.awaitAll()
            if (results.any { it }) {
                Log.i(TAG, "Subnet parallel sweep successfully found ESP32 on $subnet.x")
            }
        }
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
                        handleEsp32Found(host, rawBody)
                        return true
                    }
                }
            } else {
                resp.close()
            }
        } catch (_: Exception) {}

        // 2. Try /status
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
                        handleEsp32Found(host, rawBody)
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
                    handleEsp32Found(host, rawBody)
                    return true
                }
            }
            resp.close()
        } catch (_: Exception) {}

        return false
    }

    private fun handleEsp32Found(ip: String, rawResponseBody: String?) {
        delegate.onEsp32Discovered(ip, rawResponseBody)
    }

    fun isEsp32MacMatching(ip: String, rawResponseBody: String?): Boolean {
        val configuredMac = KioskSecurity.getConfiguredEsp32Mac(context)
        if (configuredMac.isBlank()) return true // No hardware MAC lock configured

        var deviceMac = ""
        if (!rawResponseBody.isNullOrBlank()) {
            try {
                val json = JSONObject(rawResponseBody)
                deviceMac = json.optString("mac", "")
                    .ifBlank { json.optString("esp32_mac", "") }
                    .ifBlank { json.optString("hardware_mac", "") }
                    .ifBlank { json.optString("box_mac", "") }
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

    fun getLocalIpAddress(): String {
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

    fun getEsp32HostAndPort(rawIp: String?): Pair<String, Int> {
        if (rawIp.isNullOrBlank()) return Pair("", DEFAULT_WEB_PORT)
        val parts = rawIp.split(":")
        val host = parts[0]
        val port = if (parts.size > 1) parts[1].toIntOrNull() ?: DEFAULT_WEB_PORT else DEFAULT_WEB_PORT
        return Pair(host, port)
    }

    fun shutdown() {
        udpListenerJob?.cancel()
    }
}
