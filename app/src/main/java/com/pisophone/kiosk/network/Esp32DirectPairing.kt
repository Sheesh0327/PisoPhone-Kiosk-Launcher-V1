package com.pisophone.kiosk.network

import android.content.Context
import android.util.Log
import com.pisophone.kiosk.security.KioskSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Handles direct HTTP-based device pairing, slot unpairing, and master config discovery with the ESP32.
 */
class Esp32DirectPairing(
    private val context: Context,
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient,
    private val delegate: Esp32ConnectionDelegate
) {
    companion object {
        private const val TAG = "Esp32DirectPairing"
        const val DEFAULT_STATIC_ESP32_IP = "192.168.1.10"
        const val DEFAULT_WEB_PORT = 80

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
            } catch (e: Exception) {}
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

    private val isPairingInProgress = java.util.concurrent.atomic.AtomicBoolean(false)

    fun sendDirectPairingRequest(
        targetIp: String? = null,
        targetMac: String? = null,
        currentEsp32Ip: String? = null,
        onSuccess: (String, String) -> Unit,
        onResult: ((Boolean, String?) -> Unit)? = null
    ): Job {
        val configuredIp = KioskSecurity.getConfiguredEsp32Ip(context).ifBlank { DEFAULT_STATIC_ESP32_IP }
        val host = (targetIp ?: currentEsp32Ip ?: configuredIp).trim()
        val expectedMac = targetMac ?: KioskSecurity.getConfiguredEsp32Mac(context)

        return scope.launch(Dispatchers.IO) {
            if (!isPairingInProgress.compareAndSet(false, true)) {
                Log.d(TAG, "[DIRECT_PAIR] Pairing request already in progress; skipping duplicate attempt.")
                onResult?.invoke(false, "Pairing already in progress")
                return@launch
            }
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

                            if (cleanMacParam.isBlank() && responseMac.isNotBlank()) {
                                KioskSecurity.setConfiguredEsp32Mac(context, responseMac)
                            }

                            val pairedSlot = json.optInt("slot", 0)
                            Log.i(TAG, "[+] Direct pairing SUCCESS at $ipHost (MAC=$responseMac, slot=$pairedSlot)")

                            onSuccess(ipHost, responseMac)
                            delegate.onEsp32Paired(ipHost)
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
            } finally {
                isPairingInProgress.set(false)
            }
        }
    }

    fun unpair(
        currentEsp32Ip: String?,
        force: Boolean = false,
        onLocalStateReset: () -> Unit,
        onResult: ((Boolean, String?) -> Unit)? = null
    ): Job {
        return scope.launch(Dispatchers.IO) {
            try {
                val assignedSlot = KioskSecurity.getAssignedBoxSlot(context)
                val configuredIp = KioskSecurity.getConfiguredEsp32Ip(context).ifBlank { DEFAULT_STATIC_ESP32_IP }
                val (ipHost, esp32Port) = getEsp32HostAndPort(currentEsp32Ip ?: configuredIp)

                val emptyBody = okhttp3.RequestBody.create(null, ByteArray(0))
                val url = "http://$ipHost:$esp32Port/api/slots/unpair?slot=$assignedSlot"
                val req = Request.Builder().url(url).post(emptyBody).build()

                var success = false
                var errorMsg: String? = null

                try {
                    httpClient.newCall(req).execute().use { resp ->
                        val respBody = resp.body?.string()?.trim() ?: ""
                        Log.i(TAG, "[UNPAIR] ESP32 unpair HTTP response: code=${resp.code}, body=$respBody")

                        if (resp.isSuccessful) {
                            try {
                                val json = JSONObject(respBody)
                                if (json.optBoolean("success", false)) {
                                    success = true
                                } else {
                                    errorMsg = json.optString("error", "ESP32 rejected unpair request.")
                                }
                            } catch (e: Exception) {
                                errorMsg = "Malformed response from ESP32: ${e.message}"
                            }
                        } else {
                            when (resp.code) {
                                401, 403 -> {
                                    errorMsg = "Unpair requires admin authentication. Please log in to the ESP32 Admin Web Portal at http://$ipHost:$esp32Port to unpair slot #$assignedSlot."
                                }
                                409 -> {
                                    val jsonErr = try { JSONObject(respBody).optString("error", "") } catch (e: Exception) { "" }
                                    errorMsg = if (jsonErr.isNotBlank()) jsonErr else "Slot #$assignedSlot is busy with an active session or unresolved payments."
                                }
                                else -> {
                                    val jsonErr = try { JSONObject(respBody).optString("error", "") } catch (e: Exception) { "" }
                                    errorMsg = if (jsonErr.isNotBlank()) jsonErr else "ESP32 rejected unpair (HTTP ${resp.code})."
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[UNPAIR] Network failure contacting ESP32: ${e.message}")
                    errorMsg = "Failed to connect to ESP32: ${e.message}"
                }

                if (success) {
                    onLocalStateReset()
                    KioskSecurity.clearPinnedEsp32Mac(context)
                    delegate.onOnlineStatusChanged(false, null)
                    Log.i(TAG, "[UNPAIR] Slot #$assignedSlot unpaired successfully on ESP32; local terminal state reset.")
                    onResult?.invoke(true, null)
                } else {
                    val finalMsg = errorMsg ?: "Failed to unpair slot #$assignedSlot."
                    Log.w(TAG, "[UNPAIR] Unpair failed. Retaining local configuration. Error: $finalMsg")
                    onResult?.invoke(false, finalMsg)
                }
            } catch (e: Exception) {
                Log.e(TAG, "[UNPAIR] Unexpected error unpairing: ${e.message}", e)
                onResult?.invoke(false, e.message)
            }
        }
    }

    fun fetchMasterConfig(ipHost: String, esp32Port: Int) {
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
        } catch (e: Exception) {}
    }
}
