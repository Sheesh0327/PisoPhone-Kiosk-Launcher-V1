package com.pisophone.kiosk.provisioning

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import com.pisophone.kiosk.security.HardwareLockManager
import com.pisophone.kiosk.security.KioskSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.net.URLEncoder

object ProvisioningCoordinator {
    private const val TAG = "Provisioning"
    const val PREFS_NAME = "provisioning_state"
    const val KEY_PAIRING_PENDING = "pairing_pending"
    const val KEY_PAIRING_COMPLETED = "pairing_completed"
    const val KEY_SLOT = "setup_slot"
    const val KEY_MAC = "setup_mac"
    const val KEY_IP = "setup_ip"
    const val KEY_NAME = "setup_name"

    private fun getPrefs(context: Context): SharedPreferences {
        return KioskSecurity.getDirectBootPrefs(context, PREFS_NAME)
    }

    fun extractAndSaveAdminExtras(context: Context, intent: Intent): Boolean {
        val extras: PersistableBundle? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, PersistableBundle::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)
        }

        if (extras == null) {
            Log.e(TAG, "No admin extras found in provisioning intent.")
            return false
        }

        val schemaVersion = extras.getInt("setup_schema_version", -1)
        Log.i(TAG, "Found admin extras, schema: $schemaVersion")

        if (schemaVersion != 1) {
            Log.e(TAG, "Unsupported schema version: $schemaVersion (expected 1)")
            return false
        }

        val secret = extras.getString("setup_secret")?.trim()
        val mac = extras.getString("setup_mac")?.trim()
        val ip = extras.getString("setup_ip")?.trim()
        val slot = extras.getInt("setup_slot", -1)
        val name = extras.getString("setup_name")?.trim()

        if (secret.isNullOrBlank()) {
            Log.e(TAG, "Missing or blank setup_secret in admin extras.")
            return false
        }
        if (mac.isNullOrBlank() || mac.length < 12) {
            Log.e(TAG, "Missing or invalid setup_mac in admin extras: $mac")
            return false
        }
        if (ip.isNullOrBlank() || ip == "0.0.0.0" || ip == "127.0.0.1") {
            Log.e(TAG, "Missing or invalid setup_ip in admin extras: $ip")
            return false
        }
        if (slot <= 0) {
            Log.e(TAG, "Missing or invalid setup_slot in admin extras: $slot")
            return false
        }

        val prefs = getPrefs(context)
        val alreadyCompleted = prefs.getBoolean(KEY_PAIRING_COMPLETED, false)
        val savedSlot = prefs.getInt(KEY_SLOT, -1)
        val savedMac = prefs.getString(KEY_MAC, "")
        val savedIp = prefs.getString(KEY_IP, "")

        // Avoid repeated callbacks resetting pairing to pending if already paired with matching config
        if (alreadyCompleted && savedSlot == slot && savedMac.equals(mac, ignoreCase = true) && savedIp == ip) {
            Log.i(TAG, "Provisioning already completed for slot $slot (MAC: $mac). Preserving paired status.")
            return true
        }

        KioskSecurity.applyDirectProvisioning(
            context = context,
            secret = secret,
            mac = mac,
            ip = ip,
            slot = slot,
            name = name
        )

        prefs.edit()
            .putBoolean(KEY_PAIRING_PENDING, true)
            .putBoolean(KEY_PAIRING_COMPLETED, false)
            .putInt(KEY_SLOT, slot)
            .putString(KEY_MAC, mac)
            .putString(KEY_IP, ip)
            .putString(KEY_NAME, name ?: "PisoPhone $slot")
            .apply()

        Log.i(TAG, "Saved provisioning configuration for slot $slot ($mac @ $ip). Pairing pending.")
        return true
    }

    fun isPairingPending(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PAIRING_PENDING, false)
    }

    fun markPairingComplete(context: Context) {
        getPrefs(context).edit()
            .putBoolean(KEY_PAIRING_PENDING, false)
            .putBoolean(KEY_PAIRING_COMPLETED, true)
            .apply()
        Log.i(TAG, "Marked pairing as completed in device-protected storage.")
    }

    fun initiatePairingAsync(context: Context, onComplete: (Boolean) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val success = attemptPairing(context)
            withContext(Dispatchers.Main) {
                if (success) {
                    markPairingComplete(context)
                }
                onComplete(success)
            }
        }
    }

    private fun attemptPairing(context: Context): Boolean {
        val prefs = getPrefs(context)
        val slot = prefs.getInt(KEY_SLOT, -1)
        val configuredIp = prefs.getString(KEY_IP, null)?.trim()
        val expectedMac = prefs.getString(KEY_MAC, null)?.trim()
        val name = prefs.getString(KEY_NAME, "PisoPhone $slot") ?: "PisoPhone $slot"

        if (slot < 1 || configuredIp.isNullOrBlank() || expectedMac.isNullOrBlank()) {
            Log.e(TAG, "Pairing aborted: missing slot ($slot), IP ($configuredIp), or MAC ($expectedMac)")
            return false
        }

        val secret = KioskSecurity.getSharedSecret(context)
        if (secret.isNullOrEmpty()) {
            Log.e(TAG, "Pairing aborted: shared secret is not available.")
            return false
        }

        val deviceId = HardwareLockManager.getHardwareFingerprint(context)
        val localIp = getLocalIpAddress()

        // Candidate IPs for changed-IP discovery safeguard
        val candidateIps = mutableListOf<String>()
        candidateIps.add(configuredIp)
        if (!candidateIps.contains("kioskmanager.local")) {
            candidateIps.add("kioskmanager.local")
        }
        val gatewayCandidate = deriveGatewayCandidate(localIp)
        if (gatewayCandidate != null && !candidateIps.contains(gatewayCandidate)) {
            candidateIps.add(gatewayCandidate)
        }

        for (candidateIp in candidateIps) {
            Log.i(TAG, "Attempting pairing with ESP32 candidate: $candidateIp (Slot $slot, LocalIP $localIp)")
            val success = performPairingRequest(
                context = context,
                targetIp = candidateIp,
                slot = slot,
                deviceId = deviceId,
                localIp = localIp,
                name = name,
                secret = secret,
                expectedMac = expectedMac
            )
            if (success) {
                if (candidateIp != configuredIp) {
                    Log.i(TAG, "[+] ESP32 IP changed from $configuredIp to $candidateIp. Updating configuration.")
                    prefs.edit().putString(KEY_IP, candidateIp).apply()
                    KioskSecurity.setConfiguredEsp32Ip(context, candidateIp)
                }
                return true
            }
        }

        Log.e(TAG, "Pairing failed across all candidate IPs: $candidateIps")
        return false
    }

    private fun performPairingRequest(
        context: Context,
        targetIp: String,
        slot: Int,
        deviceId: String,
        localIp: String,
        name: String,
        secret: String,
        expectedMac: String
    ): Boolean {
        var conn: HttpURLConnection? = null
        try {
            val ts = System.currentTimeMillis()
            val challenge = "$deviceId:$ts:$slot:$localIp"
            val sig = KioskSecurity.calculateHmac(challenge, secret)
            val encodedName = URLEncoder.encode(name, "UTF-8")

            val url = URL("http://$targetIp/api/slots/pair")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }

            val postData = "device_id=$deviceId&ts=$ts&sig=$sig&slot=$slot&id=$deviceId&ip=$localIp&name=$encodedName"
            conn.outputStream.use { os ->
                os.write(postData.toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val responseCode = conn.responseCode
            val responseBody = if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            if (responseCode == 200) {
                val json = JSONObject(responseBody)
                val success = json.optBoolean("success", false)
                val respSlot = json.optInt("slot", -1)
                val respMac = json.optString("mac", "")
                val respSig = json.optString("sig", "")

                if (!success) {
                    Log.e(TAG, "Pairing rejected by ESP32: ${json.optString("error")}")
                    return false
                }

                if (respSlot != slot) {
                    Log.e(TAG, "Slot mismatch: expected $slot, got $respSlot")
                    return false
                }

                val cleanExpected = expectedMac.replace(":", "").replace("-", "")
                val cleanResp = respMac.replace(":", "").replace("-", "")
                if (!cleanExpected.equals(cleanResp, ignoreCase = true)) {
                    Log.e(TAG, "ESP32 MAC mismatch: expected $expectedMac, got $respMac")
                    return false
                }

                // Verify cryptographic response signature: HMAC("success:$deviceId:$slot:$respMac", secret)
                val expectedRespSig = KioskSecurity.calculateHmac("success:$deviceId:$slot:$respMac", secret)
                if (!expectedRespSig.equals(respSig, ignoreCase = true)) {
                    Log.e(TAG, "ESP32 response signature invalid! Possible MITM attack.")
                    return false
                }

                Log.i(TAG, "[+] Successfully paired and authenticated with ESP32 at $targetIp for slot $slot.")
                return true
            } else if (responseCode == 409) {
                Log.e(TAG, "Slot $slot is already occupied on ESP32 at $targetIp: $responseBody")
                return false
            } else {
                Log.w(TAG, "Pairing attempt at $targetIp returned HTTP $responseCode: $responseBody")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Pairing attempt to $targetIp failed: ${e.message}")
        } finally {
            conn?.disconnect()
        }
        return false
    }

    private fun deriveGatewayCandidate(localIp: String): String? {
        if (localIp.isBlank() || localIp == "127.0.0.1") return null
        val parts = localIp.split(".")
        if (parts.size == 4) {
            return "${parts[0]}.${parts[1]}.${parts[2]}.1"
        }
        return null
    }

    private fun getLocalIpAddress(): String {
        var fallbackIp: String? = null
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val ip = address.hostAddress
                        if (ip != null) {
                            if (networkInterface.name.contains("wlan") || networkInterface.name.contains("eth")) {
                                return ip
                            }
                            if (fallbackIp == null) {
                                fallbackIp = ip
                            }
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to determine local IP: ${ex.message}")
        }
        return fallbackIp ?: "127.0.0.1"
    }
}
