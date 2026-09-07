package com.pisophone.kiosk.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.pisophone.kiosk.security.KioskSecurity
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

interface Esp32DiscoveryDelegate {
    fun onEsp32Discovered(ip: String, rawResponseBody: String?)
}

/**
 * Industry-standard IoT Discovery Service for the ESP32 Master box:
 * 1. Fast Path: Direct probe of configured IP & canonical mDNS ("kioskmanager.local") via HTTP /identify.
 * 2. Dynamic Discovery: Standard UDP broadcast probe & beacon on port 8888.
 * 3. Hardware Lock (RULE 6): Uniform MAC address validation from discovered JSON payload.
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
        const val CANONICAL_MDNS_HOST = "kioskmanager.local"
        private const val DISCOVERY_PROBE_MSG = "{\"type\":\"PISOPHONE_DISCOVER\"}"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .writeTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    private var udpListenerJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    init {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("PisoPhoneDiscoveryLock")?.apply {
                setReferenceCounted(false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire MulticastLock: ${e.message}")
        }
    }

    fun triggerDiscovery(localIp: String) {
        startUdpListener()
        scope.launch(Dispatchers.IO) {
            // 1. Send standard UDP discovery broadcast
            sendUdpDiscoveryBroadcast(localIp)
            // 2. Direct probe of configured IP or canonical mDNS hostname
            probeDirectCandidates()
        }
    }

    fun startUdpListener() {
        if (udpListenerJob?.isActive == true) return
        udpListenerJob = scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                acquireMulticastLock()
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(UDP_DISCOVERY_PORT))
                }
                val buffer = ByteArray(2048)
                Log.d(TAG, "Started UDP Discovery Listener on port $UDP_DISCOVERY_PORT")

                val localIp = getLocalIpAddress()
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val senderIp = packet.address?.hostAddress ?: continue
                    if (senderIp == "127.0.0.1" || senderIp == localIp) continue

                    val message = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                    if (message.isBlank()) continue

                    // Validate canonical ESP32 response contract
                    if (message.contains("PISOPHONE_ESP32_RESPONSE")) {
                        var targetIp = senderIp
                        try {
                            val json = JSONObject(message)
                            val ipInJson = json.optString("ip", "")
                            if (ipInJson.isNotBlank() && ipInJson != "0.0.0.0" && ipInJson != "127.0.0.1") {
                                targetIp = ipInJson
                            }
                        } catch (_: Exception) {}

                        if (isEsp32MacMatching(message)) {
                            Log.i(TAG, "[+] Discovered ESP32 Master via UDP broadcast at $targetIp")
                            delegate.onEsp32Discovered(targetIp, message)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "UDP listener stopped: ${e.message}")
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {}
                releaseMulticastLock()
            }
        }
    }

    fun sendUdpDiscoveryBroadcast(localIp: String) {
        try {
            acquireMulticastLock()
            val data = DISCOVERY_PROBE_MSG.toByteArray(Charsets.UTF_8)
            val broadcastTargets = mutableListOf("255.255.255.255")

            val activeIp = if (localIp.isNotBlank()) localIp else getLocalIpAddress()
            if (activeIp.isNotBlank() && activeIp.contains(".")) {
                val subnet = activeIp.substringBeforeLast(".")
                broadcastTargets.add("$subnet.255")
            }

            val socket = DatagramSocket().apply { broadcast = true }
            for (target in broadcastTargets) {
                try {
                    val address = InetAddress.getByName(target)
                    socket.send(DatagramPacket(data, data.size, address, UDP_DISCOVERY_PORT))
                } catch (_: Exception) {}
            }
            socket.close()
            Log.d(TAG, "Dispatched UDP Discovery broadcast to targets: $broadcastTargets")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending UDP discovery probe: ${e.message}")
        }
    }

    /**
     * Direct probe for known candidates:
     * 1. Stored configured IP
     * 2. Canonical mDNS hostname "kioskmanager.local"
     */
    fun probeDirectCandidates() {
        val candidates = mutableListOf<String>()

        val configured = KioskSecurity.getConfiguredEsp32Ip(context)
        if (configured.isNotBlank()) {
            candidates.add(configured)
        }

        // Canonical mDNS host resolution
        try {
            val mDnsAddr = InetAddress.getByName(CANONICAL_MDNS_HOST)
            val resolvedIp = mDnsAddr.hostAddress
            if (!resolvedIp.isNullOrBlank() && !candidates.contains(resolvedIp)) {
                candidates.add(resolvedIp)
            }
        } catch (_: Exception) {}

        for (target in candidates) {
            if (probeEsp32Connection(target)) {
                Log.i(TAG, "Direct probe succeeded for ESP32 at $target")
                return
            }
        }
    }

    fun probeEsp32Connection(ip: String): Boolean {
        if (ip.isBlank()) return false
        val (host, port) = getEsp32HostAndPort(ip)

        try {
            val req = Request.Builder()
                .url("http://$host:$port/identify")
                .build()
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val rawBody = resp.body?.string() ?: ""
                resp.close()
                if (isEsp32MacMatching(rawBody)) {
                    delegate.onEsp32Discovered(host, rawBody)
                    return true
                }
            } else {
                resp.close()
            }
        } catch (_: Exception) {}

        return false
    }

    /**
     * Single validation path for ESP32 identity (RULE 6 - SINGLE-AUTH-PATH).
     * Extracts MAC from the response payload and validates against configured box MAC if set.
     */
    fun isEsp32MacMatching(rawResponseBody: String?): Boolean {
        val configuredMac = KioskSecurity.getConfiguredEsp32Mac(context)
        if (configuredMac.isBlank()) return true // No hardware MAC lock configured

        var deviceMac = ""
        if (!rawResponseBody.isNullOrBlank()) {
            try {
                val json = JSONObject(rawResponseBody)
                deviceMac = json.optString("mac", "")
                    .ifBlank { json.optString("esp32_mac", "") }
            } catch (_: Exception) {}
        }

        if (deviceMac.isNotBlank()) {
            val cleanDeviceMac = KioskSecurity.formatMacAddress(deviceMac)
            val cleanConfiguredMac = KioskSecurity.formatMacAddress(configuredMac)
            if (cleanDeviceMac.equals(cleanConfiguredMac, ignoreCase = true)) {
                return true
            } else {
                Log.w(TAG, "Rejected ESP32: MAC '$cleanDeviceMac' does not match configured box MAC '$cleanConfiguredMac'")
                return false
            }
        }

        return false
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}
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
                            if (networkInterface.name.contains("wlan") ||
                                networkInterface.name.contains("eth") ||
                                networkInterface.name.contains("ap") ||
                                networkInterface.name.contains("rndis")) {
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
        releaseMulticastLock()
    }
}
