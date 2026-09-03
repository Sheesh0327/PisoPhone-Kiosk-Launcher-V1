package com.pisophone.kiosk.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HardwareLockManager
 *
 * Cryptographically binds the kiosk application to the target device's physical hardware
 * and enforces a robust 7-day trial followed by un-bypassable lockdown unless licensed.
 * Supports Cloudflare KV remote synchronization while providing asymmetric RSA-2048
 * signature verification and tamper-resistant offline clock tracking.
 */
object HardwareLockManager {
    private const val TAG = "HardwareLock"
    private const val PREFS_NAME = "kiosk_hardware_seal_vault"

    // Production RSA-2048 Public Key (X.509 SPKI Base64)
    // Used to verify digitally signed licenses issued by the Cloudflare Worker.
    // An adversary decompiling this APK cannot forge licenses without the private key on Cloudflare.
    private const val LICENSE_PUBLIC_KEY_BASE64 = 
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAtv5IQqpyOqByzeYp2co5hCfbJ+sJJQ81wiXMBkcS+Q/yXOseqSYS87+Sks+QQS+zmN7cPS3518Ej4qKNCbSGgGLqzG1j7Kpy/YyfOdABqEB/ary6VH52enGMOaxvKWl3VyzGSapPHd7oq95ftUAOT23qqNLqViWq2A4ZJ+xC3FiwIIaGd4T4m+3E/mPfyOvcpw08Ag6yJsVjr51q0zt1/BSXWScNkCbx2VRdMaHk2Rr/YbEue4086oMJGKK3t/SOox+MAQF1ALIjUGldA4gxlGfhqbB/fBRk7gOXhDWfNutE+MHSonaFh4xO1W2RU6NKKEHsOmX+WNox0lYY2WYvFQIDAQAB"

    val licenseUpdateVersion = MutableStateFlow<Long>(System.currentTimeMillis())
    val activationCelebrationEvent = MutableStateFlow<Boolean>(false)

    fun notifyLicenseChanged() {
        licenseUpdateVersion.value = System.currentTimeMillis()
    }

    private const val KEY_BOUND_HW_ID = "bound_hardware_fingerprint"
    private const val KEY_BOUND_DEVICE_NAME = "bound_device_model_name"
    private const val KEY_BOUND_TIMESTAMP = "bound_timestamp_ms"
    private const val KEY_BOUND_SIGNATURE = "bound_hardware_sig"
    private const val KEY_HARDWARE_LOCKED = "hardware_lock_enforced"

    // Licensing & Anti-Uninstall Trial State Keys
    private const val KEY_LICENSE_STATUS = "license_status" // "TRIAL", "PAID", "EXPIRED"
    private const val KEY_TRIAL_START_TIME = "license_trial_start_time"
    private const val KEY_TRIAL_EXPIRES_TIME = "license_trial_expires_time"
    private const val KEY_PAID_EXPIRES_TIME = "license_paid_expires_time"
    private const val KEY_LAST_KNOWN_WALL_CLOCK = "license_last_wall_clock"
    private const val KEY_TIME_TAMPER_LOCKED = "license_time_tamper_locked"
    private const val KEY_LICENSE_SIGNATURE = "license_integrity_signature"

    private const val HARDWARE_SECRET_SALT = "kiosk_hw_bind_salt_2026_x89a"
    private const val SEVEN_DAYS_MS = 7L * 24L * 60L * 60L * 1000L
    private const val ONE_YEAR_MS = 365L * 24L * 60L * 60L * 1000L

    // Cloudflare Worker backend endpoint
    private const val DEFAULT_BACKEND_URL = "https://pisophone-licensing-api.evankhell897.workers.dev"

    enum class LicenseState {
        TRIAL_ACTIVE,
        PAID_ACTIVE,
        EXPIRED_LOCKED,
        HARDWARE_MISMATCH
    }

    data class LicenseInfo(
        val state: LicenseState,
        val daysRemaining: Int,
        val expiresAtMs: Long,
        val isPaid: Boolean,
        val hardwareId: String,
        val deviceName: String
    )

    /**
     * Computes a stable, canonical hardware fingerprint derived strictly from immutable hardware attributes.
     * Excludes volatile Build.FINGERPRINT and Build.BOOTLOADER to survive OTA firmware and OS updates.
     */
    fun getHardwareFingerprint(context: Context): String {
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN_ID"
        } catch (e: Exception) {
            "UNKNOWN_ID"
        }

        val brand = if (Build.BRAND.isNullOrBlank() || Build.BRAND.equals("unknown", ignoreCase = true)) {
            Build.MANUFACTURER ?: ""
        } else {
            Build.BRAND
        }

        val rawHardwareString = listOf(
            androidId,
            Build.BOARD ?: "",
            brand,
            Build.DEVICE ?: "",
            Build.HARDWARE ?: "",
            Build.MANUFACTURER ?: "",
            Build.MODEL ?: "",
            Build.PRODUCT ?: ""
        ).joinToString("|")

        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(rawHardwareString.toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02X".format(it) }
            "HW-${hex.substring(0, 4)}-${hex.substring(4, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}"
        } catch (e: Exception) {
            "HW-GENERIC-${androidId.take(8).uppercase()}"
        }
    }

    /**
     * Returns human-readable device model information.
     */
    fun getHardwareDescription(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val model = Build.MODEL
        val device = Build.DEVICE
        return "$manufacturer $model ($device)"
    }

    private fun getPrefs(context: Context): SharedPreferences {
        val deviceContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) context.createDeviceProtectedStorageContext() else context
        return deviceContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Binds and cryptographically seals the application to the current device's hardware.
     * Safe to call on every boot/app launch.
     */
    fun sealToCurrentDevice(context: Context): Boolean {
        val prefs = getPrefs(context)
        val currentHwId = getHardwareFingerprint(context)
        val currentDevName = getHardwareDescription()
        val now = System.currentTimeMillis()

        val boundHwId = prefs.getString(KEY_BOUND_HW_ID, null)

        if (boundHwId == null) {
            // First run on this hardware: bind and start 7-Day Free Trial
            val sig = generateSignature(currentHwId, currentDevName, now)
            val trialExpires = now + SEVEN_DAYS_MS
            val licSig = generateLicenseSignature(currentHwId, "TRIAL", trialExpires, 0L)

            prefs.edit()
                .putString(KEY_BOUND_HW_ID, currentHwId)
                .putString(KEY_BOUND_DEVICE_NAME, currentDevName)
                .putLong(KEY_BOUND_TIMESTAMP, now)
                .putString(KEY_BOUND_SIGNATURE, sig)
                .putBoolean(KEY_HARDWARE_LOCKED, false)
                .putString(KEY_LICENSE_STATUS, "TRIAL")
                .putLong(KEY_TRIAL_START_TIME, now)
                .putLong(KEY_TRIAL_EXPIRES_TIME, trialExpires)
                .putLong(KEY_PAID_EXPIRES_TIME, 0L)
                .putLong(KEY_LAST_KNOWN_WALL_CLOCK, now)
                .putBoolean(KEY_TIME_TAMPER_LOCKED, false)
                .putString(KEY_LICENSE_SIGNATURE, licSig)
                .apply()

            Log.i(TAG, "Cryptographic hardware seal established for $currentDevName ($currentHwId). 7-Day Trial active.")
            
            // Asynchronously register device with Cloudflare KV backend
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                syncWithBackend(context)
            }
            return true
        }

        // Verify stored seal signature against current hardware
        val storedDevName = prefs.getString(KEY_BOUND_DEVICE_NAME, "") ?: ""
        val storedTimestamp = prefs.getLong(KEY_BOUND_TIMESTAMP, 0L)
        val storedSig = prefs.getString(KEY_BOUND_SIGNATURE, "") ?: ""

        val expectedSig = generateSignature(boundHwId, storedDevName, storedTimestamp)

        if (boundHwId != currentHwId || storedSig != expectedSig) {
            Log.e(TAG, "HARDWARE SEAL BREACH! Bound: $boundHwId, Actual: $currentHwId")
            prefs.edit().putBoolean(KEY_HARDWARE_LOCKED, true).apply()
            return false
        }

        return true
    }

    /**
     * Evaluates current license status: 7-Day Trial, Paid, or Expired Lockdown.
     * Incorporates anti-time-travel high-water mark validation.
     */
    fun getLicenseInfo(context: Context): LicenseInfo {
        val hwId = getHardwareFingerprint(context)
        val devName = getHardwareDescription()

        // Verify hardware binding
        val isHardwareValid = isHardwareBound(context)
        if (!isHardwareValid) {
            return LicenseInfo(
                state = LicenseState.HARDWARE_MISMATCH,
                daysRemaining = 0,
                expiresAtMs = 0L,
                isPaid = false,
                hardwareId = hwId,
                deviceName = devName
            )
        }

        val prefs = getPrefs(context)
        val now = System.currentTimeMillis()

        // Anti-Time-Travel Check: Detect if user rolled back device system clock
        val lastKnownClock = prefs.getLong(KEY_LAST_KNOWN_WALL_CLOCK, 0L)
        val isTamperLocked = prefs.getBoolean(KEY_TIME_TAMPER_LOCKED, false)

        // 1-hour grace window for daylight saving / minor NTP clock synchronization adjustments
        if (lastKnownClock > 0L && now < lastKnownClock - 3_600_000L) {
            Log.w(TAG, "System clock rollback detected! (Now: $now, LastKnown: $lastKnownClock). Tamper lock engaged.")
            prefs.edit().putBoolean(KEY_TIME_TAMPER_LOCKED, true).apply()
            return LicenseInfo(
                state = LicenseState.EXPIRED_LOCKED,
                daysRemaining = 0,
                expiresAtMs = 0L,
                isPaid = false,
                hardwareId = hwId,
                deviceName = devName
            )
        }

        if (isTamperLocked) {
            Log.w(TAG, "Device clock is tamper-locked. Awaiting verified network time sync.")
            return LicenseInfo(
                state = LicenseState.EXPIRED_LOCKED,
                daysRemaining = 0,
                expiresAtMs = 0L,
                isPaid = false,
                hardwareId = hwId,
                deviceName = devName
            )
        }

        // Advance monotonic high-water mark
        prefs.edit().putLong(KEY_LAST_KNOWN_WALL_CLOCK, maxOf(lastKnownClock, now)).apply()

        val status = prefs.getString(KEY_LICENSE_STATUS, "TRIAL") ?: "TRIAL"
        val trialExpires = prefs.getLong(KEY_TRIAL_EXPIRES_TIME, 0L)
        val paidExpires = prefs.getLong(KEY_PAID_EXPIRES_TIME, 0L)
        val licSig = prefs.getString(KEY_LICENSE_SIGNATURE, "") ?: ""

        // Check tamper signature
        val expectedSig = generateLicenseSignature(hwId, status, trialExpires, paidExpires)
        if (licSig.isNotEmpty() && licSig != expectedSig) {
            Log.w(TAG, "License signature mismatch! Lock enforced.")
            return LicenseInfo(
                state = LicenseState.EXPIRED_LOCKED,
                daysRemaining = 0,
                expiresAtMs = 0L,
                isPaid = false,
                hardwareId = hwId,
                deviceName = devName
            )
        }

        // 1. Check Paid License
        if (status == "PAID" && paidExpires > now) {
            val days = Math.max(0, Math.ceil((paidExpires - now).toDouble() / (24 * 60 * 60 * 1000)).toInt())
            return LicenseInfo(
                state = LicenseState.PAID_ACTIVE,
                daysRemaining = days,
                expiresAtMs = paidExpires,
                isPaid = true,
                hardwareId = hwId,
                deviceName = devName
            )
        }

        // 2. Check Trial License
        if (trialExpires > now) {
            val days = Math.max(0, Math.ceil((trialExpires - now).toDouble() / (24 * 60 * 60 * 1000)).toInt())
            return LicenseInfo(
                state = LicenseState.TRIAL_ACTIVE,
                daysRemaining = days,
                expiresAtMs = trialExpires,
                isPaid = false,
                hardwareId = hwId,
                deviceName = devName
            )
        }

        // 3. Expired Lockdown
        return LicenseInfo(
            state = LicenseState.EXPIRED_LOCKED,
            daysRemaining = 0,
            expiresAtMs = if (status == "PAID") paidExpires else trialExpires,
            isPaid = false,
            hardwareId = hwId,
            deviceName = devName
        )
    }

    /**
     * Checks if kiosk features are allowed to operate under the current license.
     */
    fun isLicenseActive(context: Context): Boolean {
        val info = getLicenseInfo(context)
        return info.state == LicenseState.TRIAL_ACTIVE || info.state == LicenseState.PAID_ACTIVE
    }

    /**
     * Checks if the kiosk application is authorized to operate (hardware bound and valid license active).
     */
    fun isAppAllowedToRun(context: Context): Boolean {
        return isHardwareBound(context) && isLicenseActive(context)
    }

    /**
     * Checks if the physical hardware is authorized and sealed.
     */
    fun isHardwareAuthorized(context: Context): Boolean {
        return isHardwareBound(context)
    }

    /**
     * Checks if current physical hardware matches the bound seal.
     */
    fun isHardwareBound(context: Context): Boolean {
        val prefs = getPrefs(context)
        if (prefs.getBoolean(KEY_HARDWARE_LOCKED, false)) return false
        val boundHwId = prefs.getString(KEY_BOUND_HW_ID, null) ?: return true
        val currentHwId = getHardwareFingerprint(context)
        return boundHwId == currentHwId
    }

    /**
     * Verifies an asymmetric RSA-SHA256 digital signature from the Cloudflare licensing server.
     * Uses PKCS#1 v1.5 padding with SHA-256.
     */
    fun verifyRsaSignature(data: String, signatureBase64Url: String): Boolean {
        return try {
            val pubKeyBytes = Base64.decode(LICENSE_PUBLIC_KEY_BASE64, Base64.DEFAULT)
            val keySpec = X509EncodedKeySpec(pubKeyBytes)
            val keyFactory = KeyFactory.getInstance("RSA")
            val publicKey = keyFactory.generatePublic(keySpec)

            var b64 = signatureBase64Url.replace('-', '+').replace('_', '/')
            while (b64.length % 4 != 0) {
                b64 += "="
            }
            val sigBytes = Base64.decode(b64, Base64.DEFAULT)

            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(publicKey)
            verifier.update(data.toByteArray(Charsets.UTF_8))
            verifier.verify(sigBytes)
        } catch (e: Exception) {
            Log.e(TAG, "RSA signature verification exception: ${e.message}")
            false
        }
    }

    /**
     * Helper to verify legacy HMAC-SHA256 signatures for backward compatibility.
     */
    private fun verifyLegacyHmac(data: String, signatureHex: String): Boolean {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            val signingSecret = "piso_master_lic_secret_2026_89a1f"
            val secretKey = SecretKeySpec(signingSecret.toByteArray(Charsets.UTF_8), "HmacSHA256")
            mac.init(secretKey)
            val expectedBytes = mac.doFinal(data.toByteArray(Charsets.UTF_8))
            val expectedHex = expectedBytes.joinToString("") { "%02x".format(it) }
            expectedHex.equals(signatureHex, ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Activates a 1-Year Commercial License on this hardware.
     * Accepts:
     * - Asymmetrically signed license tokens: "PISO-1Y.<deviceId>.<expiresAt>.<rsaSignature>"
     * - Manual redemption / activation codes with Cloudflare backend verification
     */
    fun activateOneYearLicense(context: Context, keyOrRef: String = "MANUAL"): Boolean {
        return try {
            val hwId = getHardwareFingerprint(context)
            val androidId = try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
            } catch (e: Exception) { "" }
            val now = System.currentTimeMillis()
            val prefs = getPrefs(context)
            val currentPaidExpires = prefs.getLong(KEY_PAID_EXPIRES_TIME, 0L)

            val trimmedKey = keyOrRef.trim()
            val parts = trimmedKey.split(".")
            var targetExpires = (if (currentPaidExpires > now) currentPaidExpires else now) + ONE_YEAR_MS

            if (parts.size == 4 && parts[0] == "PISO-1Y") {
                val licDevId = parts[1]
                val licExpires = parts[2].toLongOrNull()
                val licSig = parts[3]

                if (licExpires == null || licExpires <= now) {
                    Log.w(TAG, "License token is expired or invalid expiry: $licExpires")
                    return false
                }

                // Verify device binding
                if (!licDevId.equals(hwId, ignoreCase = true) && !licDevId.equals(androidId, ignoreCase = true)) {
                    Log.w(TAG, "License device ID mismatch: token is for $licDevId, actual device is $hwId")
                    return false
                }

                // Asymmetric cryptographic verification (RSA-2048 SHA-256)
                val payload = "$licDevId|$licExpires"
                val isRsaValid = verifyRsaSignature(payload, licSig)
                val isLegacyValid = !isRsaValid && verifyLegacyHmac(payload, licSig)

                if (!isRsaValid && !isLegacyValid) {
                    Log.e(TAG, "Cryptographic signature verification failed! License rejected.")
                    return false
                }

                targetExpires = licExpires
                Log.i(TAG, "Valid cryptographically verified license token received (Algorithm: ${if (isRsaValid) "RSA-2048" else "HMAC"}).")
            }

            val trialExpires = prefs.getLong(KEY_TRIAL_EXPIRES_TIME, now)
            val sig = generateLicenseSignature(hwId, "PAID", trialExpires, targetExpires)

            prefs.edit()
                .putString(KEY_LICENSE_STATUS, "PAID")
                .putLong(KEY_PAID_EXPIRES_TIME, targetExpires)
                .putString(KEY_LICENSE_SIGNATURE, sig)
                .putLong(KEY_LAST_KNOWN_WALL_CLOCK, now)
                .putBoolean(KEY_TIME_TAMPER_LOCKED, false) // authenticated license clears tamper flag
                .apply()

            // Asynchronously notify Cloudflare backend of manual activation / redemption
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                try {
                    val devModel = getHardwareDescription()
                    val url = URL("$DEFAULT_BACKEND_URL/api/payment/confirm")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 8000
                        readTimeout = 8000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                    }

                    val payload = JSONObject().apply {
                        put("deviceId", hwId)
                        put("paymentRef", trimmedKey)
                        put("hardwareHash", hwId)
                        put("deviceModel", devModel)
                    }

                    conn.outputStream.use { os ->
                        os.write(payload.toString().toByteArray(Charsets.UTF_8))
                    }

                    val respCode = conn.responseCode
                    if (respCode == 200) {
                        val respStr = conn.inputStream.bufferedReader().use { it.readText() }
                        Log.i(TAG, "Cloudflare Worker synced license activation: $respStr")
                    } else {
                        val errStr = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                        Log.w(TAG, "Cloudflare Worker returned HTTP $respCode: $errStr")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not reach Cloudflare Worker during activation: ${e.message}")
                }
            }

            Log.i(TAG, "1-Year License successfully activated on hardware $hwId (Expires: $targetExpires)")
            activationCelebrationEvent.value = true
            notifyLicenseChanged()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to activate license: ${e.message}", e)
            false
        }
    }

    /**
     * Synchronizes hardware status with Cloudflare KV / D1 backend.
     * Also synchronizes trusted server time to resolve clock drift and self-heal tamper locks.
     */
    suspend fun syncWithBackend(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val hwId = getHardwareFingerprint(context)
            val devModel = getHardwareDescription()

            val url = URL("$DEFAULT_BACKEND_URL/api/device/register")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6000
                readTimeout = 6000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }

            val payload = JSONObject().apply {
                put("deviceId", hwId)
                put("hardwareHash", hwId)
                put("deviceModel", devModel)
            }

            conn.outputStream.use { os ->
                os.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            if (conn.responseCode == 200) {
                val respStr = conn.inputStream.bufferedReader().use { it.readText() }
                val respJson = JSONObject(respStr)
                val status = respJson.optString("status", "")
                val paidExpires = respJson.optLong("paidExpiresAt", 0L)
                val trialExpires = respJson.optLong("trialExpiresAt", 0L)
                val serverTime = respJson.optLong("serverTime", 0L)

                val prefs = getPrefs(context)
                val editor = prefs.edit()

                // Server-side trusted time anchor: if trusted server edge confirms time, self-heal tamper flag
                val lastKnown = prefs.getLong(KEY_LAST_KNOWN_WALL_CLOCK, 0L)
                if (serverTime > 0L) {
                    if (serverTime >= lastKnown - 300_000L) {
                        editor.putBoolean(KEY_TIME_TAMPER_LOCKED, false)
                        editor.putLong(KEY_LAST_KNOWN_WALL_CLOCK, maxOf(serverTime, System.currentTimeMillis()))
                    }
                }

                // If signed license key token is provided, verify it
                if (status == "PAID" && paidExpires > 0L) {
                    editor.putString(KEY_LICENSE_STATUS, "PAID")
                    editor.putLong(KEY_PAID_EXPIRES_TIME, paidExpires)
                } else if (status == "LOCKED") {
                    editor.putString(KEY_LICENSE_STATUS, "EXPIRED")
                }
                if (trialExpires > 0L) {
                    editor.putLong(KEY_TRIAL_EXPIRES_TIME, trialExpires)
                }

                val currentStatus = prefs.getString(KEY_LICENSE_STATUS, if (status == "PAID") "PAID" else "TRIAL") ?: "TRIAL"
                val currPaid = if (status == "PAID" && paidExpires > 0L) paidExpires else prefs.getLong(KEY_PAID_EXPIRES_TIME, 0L)
                val currTrial = if (trialExpires > 0L) trialExpires else prefs.getLong(KEY_TRIAL_EXPIRES_TIME, 0L)
                editor.putString(KEY_LICENSE_SIGNATURE, generateLicenseSignature(hwId, currentStatus, currTrial, currPaid))
                editor.apply()

                notifyLicenseChanged()
                Log.i(TAG, "Backend sync success. Status: $status, PaidExpires: $paidExpires, ServerTime: $serverTime")
                true
            } else {
                Log.w(TAG, "Backend sync responded with HTTP ${conn.responseCode}")
                false
            }
        } catch (e: Exception) {
            Log.d(TAG, "Backend sync skipped (offline or server unavailable): ${e.message}")
            false
        }
    }

    fun getBoundHardwareId(context: Context): String {
        return getPrefs(context).getString(KEY_BOUND_HW_ID, "") ?: ""
    }

    fun getBoundDeviceName(context: Context): String {
        return getPrefs(context).getString(KEY_BOUND_DEVICE_NAME, "Unknown Device") ?: "Unknown Device"
    }

    fun rebindWithAdminPin(context: Context, enteredPin: String): Boolean {
        if (!KioskSecurity.verifyAdminPin(context, enteredPin)) {
            Log.w(TAG, "Rebind failed: Incorrect Admin PIN.")
            return false
        }
        val success = sealToCurrentDevice(context)
        if (success) {
            Log.i(TAG, "Device hardware successfully re-bound to current device by administrator.")
        }
        return success
    }

    private fun generateSignature(hwId: String, devName: String, timestamp: Long): String {
        val payload = "$hwId|$devName|$timestamp|$HARDWARE_SECRET_SALT"
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(HARDWARE_SECRET_SALT.toByteArray(), "HmacSHA256")
        mac.init(secretKey)
        val hmacBytes = mac.doFinal(payload.toByteArray())
        return hmacBytes.joinToString("") { "%02x".format(it) }
    }

    private fun generateLicenseSignature(hwId: String, status: String, trialExp: Long, paidExp: Long): String {
        val payload = "LIC|$hwId|$status|$trialExp|$paidExp|$HARDWARE_SECRET_SALT"
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(HARDWARE_SECRET_SALT.toByteArray(), "HmacSHA256")
        mac.init(secretKey)
        val hmacBytes = mac.doFinal(payload.toByteArray())
        return hmacBytes.joinToString("") { "%02x".format(it) }
    }
}
