package com.pisophone.kiosk.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.MessageDigest

/**
 * KioskActivationManager
 *
 * Manages device fingerprinting/IDs and tracks slot activation/expiration/warnings
 * received from the connected ESP32 coin slot controller.
 */
object KioskActivationManager {
    private const val TAG = "KioskActivation"
    private const val PREFS_NAME = "kiosk_activation_vault"

    val activationUpdateVersion = MutableStateFlow<Long>(System.currentTimeMillis())

    fun notifyActivationChanged() {
        activationUpdateVersion.value = System.currentTimeMillis()
    }

    private const val KEY_BOUND_HW_ID = "bound_hardware_fingerprint"
    private const val KEY_BOUND_DEVICE_NAME = "bound_device_model_name"
    private const val KEY_BOUND_TIMESTAMP = "bound_timestamp_ms"
    private const val KEY_BOUND_SIGNATURE = "bound_hardware_sig"
    private const val KEY_SLOT_EXPIRED = "slot_expired_lockdown"
    private const val KEY_SLOT_EXPIRED_REASON = "slot_expired_reason"
    private const val KEY_SLOT_NUM = "slot_number"
    private const val KEY_SLOT_EXPIRY_TS = "slot_expiry_timestamp"
    private const val KEY_PAIRING_COMPLETED = "pairing_completed"
    private const val KEY_SETUP_WINDOW_START = "setup_window_start_ts"
    private const val SETUP_WINDOW_DURATION_MS = 30 * 60 * 1000L

    /**
     * Computes a stable canonical hardware fingerprint for unique Device ID display.
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

        val computed = try {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(rawHardwareString.toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02X".format(it) }
            "HW-${hex.substring(0, 4)}-${hex.substring(4, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}"
        } catch (e: Exception) {
            "HW-GENERIC-${androidId.take(8).uppercase()}"
        }

        syncToGlobalSettings(context, computed)
        return computed
    }

    private fun syncToGlobalSettings(context: Context, hwId: String) {
        try {
            val current = Settings.Global.getString(context.contentResolver, "pisophone_hw_id")
            if (current != hwId) {
                Settings.Global.putString(context.contentResolver, "pisophone_hw_id", hwId)
            }
        } catch (e: Exception) {
            // Ignored if permissions not yet granted
        }
    }

    fun getHardwareDescription(): String {
        val mfg = (Build.MANUFACTURER ?: "").trim()
        val model = (Build.MODEL ?: "").trim()

        return if (model.isNotEmpty() && mfg.isNotEmpty() && model.startsWith(mfg, ignoreCase = true)) {
            model
        } else if (mfg.isNotEmpty() && model.isNotEmpty()) {
            val prettyMfg = mfg.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            "$prettyMfg $model"
        } else {
            model.ifEmpty { mfg.ifEmpty { "Android Device" } }
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return KioskSecurity.getDirectBootPrefs(context, PREFS_NAME)
    }

    fun sealToCurrentDevice(context: Context): Boolean {
        val prefs = getPrefs(context)
        val currentHwId = getHardwareFingerprint(context)
        val currentDevName = getHardwareDescription()
        val now = System.currentTimeMillis()

        val sig = generateSignature(context, currentHwId, currentDevName, now)

        prefs.edit()
            .putString(KEY_BOUND_HW_ID, currentHwId)
            .putString(KEY_BOUND_DEVICE_NAME, currentDevName)
            .putLong(KEY_BOUND_TIMESTAMP, now)
            .putString(KEY_BOUND_SIGNATURE, sig)
            .apply()

        notifyActivationChanged()
        Log.i(TAG, "Device activation parameters recorded for $currentDevName ($currentHwId).")
        return true
    }

    fun isPairingCompleted(context: Context): Boolean {
        val prefs = getPrefs(context)
        if (prefs.getBoolean(KEY_PAIRING_COMPLETED, false)) return true
        if (KioskSecurity.isProvisioned(context)) {
            setPairingCompleted(context, true)
            return true
        }
        return false
    }

    fun setPairingCompleted(context: Context, completed: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_PAIRING_COMPLETED, completed).apply()
        notifyActivationChanged()
    }

    fun startSetupWindow(context: Context) {
        val prefs = getPrefs(context)
        if (prefs.getLong(KEY_SETUP_WINDOW_START, 0L) == 0L) {
            prefs.edit().putLong(KEY_SETUP_WINDOW_START, System.currentTimeMillis()).apply()
            Log.i(TAG, "Explicit pairing/setup window initialized.")
        }
    }

    fun isSetupModeActive(context: Context): Boolean {
        val prefs = getPrefs(context)
        val startTs = prefs.getLong(KEY_SETUP_WINDOW_START, 0L)
        if (startTs <= 0L) return false
        val elapsed = System.currentTimeMillis() - startTs
        if (elapsed < 0L || elapsed >= SETUP_WINDOW_DURATION_MS) {
            return false
        }
        return true
    }

    fun isAppAllowedToRun(context: Context): Boolean {
        return true
    }

    fun isHardwareAuthorized(context: Context): Boolean {
        return true
    }

    fun isSlotLockedDown(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.getBoolean(KEY_SLOT_EXPIRED, false)
    }

    fun getSlotLockdownDetails(context: Context): Triple<String, Int, Long> {
        val prefs = getPrefs(context)
        val reason = prefs.getString(KEY_SLOT_EXPIRED_REASON, "Device activation required") ?: "Device activation required"
        val slotNum = prefs.getInt(KEY_SLOT_NUM, 0)
        val expiryTs = prefs.getLong(KEY_SLOT_EXPIRY_TS, 0L)
        return Triple(reason, slotNum, expiryTs)
    }

    fun setSlotLockdown(context: Context, locked: Boolean, reason: String = "", slotNum: Int = 0, expiryTs: Long = 0L) {
        val prefs = getPrefs(context)
        prefs.edit()
            .putBoolean(KEY_SLOT_EXPIRED, locked)
            .putString(KEY_SLOT_EXPIRED_REASON, reason)
            .putInt(KEY_SLOT_NUM, slotNum)
            .putLong(KEY_SLOT_EXPIRY_TS, expiryTs)
            .apply()
        notifyActivationChanged()
    }

    fun getBoundHardwareId(context: Context): String {
        val prefs = getPrefs(context)
        val bound = prefs.getString(KEY_BOUND_HW_ID, null)
        if (bound.isNullOrBlank()) {
            val current = getHardwareFingerprint(context)
            sealToCurrentDevice(context)
            return current
        }
        return bound
    }

    fun getBoundDeviceName(context: Context): String {
        return getPrefs(context).getString(KEY_BOUND_DEVICE_NAME, "Unknown Device") ?: "Unknown Device"
    }

    fun rebindWithAdminPin(context: Context, enteredPin: String): Boolean {
        if (!KioskSecurity.verifyAdminPin(context, enteredPin)) {
            Log.w(TAG, "Re-registration failed: Incorrect Admin PIN.")
            return false
        }
        val prefs = getPrefs(context)
        val currentHwId = getHardwareFingerprint(context)
        val currentDevName = getHardwareDescription()
        val now = System.currentTimeMillis()
        val sig = generateSignature(context, currentHwId, currentDevName, now)

        prefs.edit()
            .putString(KEY_BOUND_HW_ID, currentHwId)
            .putString(KEY_BOUND_DEVICE_NAME, currentDevName)
            .putLong(KEY_BOUND_TIMESTAMP, now)
            .putString(KEY_BOUND_SIGNATURE, sig)
            .apply()

        notifyActivationChanged()
        Log.i(TAG, "Device details re-registered with Admin credentials ($currentDevName - $currentHwId).")
        return true
    }

    private fun generateSignature(context: Context, hwId: String, devName: String, timestamp: Long): String {
        val secret = KioskSecurity.getSharedSecret(context)
        val payload = "$hwId|$devName|$timestamp|$secret"
        return KioskSecurity.calculateHmac(payload, secret)
    }
}
