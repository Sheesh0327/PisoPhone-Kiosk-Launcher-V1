package com.pisophone.kiosk.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HardwareLockManager
 *
 * Cryptographically binds the kiosk application to the target device's physical hardware
 * and enforces a robust 7-day trial followed by un-bypassable lockdown unless licensed.
 * Supports Cloudflare KV/D1 remote synchronization while providing secure local tamper protection.
 */
object HardwareLockManager {
    private const val TAG = "HardwareLock"
    private const val PREFS_NAME = "kiosk_hardware_seal_vault"

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
     * Computes a unique hardware fingerprint derived from immutable hardware attributes.
     */
    fun getHardwareFingerprint(context: Context): String {
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN_ID"
        } catch (e: Exception) {
            "UNKNOWN_ID"
        }

        val rawHardwareString = listOf(
            androidId,
            Build.BOARD,
            Build.BOOTLOADER,
            Build.BRAND,
            Build.DEVICE,
            Build.HARDWARE,
            Build.MANUFACTURER,
            Build.MODEL,
            Build.PRODUCT,
            Build.FINGERPRINT
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
     */
    fun sealToCurrentDevice(context: Context): Boolean {
        return try {
            val hwId = getHardwareFingerprint(context)
            val devName = getHardwareDescription()
            val now = System.currentTimeMillis()
            val signature = generateSignature(hwId, devName, now)

            val prefs = getPrefs(context)
            val editor = prefs.edit()
                .putString(KEY_BOUND_HW_ID, hwId)
                .putString(KEY_BOUND_DEVICE_NAME, devName)
                .putLong(KEY_BOUND_TIMESTAMP, now)
                .putString(KEY_BOUND_SIGNATURE, signature)
                .putBoolean(KEY_HARDWARE_LOCKED, true)

            // Initialize trial if not present
            if (!prefs.contains(KEY_TRIAL_START_TIME)) {
                val trialExpires = now + SEVEN_DAYS_MS
                val licSig = generateLicenseSignature(hwId, "TRIAL", trialExpires, 0L)
                editor.putString(KEY_LICENSE_STATUS, "TRIAL")
                    .putLong(KEY_TRIAL_START_TIME, now)
                    .putLong(KEY_TRIAL_EXPIRES_TIME, trialExpires)
                    .putLong(KEY_PAID_EXPIRES_TIME, 0L)
                    .putLong(KEY_LAST_KNOWN_WALL_CLOCK, now)
                    .putString(KEY_LICENSE_SIGNATURE, licSig)
            }

            editor.apply()
            Log.i(TAG, "Hardware Seal successfully created and bound to: $devName ($hwId)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to seal hardware to current device: ${e.message}", e)
            false
        }
    }

    /**
     * Checks whether the current physical hardware is genuine and authorized.
     */
    fun isHardwareAuthorized(context: Context): Boolean {
        val prefs = getPrefs(context)
        val isLocked = prefs.getBoolean(KEY_HARDWARE_LOCKED, false)

        // First-run on initial installation: automatically bind to this physical device
        if (!isLocked || !prefs.contains(KEY_BOUND_HW_ID)) {
            Log.i(TAG, "First installation detected. Initializing hardware seal on this device...")
            sealToCurrentDevice(context)
            return true
        }

        val boundHwId = prefs.getString(KEY_BOUND_HW_ID, "") ?: ""
        val boundDevName = prefs.getString(KEY_BOUND_DEVICE_NAME, "") ?: ""
        val boundTime = prefs.getLong(KEY_BOUND_TIMESTAMP, 0L)
        val boundSig = prefs.getString(KEY_BOUND_SIGNATURE, "") ?: ""

        val currentHwId = getHardwareFingerprint(context)

        // Check Hardware Fingerprint Match
        if (boundHwId.isEmpty() || boundHwId != currentHwId) {
            Log.w(TAG, "Hardware mismatch! Bound: $boundHwId, Current: $currentHwId")
            return false
        }

        // Verify Cryptographic Signature
        val expectedSig = generateSignature(boundHwId, boundDevName, boundTime)
        if (boundSig.isEmpty() || boundSig != expectedSig) {
            Log.w(TAG, "Hardware signature verification failed! Tampering detected.")
            return false
        }

        return true
    }

    /**
     * Returns full license and trial status for this hardware.
     */
    fun getLicenseInfo(context: Context): LicenseInfo {
        val hwId = getHardwareFingerprint(context)
        val devName = getHardwareDescription()

        if (!isHardwareAuthorized(context)) {
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

        // Anti-Time-Travel check: detect if user rolled back device clock
        val lastKnownClock = prefs.getLong(KEY_LAST_KNOWN_WALL_CLOCK, 0L)
        if (now < lastKnownClock - 60_000L) {
            Log.w(TAG, "Clock rollback detected! Lock enforced.")
            return LicenseInfo(
                state = LicenseState.EXPIRED_LOCKED,
                daysRemaining = 0,
                expiresAtMs = 0L,
                isPaid = false,
                hardwareId = hwId,
                deviceName = devName
            )
        }
        // Update high-water mark of wall clock
        prefs.edit().putLong(KEY_LAST_KNOWN_WALL_CLOCK, now).apply()

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

        // 3. Expired & Locked
        return LicenseInfo(
            state = LicenseState.EXPIRED_LOCKED,
            daysRemaining = 0,
            expiresAtMs = trialExpires,
            isPaid = false,
            hardwareId = hwId,
            deviceName = devName
        )
    }

    /**
     * Checks if the app is currently allowed to run kiosk features (either in valid Trial or Paid).
     */
    fun isAppAllowedToRun(context: Context): Boolean {
        val info = getLicenseInfo(context)
        return info.state == LicenseState.TRIAL_ACTIVE || info.state == LicenseState.PAID_ACTIVE
    }

    /**
     * Activates a 1-Year Commercial License on this hardware and syncs with Cloudflare KV.
     */
    fun activateOneYearLicense(context: Context, keyOrRef: String = "MANUAL"): Boolean {
        return try {
            val hwId = getHardwareFingerprint(context)
            val now = System.currentTimeMillis()
            val prefs = getPrefs(context)
            val currentPaidExpires = prefs.getLong(KEY_PAID_EXPIRES_TIME, 0L)
            val newExpires = (if (currentPaidExpires > now) currentPaidExpires else now) + ONE_YEAR_MS
            val trialExpires = prefs.getLong(KEY_TRIAL_EXPIRES_TIME, now)

            val sig = generateLicenseSignature(hwId, "PAID", trialExpires, newExpires)

            prefs.edit()
                .putString(KEY_LICENSE_STATUS, "PAID")
                .putLong(KEY_PAID_EXPIRES_TIME, newExpires)
                .putString(KEY_LICENSE_SIGNATURE, sig)
                .putLong(KEY_LAST_KNOWN_WALL_CLOCK, now)
                .apply()

            // Asynchronously notify & redeem code on Cloudflare backend
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                try {
                    val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
                    val devModel = getHardwareDescription()
                    val url = URL("$DEFAULT_BACKEND_URL/api/license/issue")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 8000
                        readTimeout = 8000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                    }

                    val payload = JSONObject().apply {
                        put("deviceId", androidId.ifEmpty { hwId })
                        put("activationCode", keyOrRef.trim())
                        put("hardwareHash", hwId)
                        put("deviceModel", devModel)
                    }

                    conn.outputStream.use { os ->
                        os.write(payload.toString().toByteArray(Charsets.UTF_8))
                    }

                    val respCode = conn.responseCode
                    if (respCode == 200) {
                        val respStr = conn.inputStream.bufferedReader().use { it.readText() }
                        Log.i(TAG, "Cloudflare Worker validated & redeemed code in KV successfully: $respStr")
                    } else {
                        val errStr = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                        Log.w(TAG, "Cloudflare Worker returned HTTP $respCode: $errStr")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not reach Cloudflare Worker during activation: ${e.message}")
                }
            }

            Log.i(TAG, "1-Year License successfully activated on hardware $hwId (Expires: $newExpires)")
            activationCelebrationEvent.value = true
            licenseUpdateVersion.value = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to activate license: ${e.message}", e)
            false
        }
    }

    /**
     * Synchronizes hardware status with Cloudflare KV / D1 backend.
     */
    suspend fun syncWithBackend(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
            val hwId = getHardwareFingerprint(context)
            val devModel = getHardwareDescription()

            val url = URL("$DEFAULT_BACKEND_URL/api/device/register")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
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

                val prefs = getPrefs(context)
                val editor = prefs.edit()
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
                Log.i(TAG, "Backend sync success. Status: $status, Paid: $paidExpires")
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
