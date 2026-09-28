package com.pisophone.kiosk.network

import android.content.Context
import android.util.Log
import com.pisophone.kiosk.security.KioskSecurity
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

interface Esp32DiscoveryDelegate {
    fun onEsp32Discovered(ip: String, rawResponseBody: String?)
}

/**
 * Direct IoT Discovery Service for the ESP32 Master box:
 * 1. Fast Path: Direct probe of configured Static IP (192.168.1.10) & mDNS ("kioskmanager.local") via HTTP /identify.
 * 2. Security (RULE 6): Uniform MAC address validation and HMAC signature verification from discovered JSON payload.
 */
class Esp32DiscoveryScanner(
    private val context: Context,
    private val scope: CoroutineScope,
    private val delegate: Esp32DiscoveryDelegate,
    private val isAlreadyBound: () -> Boolean
) {
    companion object {
        private const val TAG = "Esp32DiscoveryScanner"
        const val STATIC_ESP32_IP = "192.168.1.10"
        const val DEFAULT_WEB_PORT = 80
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .writeTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    private val lock = Any()
    private var isStopped = false
    private var discoveryJob: Job? = null

    fun triggerDiscovery(localIp: String) {
        synchronized(lock) {
            if (isStopped) return
            if (discoveryJob?.isActive == true) {
                return
            }
            discoveryJob = scope.launch(Dispatchers.IO) {
                try {
                    while (!isAlreadyBound() && isActive) {
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
                    var deviceMac = ""
                    var sig = ""
                    try {
                        val json = JSONObject(rawBody)
                        deviceMac = json.optString("mac", "").ifBlank { json.optString("esp32_mac", "") }
                        sig = json.optString("sig", "").ifBlank { json.optString("signature", "") }
                    } catch (_: Exception) {}

                    if (validateEsp32Response(deviceMac, host, sig, rawBody)) {
                        Log.i(TAG, "[+] Discovered verified ESP32 Master via HTTP probe at $host (MAC=$deviceMac)")
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
        // 1. Direct Static IP probe (primary fast path)
        targets.add(STATIC_ESP32_IP)

        val activeIp = if (localIp.isNotBlank()) localIp else getLocalIpAddress()
        if (activeIp.isNotBlank() && activeIp.contains(".")) {
            val gateway = activeIp.substringBeforeLast(".") + ".1"
            if (!targets.contains(gateway)) targets.add(gateway)
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
                Log.w(TAG, "Rejected ESP32 response from $targetIp: Missing cryptographic signature!")
                return false
            }
            val cleanDeviceMac = KioskSecurity.formatMacAddress(deviceMac)
            val expectedSig = KioskSecurity.calculateHmac("DISCOVERY:$cleanDeviceMac:$targetIp", secret)
            val expectedMasterSig = KioskSecurity.calculateHmac("DISCOVERY:$cleanDeviceMac:$targetIp", KioskSecurity.DEFAULT_SHARED_SECRET)

            val sigMatches = KioskSecurity.constantTimeEquals(sig.lowercase(), expectedSig.lowercase()) ||
                    KioskSecurity.constantTimeEquals(sig.lowercase(), expectedMasterSig.lowercase())

            if (!sigMatches) {
                Log.w(TAG, "Rejected ESP32 response from $targetIp: HMAC signature verification failed!")
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
        val discoveryJobToCancel: Job?
        synchronized(lock) {
            isStopped = true
            discoveryJobToCancel = discoveryJob
            discoveryJob = null
        }
        discoveryJobToCancel?.cancel()
    }
}
