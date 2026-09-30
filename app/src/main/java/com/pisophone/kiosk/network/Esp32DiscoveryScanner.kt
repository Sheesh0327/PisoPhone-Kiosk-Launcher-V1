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
 * Direct Static IP Connection Scanner for the ESP32 Master box:
 * 1. Strict Static IP Path: Direct HTTP probe exclusively targeting injected/configured static IP.
 * 2. No Fallbacks: Does not attempt gateway, AP mode, or mDNS dynamic discovery probes.
 * 3. MAC Validation: Uniform MAC address validation from discovered JSON payload.
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
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .writeTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    private val lock = Any()
    private var isStopped = false
    private var discoveryJob: Job? = null

    fun triggerDiscovery(localIp: String = "") {
        synchronized(lock) {
            if (isStopped) return
            if (discoveryJob?.isActive == true) return
            discoveryJob = scope.launch(Dispatchers.IO) {
                try {
                    while (!isAlreadyBound() && isActive) {
                        probeCandidates(localIp)
                        delay(2500)
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

    fun probeCandidates(localIp: String = "") {
        val configuredIp = KioskSecurity.getConfiguredEsp32Ip(context)
        if (configuredIp.isNotBlank()) {
            if (!isAlreadyBound() && !isStopped) {
                probeEsp32Connection(configuredIp)
            }
        } else {
            Log.d(TAG, "No configured static ESP32 IP stored yet. Awaiting WebADB setup injection or manual admin configuration.")
        }
    }

    fun probeEsp32Connection(ip: String): Boolean {
        if (ip.isBlank()) return false
        val (host, port) = getEsp32HostAndPort(ip)

        try {
            val fastClient = httpClient.newBuilder()
                .connectTimeout(1500, TimeUnit.MILLISECONDS)
                .readTimeout(1500, TimeUnit.MILLISECONDS)
                .writeTimeout(1500, TimeUnit.MILLISECONDS)
                .build()

            val devAlias = KioskSecurity.getDeviceAlias(context).ifBlank { android.os.Build.MODEL ?: "PisoPhone Terminal" }
            val encodedName = java.net.URLEncoder.encode(devAlias, "UTF-8")
            val req = Request.Builder()
                .url("http://$host:$port/identify?name=$encodedName")
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

    /**
     * Single validation path for ESP32 identity.
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
            if (!KioskSecurity.constantTimeEquals(sig.lowercase(), expectedSig.lowercase())) {
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
