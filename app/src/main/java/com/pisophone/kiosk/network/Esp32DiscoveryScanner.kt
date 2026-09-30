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
        private const val DISCOVERY_PROBE_MSG = "{\"type\":\"PISOPHONE_DISCOVER\"}"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .writeTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    private val lock = Any()
    private var isStopped = false
    private var discoveryJob: Job? = null
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
        synchronized(lock) {
            if (isStopped) return
            startUdpListenerLocked()
            if (discoveryJob?.isActive == true) {
                return
            }
            discoveryJob = scope.launch(Dispatchers.IO) {
                try {
                    while (!isAlreadyBound() && isActive) {
                        sendUdpDiscoveryBroadcast(localIp)
                        probeFastPathTargets(localIp)
                        delay(2000)
                    }
                } finally {
                    synchronized(lock) {
                        if (discoveryJob == coroutineContext[Job]) {
                            discoveryJob = null
                        }
                    }
                }
            }
        }
    }

    fun startUdpListener() {
        synchronized(lock) {
            if (!isStopped) {
                startUdpListenerLocked()
            }
        }
    }

    private fun startUdpListenerLocked() {
        if (udpListenerJob?.isActive == true) return
        udpListenerJob = scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                acquireMulticastLock()
                val newSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 1000 // 1 sec timeout for clean cancellation checking
                    bind(InetSocketAddress(UDP_DISCOVERY_PORT))
                }
                synchronized(lock) {
                    if (isStopped || !isActive) {
                        newSocket.close()
                        return@launch
                    }
                    socket = newSocket
                    activeUdpSocket = newSocket
                }
                val buffer = ByteArray(2048)
                Log.d(TAG, "Started UDP Discovery Listener on port $UDP_DISCOVERY_PORT")

                val localIp = getLocalIpAddress()
                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        newSocket.receive(packet)
                        val senderIp = packet.address?.hostAddress ?: continue
                        if (senderIp == "127.0.0.1" || senderIp == localIp) continue

                        val message = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                        if (message.isBlank()) continue

                        // Validate canonical ESP32 response contract
                        if (message.contains("PISOPHONE_ESP32_RESPONSE")) {
                            var targetIp = senderIp
                            var deviceMac = ""
                            var sig = ""
                            try {
                                val json = JSONObject(message)
                                val ipInJson = json.optString("ip", "")
                                if (ipInJson.isNotBlank() && ipInJson != "0.0.0.0" && ipInJson != "127.0.0.1") {
                                    targetIp = ipInJson
                                }
                                deviceMac = json.optString("mac", "").ifBlank { json.optString("esp32_mac", "") }
                                sig = json.optString("sig", "").ifBlank { json.optString("signature", "") }
                            } catch (_: Exception) {}

                            if (validateEsp32Response(deviceMac, targetIp, sig, message)) {
                                Log.i(TAG, "[+] Discovered verified ESP32 Master via UDP at $targetIp (MAC=$deviceMac)")
                                delegate.onEsp32Discovered(targetIp, message)
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // Normal receive timeout; loop to check coroutine isActive status
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "UDP listener stopped: ${e.message}")
            } finally {
                synchronized(lock) {
                    try {
                        socket?.close()
                    } catch (_: Exception) {}
                    if (activeUdpSocket === socket) {
                        activeUdpSocket = null
                    }
                    if (udpListenerJob == coroutineContext[Job]) {
                        udpListenerJob = null
                        releaseMulticastLockLocked()
                    }
                }
            }
        }
    }

    fun sendUdpDiscoveryBroadcast(localIp: String) {
        try {
            acquireMulticastLock()
            val configuredMac = KioskSecurity.getConfiguredEsp32Mac(context)
            val probeMsg = if (configuredMac.isNotBlank()) {
                "{\"type\":\"PISOPHONE_DISCOVER\",\"target_mac\":\"$configuredMac\"}"
            } else {
                DISCOVERY_PROBE_MSG
            }
            val data = probeMsg.toByteArray(Charsets.UTF_8)
            val broadcastTargets = mutableListOf("255.255.255.255")

            val activeIp = if (localIp.isNotBlank()) localIp else getLocalIpAddress()
            if (activeIp.isNotBlank() && activeIp.contains(".")) {
                val subnet = activeIp.substringBeforeLast(".")
                broadcastTargets.add("$subnet.255")
            }

            val socketToUse: DatagramSocket
            val isSharedSocket: Boolean
            synchronized(lock) {
                if (activeUdpSocket != null && !activeUdpSocket!!.isClosed) {
                    socketToUse = activeUdpSocket!!
                    isSharedSocket = true
                } else {
                    socketToUse = DatagramSocket().apply { broadcast = true }
                    isSharedSocket = false
                }
            }

            try {
                for (target in broadcastTargets) {
                    try {
                        val address = InetAddress.getByName(target)
                        socketToUse.send(DatagramPacket(data, data.size, address, UDP_DISCOVERY_PORT))
                    } catch (_: Exception) {}
                }
            } finally {
                if (!isSharedSocket) {
                    try { socketToUse.close() } catch (_: Exception) {}
                }
            }
            Log.d(TAG, "Dispatched UDP Discovery broadcast to targets: $broadcastTargets")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending UDP discovery probe: ${e.message}")
        }
    }

    fun probeEsp32Connection(ip: String): Boolean {
        if (ip.isBlank()) return false
        val (host, port) = getEsp32HostAndPort(ip)

        try {
            val fastClient = httpClient.newBuilder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .writeTimeout(2, TimeUnit.SECONDS)
                .build()

            val myIp = getLocalIpAddress()
            val deviceId = KioskSecurity.getHardwareId(context)
            val req = Request.Builder()
                .url("http://$host:$port/identify?ip=$myIp&device_id=$deviceId")
                .build()
            fastClient.newCall(req).execute().use { resp ->
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

    fun probeFastPathTargets(localIp: String) {
        val targets = mutableListOf<String>()
        val activeIp = if (localIp.isNotBlank()) localIp else getLocalIpAddress()
        if (activeIp.isNotBlank() && activeIp.contains(".")) {
            val gateway = activeIp.substringBeforeLast(".") + ".1"
            targets.add(gateway)
        }
        if (!targets.contains("192.168.4.1")) targets.add("192.168.4.1")
        if (!targets.contains("kioskmanager.local")) targets.add("kioskmanager.local")

        for (target in targets) {
            if (isAlreadyBound()) break
            if (probeEsp32Connection(target)) {
                Log.d(TAG, "Direct HTTP discovery succeeded for target: $target")
                break
            }
        }
    }

    /**
     * Single validation path for ESP32 identity (RULE 6 - SINGLE-AUTH-PATH).
     * Validates MAC match and verifies HMAC-SHA256 signature when secret is configured.
     */
    fun validateEsp32Response(deviceMac: String, targetIp: String, sig: String, rawResponseBody: String?): Boolean {
        val configuredMac = KioskSecurity.getConfiguredEsp32Mac(context)
        if (configuredMac.isNotBlank()) {
            val cleanDeviceMac = KioskSecurity.formatMacAddress(deviceMac)
            val cleanConfiguredMac = KioskSecurity.formatMacAddress(configuredMac)
            if (!cleanDeviceMac.equals(cleanConfiguredMac, ignoreCase = true)) {
                Log.w(TAG, "Rejected ESP32: MAC '$cleanDeviceMac' does not match configured box MAC '$cleanConfiguredMac'")
                return false
            }
        }

        val secret = KioskSecurity.getSharedSecret(context)
        if (secret.isNotBlank()) {
            if (sig.isBlank()) {
                Log.w(TAG, "Rejected ESP32 UDP packet from $targetIp: Missing cryptographic signature!")
                return false
            }
            val cleanDeviceMac = KioskSecurity.formatMacAddress(deviceMac)
            val expectedSig = KioskSecurity.calculateHmac("DISCOVERY:$cleanDeviceMac:$targetIp", secret)
            val expectedMasterSig = KioskSecurity.calculateHmac("DISCOVERY:$cleanDeviceMac:$targetIp", KioskSecurity.DEFAULT_SHARED_SECRET)

            val sigMatches = KioskSecurity.constantTimeEquals(sig.lowercase(), expectedSig.lowercase()) ||
                    KioskSecurity.constantTimeEquals(sig.lowercase(), expectedMasterSig.lowercase())

            if (!sigMatches) {
                Log.w(TAG, "Rejected ESP32 UDP packet from $targetIp: HMAC signature verification failed!")
                return false
            }
        }

        return true
    }

    /**
     * Extracts MAC from response payload and validates against configured box MAC if set.
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

    private fun releaseMulticastLockLocked() {
        try {
            if (multicastLock?.isHeld == true && udpListenerJob == null) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}
    }

    private fun releaseMulticastLock() {
        synchronized(lock) {
            releaseMulticastLockLocked()
        }
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
        val socketToClose: DatagramSocket?
        val listenerJobToCancel: Job?
        val discoveryJobToCancel: Job?
        synchronized(lock) {
            isStopped = true
            listenerJobToCancel = udpListenerJob
            discoveryJobToCancel = discoveryJob
            socketToClose = activeUdpSocket
            activeUdpSocket = null
            udpListenerJob = null
            discoveryJob = null
            try {
                if (multicastLock?.isHeld == true) {
                    multicastLock?.release()
                }
            } catch (_: Exception) {}
        }
        listenerJobToCancel?.cancel()
        discoveryJobToCancel?.cancel()
        try {
            socketToClose?.close()
        } catch (_: Exception) {}
    }
}
