package com.pisophone.kiosk.provisioning

import android.content.Context
import android.util.Log
import com.pisophone.kiosk.security.HardwareLockManager
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

sealed class PairingResult {
    data class Success(val slot: Int, val mac: String) : PairingResult()
    data class OccupiedSlot(val slot: Int, val message: String) : PairingResult()
    data class AuthFailed(val message: String) : PairingResult()
    data class ConfigurationError(val message: String) : PairingResult()
    data class NetworkError(val message: String) : PairingResult()
}

/**
 * PairingCoordinator
 *
 * Single, unified engine for configuring and pairing the PisoPhone kiosk terminal
 * with the ESP32 controller over WebUSB, intent, or manual administrator setup.
 */
object PairingCoordinator {
    private const val TAG = "PairingCoordinator"

    fun configureAndPair(
        context: Context,
        secret: String,
        mac: String,
        ip: String,
        slot: Int,
        name: String?,
        onComplete: (PairingResult) -> Unit
    ) {
        val trimmedSecret = secret.trim()
        val trimmedIp = ip.trim()
        val trimmedMac = mac.trim()
        val cleanName = name?.trim() ?: "PisoPhone $slot"

        // 1. Strict validation of required parameters
        if (trimmedSecret.isEmpty()) {
            onComplete(PairingResult.ConfigurationError("Shared secret is missing or blank."))
            return
        }
        if (slot !in 1..12) {
            onComplete(PairingResult.ConfigurationError("Invalid slot number: $slot (must be 1-12)."))
            return
        }
        if (trimmedIp.isEmpty() || trimmedIp == "0.0.0.0" || trimmedIp == "127.0.0.1") {
            onComplete(PairingResult.ConfigurationError("Invalid or missing controller IP: $trimmedIp"))
            return
        }
        if (trimmedMac.isNotEmpty() && trimmedMac.length < 12) {
            onComplete(PairingResult.ConfigurationError("Invalid controller MAC address: $trimmedMac"))
            return
        }

        // 2. Persist configuration to Direct Boot secure storage
        val persisted = KioskSecurity.applyDirectProvisioning(
            context = context,
            secret = trimmedSecret,
            mac = trimmedMac,
            ip = trimmedIp,
            slot = slot,
            name = cleanName
        )
        if (!persisted) {
            onComplete(PairingResult.ConfigurationError("Failed to commit configuration to secure storage."))
            return
        }

        // 3. Perform network pairing handshake with ESP32 controller
        CoroutineScope(Dispatchers.IO).launch {
            val result = attemptPairing(
                context = context,
                targetIp = trimmedIp,
                slot = slot,
                expectedMac = trimmedMac,
                name = cleanName,
                secret = trimmedSecret
            )
            withContext(Dispatchers.Main) {
                if (result is PairingResult.Success) {
                    if (trimmedMac.isBlank() && result.mac.isNotBlank()) {
                        KioskSecurity.applyDirectProvisioning(
                            context = context,
                            secret = trimmedSecret,
                            mac = result.mac,
                            ip = trimmedIp,
                            slot = slot,
                            name = cleanName
                        )
                    }
                    HardwareLockManager.sealToCurrentDevice(context)
                }
                onComplete(result)
            }
        }
    }

    suspend fun attemptPairing(
        context: Context,
        targetIp: String,
        slot: Int,
        expectedMac: String,
        name: String,
        secret: String
    ): PairingResult = withContext(Dispatchers.IO) {
        val deviceId = HardwareLockManager.getCanonicalDeviceId(context)
        val localIp = NetworkUtils.getLocalIpAddress()

        // Candidate IPs for changed-IP discovery safeguard
        val candidateIps = mutableListOf<String>()
        candidateIps.add(targetIp)
        if (!candidateIps.contains("kioskmanager.local")) {
            candidateIps.add("kioskmanager.local")
        }
        val gatewayCandidate = deriveGatewayCandidate(localIp)
        if (gatewayCandidate != null && !candidateIps.contains(gatewayCandidate)) {
            candidateIps.add(gatewayCandidate)
        }

        var lastResult: PairingResult = PairingResult.NetworkError("Could not connect to controller at $targetIp")

        for (candidateIp in candidateIps) {
            Log.i(TAG, "Pairing attempt with ESP32 candidate: $candidateIp (Slot $slot, LocalIP $localIp)")
            val result = performPairingRequest(
                context = context,
                targetIp = candidateIp,
                slot = slot,
                deviceId = deviceId,
                localIp = localIp,
                name = name,
                secret = secret,
                expectedMac = expectedMac
            )
            if (result is PairingResult.Success) {
                if (candidateIp != targetIp) {
                    Log.i(TAG, "[+] ESP32 IP updated from $targetIp to $candidateIp. Updating configuration.")
                    KioskSecurity.setConfiguredEsp32Ip(context, candidateIp)
                }
                return@withContext result
            }
            lastResult = result
            if (result is PairingResult.OccupiedSlot || result is PairingResult.AuthFailed) {
                // Non-transient errors: do not loop further
                return@withContext result
            }
        }

        return@withContext lastResult
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
    ): PairingResult {
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

            return when (responseCode) {
                200 -> {
                    val json = JSONObject(responseBody)
                    val success = json.optBoolean("success", false)
                    val respSlot = json.optInt("slot", -1)
                    val respMac = json.optString("mac", "")
                    val respSig = json.optString("sig", "")

                    if (!success) {
                        PairingResult.ConfigurationError(json.optString("error", "Pairing rejected by controller."))
                    } else if (respSlot != slot) {
                        PairingResult.ConfigurationError("Slot mismatch: expected $slot, got $respSlot")
                    } else {
                        val cleanExpected = expectedMac.replace(":", "").replace("-", "")
                        val cleanResp = respMac.replace(":", "").replace("-", "")
                        if (cleanExpected.isNotBlank() && cleanResp.isNotBlank() && !cleanExpected.equals(cleanResp, ignoreCase = true)) {
                            PairingResult.ConfigurationError("Controller MAC mismatch: expected $expectedMac, got $respMac")
                        } else {
                            // Verify response signature: HMAC("success:$deviceId:$slot:$respMac:$ts", secret)
                            val expectedRespSig = KioskSecurity.calculateHmac("success:$deviceId:$slot:$respMac:$ts", secret)
                            if (!expectedRespSig.equals(respSig, ignoreCase = true)) {
                                PairingResult.AuthFailed("Controller response signature invalid! Possible unauthorized device.")
                            } else {
                                Log.i(TAG, "[+] Successfully paired and authenticated with ESP32 for slot $slot.")
                                PairingResult.Success(slot, respMac)
                            }
                        }
                    }
                }
                409 -> {
                    val msg = try { JSONObject(responseBody).optString("error", "Slot $slot is already occupied.") } catch (_: Exception) { "Slot $slot is already occupied." }
                    PairingResult.OccupiedSlot(slot, msg)
                }
                401, 403 -> {
                    val msg = try { JSONObject(responseBody).optString("error", "Authentication failed.") } catch (_: Exception) { "Authentication failed." }
                    PairingResult.AuthFailed(msg)
                }
                else -> {
                    PairingResult.NetworkError("HTTP $responseCode from $targetIp: $responseBody")
                }
            }
        } catch (e: Exception) {
            return PairingResult.NetworkError("Connection to $targetIp failed: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    private fun deriveGatewayCandidate(localIp: String): String? {
        if (localIp.isBlank() || localIp == "127.0.0.1") return null
        val parts = localIp.split(".")
        if (parts.size == 4) {
            return "${parts[0]}.${parts[1]}.${parts[2]}.1"
        }
        return null
    }
}

typealias ProvisioningCoordinator = PairingCoordinator
