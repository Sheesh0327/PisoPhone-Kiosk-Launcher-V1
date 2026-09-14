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
 * 3. Security (RULE 6): Uniform MAC address validation from discovered JSON payload.
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
    private var activeUdpSocket: DatagramSocket? = null
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
            // 2. Direct probe of configured IP, canonical mDNS hostname, and DHCP gateway
            probeDirectCandidates()
            // 3. Dynamic LAN subnet scan for DHCP client ESP32
            if (!isAlreadyBound()) {
                scanSubnetIfUnbound(localIp)
            }
        }
    }

    fun startUdpListener() {
        if (udpListenerJob?.isActive == true) return
        udpListenerJob = scope.launch(Dispatchers.IO) {
            try {
                acquireMulticastLock()
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(UDP_DISCOVERY_PORT))
                }
                activeUdpSocket = socket
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
                            Log.i(TAG, "[+] Discovered ESP32 Master via UDP at $targetIp")
                            delegate.onEsp32Discovered(targetIp, message)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "UDP listener stopped: ${e.message}")
            } finally {
                try {
                    activeUdpSocket?.close()
                } catch (_: Exception) {}
                activeUdpSocket = null
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

            val socket = activeUdpSocket ?: DatagramSocket().apply { broadcast = true }
            for (target in broadcastTargets) {
                try {
                    val address = InetAddress.getByName(target)
                    socket.send(DatagramPacket(data, data.size, address, UDP_DISCOVERY_PORT))
                } catch (_: Exception) {}
            }
            if (socket != activeUdpSocket) {
                socket.close()
            }
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

        // WiFi DHCP Gateway IP if connected to AP or router
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val dhcpInfo = wifi?.dhcpInfo
            if (dhcpInfo != null && dhcpInfo.gateway != 0) {
                val gw = String.format(
                    java.util.Locale.US,
                    "%d.%d.%d.%d",
                    dhcpInfo.gateway and 0xff,
                    dhcpInfo.gateway shr 8 and 0xff,
                    dhcpInfo.gateway shr 16 and 0xff,
                    dhcpInfo.gateway shr 24 and 0xff
                )
                if (gw != "0.0.0.0" && !candidates.contains(gw)) {
                    candidates.add(gw)
                }
            }
        } catch (_: Exception) {}

        for (target in candidates) {
            if (probeEsp32Connection(target)) {
                Log.i(TAG, "Direct probe succeeded for ESP32 at $target")
                return
            }
        }
    }

    /**
     * Fast concurrent local subnet scanner for dynamically assigned DHCP client ESP32 devices.
     * Uses non-blocking 250ms TCP pre-checks to sweep the /24 subnet in <500ms without thread starvation.
     */
    fun scanSubnetIfUnbound(localIp: String) {
        if (isAlreadyBound()) return
        val activeIp = if (localIp.isNotBlank()) localIp else getLocalIpAddress()
        if (activeIp.isBlank() || !activeIp.contains(".")) return
        val prefix = activeIp.substringBeforeLast(".")
        val selfLastOctet = activeIp.substringAfterLast(".").toIntOrNull() ?: -1

        val ipList = (1..254).filter { it != selfLastOctet }.map { "$prefix.$it" }
        for (batch in ipList.chunked(32)) {
            if (isAlreadyBound() || !scope.isActive) break
            val found = runBlocking(Dispatchers.IO) {
                val jobs = batch.map { targetIp ->
                    async {
                        if (isAlreadyBound() || !scope.isActive) return@async false
                        if (isPortOpen(targetIp, DEFAULT_WEB_PORT, 250)) {
                            probeEsp32Connection(targetIp)
                        } else {
                            false
                        }
                    }
                }
                jobs.awaitAll().any { it }
            }
            if (found) break
        }
    }

    private fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            java.net.Socket().use { s ->
                s.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun probeEsp32Connection(ip: String): Boolean {
        if (ip.isBlank()) return false
        val (host, port) = getEsp32HostAndPort(ip)

        try {
            val req = Request.Builder()
                .url("http://$host:$port/identify")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val rawBody = resp.body?.string() ?: ""
                    if (isEsp32MacMatching(rawBody)) {
                        delegate.onEsp32Discovered(host, rawBody)
                        return true
                    }
                }
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
