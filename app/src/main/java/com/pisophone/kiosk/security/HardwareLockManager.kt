package com.pisophone.kiosk.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.MessageDigest

/**
 * HardwareLockManager
 *
 * Cryptographically binds and seals the kiosk application to the target device's physical hardware.
 * Provides immutable hardware fingerprinting, Direct-Boot tamper-sealed signatures (HMAC-SHA256),
 * and anti-cloning security to prevent unauthorized copying of the application to other devices.
 */
object HardwareLockManager {
    private const val TAG = "HardwareLock"
    private const val PREFS_NAME = "kiosk_hardware_seal_vault"

    val securityUpdateVersion = MutableStateFlow<Long>(System.currentTimeMillis())

    fun notifySecurityChanged() {
        securityUpdateVersion.value = System.currentTimeMillis()
    }

    private const val KEY_BOUND_HW_ID = "bound_hardware_fingerprint"
    private const val KEY_BOUND_DEVICE_NAME = "bound_device_model_name"
    private const val KEY_BOUND_TIMESTAMP = "bound_timestamp_ms"
    private const val KEY_BOUND_SIGNATURE = "bound_hardware_sig"
    private const val KEY_HARDWARE_LOCKED = "hardware_lock_enforced"
    private const val KEY_SLOT_EXPIRED = "slot_expired_lockdown"
    private const val KEY_SLOT_EXPIRED_REASON = "slot_expired_reason"
    private const val KEY_SLOT_NUM = "slot_number"
    private const val KEY_SLOT_EXPIRY_TS = "slot_expiry_timestamp"

    /**
     * Computes a stable, canonical hardware fingerprint derived strictly from immutable hardware attributes.
     * Excludes volatile Build.FINGERPRINT and Build.BOOTLOADER to survive OTA firmware and OS updates.
     * Synchronizes with Settings.Global ("pisophone_hw_id") so WebADB over USB reads the identical ID.
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

    /**
     * Synchronizes hardware ID into Android Global Settings so WebADB can read it directly.
     */
    fun syncToGlobalSettings(context: Context, hwId: String) {
        try {
            val current = Settings.Global.getString(context.contentResolver, "pisophone_hw_id")
            if (current != hwId) {
                Settings.Global.putString(context.contentResolver, "pisophone_hw_id", hwId)
            }
        } catch (e: Exception) {
            // Ignored if permissions not yet granted
        }
    }

    /**
     * Returns human-readable device model information without redundant manufacturer or device duplicates.
     */
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

    /**
     * Cryptographically binds and seals the application to the current device's hardware.
     * Called during official WebADB/ESP32 provisioning or admin manual binding.
     */
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
            .putBoolean(KEY_HARDWARE_LOCKED, false)
            .apply()

        notifySecurityChanged()
        Log.i(TAG, "Cryptographic hardware seal established for $currentDevName ($currentHwId).")
        return true
    }

    /**
     * Checks if the kiosk application is authorized to operate on this hardware and licensed slot (single auth path).
     */
    fun isAppAllowedToRun(context: Context): Boolean {
        return isHardwareAuthorized(context) && !isSlotLockedDown(context)
    }

    /**
     * Returns true if the ESP32 controller has marked this device's slot as expired,
     * triggering a hard lockdown.
     */
    fun isSlotLockedDown(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SLOT_EXPIRED, false)
    }

    /**
     * Details regarding the slot expiration lockdown: (Reason, Slot Number, Expiration Timestamp).
     */
    fun getSlotLockdownDetails(context: Context): Triple<String, Int, Long> {
        val prefs = getPrefs(context)
        val reason = prefs.getString(KEY_SLOT_EXPIRED_REASON, "Slot license expired in ESP32 memory") ?: "Slot license expired in ESP32 memory"
        val slotNum = prefs.getInt(KEY_SLOT_NUM, 0)
        val expiryTs = prefs.getLong(KEY_SLOT_EXPIRY_TS, 0L)
        return Triple(reason, slotNum, expiryTs)
    }

    /**
     * Sets or clears the hard lockdown triggered by the ESP32's authoritative slot memory.
     */
    fun setSlotLockdown(context: Context, locked: Boolean, reason: String = "", slotNum: Int = 0, expiryTs: Long = 0L) {
        val prefs = getPrefs(context)
        val currentLocked = prefs.getBoolean(KEY_SLOT_EXPIRED, false)
        if (currentLocked != locked || reason.isNotEmpty()) {
            prefs.edit()
                .putBoolean(KEY_SLOT_EXPIRED, locked)
                .putString(KEY_SLOT_EXPIRED_REASON, reason)
                .putInt(KEY_SLOT_NUM, slotNum)
                .putLong(KEY_SLOT_EXPIRY_TS, expiryTs)
                .apply()
            notifySecurityChanged()
            if (locked) {
                Log.w(TAG, "🔒 HARD LOCKDOWN ENFORCED: Slot #$slotNum expired on ESP32 ($reason)")
            } else {
                Log.i(TAG, "🔓 Slot lockdown lifted: Valid slot verified on ESP32")
            }
        }
    }

    /**
     * Checks if the physical hardware is authorized and sealed without tampering.
     * Returns false on unprovisioned/sideloaded devices or hardware mismatches.
     */
    fun isHardwareAuthorized(context: Context): Boolean {
        val prefs = getPrefs(context)
        if (prefs.getBoolean(KEY_HARDWARE_LOCKED, false)) return false
        
        // Sideload / extraction countermeasure: Unprovisioned devices must not self-seal
        val boundHwId = prefs.getString(KEY_BOUND_HW_ID, null) ?: return false
        val currentHwId = getHardwareFingerprint(context)
        
        if (boundHwId != currentHwId) {
            Log.e(TAG, "Hardware mismatch! Bound: $boundHwId, Current: $currentHwId")
            prefs.edit().putBoolean(KEY_HARDWARE_LOCKED, true).apply()
            return false
        }
        
        val storedDevName = prefs.getString(KEY_BOUND_DEVICE_NAME, "") ?: ""
        val storedTimestamp = prefs.getLong(KEY_BOUND_TIMESTAMP, 0L)
        val storedSig = prefs.getString(KEY_BOUND_SIGNATURE, "") ?: ""
        val expectedSig = generateSignature(context, boundHwId, storedDevName, storedTimestamp)
        
        return storedSig.isNotEmpty() && storedSig == expectedSig
    }

    fun getBoundHardwareId(context: Context): String {
        return getPrefs(context).getString(KEY_BOUND_HW_ID, "") ?: ""
    }

    fun getBoundDeviceName(context: Context): String {
        return getPrefs(context).getString(KEY_BOUND_DEVICE_NAME, "Unknown Device") ?: "Unknown Device"
    }

    /**
     * Allows an authorized administrator to re-seal hardware after authorized maintenance or mainboard repair.
     */
    fun rebindWithAdminPin(context: Context, enteredPin: String): Boolean {
        if (!KioskSecurity.verifyAdminPin(context, enteredPin)) {
            Log.w(TAG, "Rebind failed: Incorrect Admin PIN.")
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
            .putBoolean(KEY_HARDWARE_LOCKED, false)
            .apply()

        notifySecurityChanged()
        Log.i(TAG, "Device hardware successfully re-sealed to current device ($currentDevName - $currentHwId).")
        return true
    }

    private fun generateSignature(context: Context, hwId: String, devName: String, timestamp: Long): String {
        val secret = KioskSecurity.getSharedSecret(context)
        val payload = "$hwId|$devName|$timestamp|$secret"
        return KioskSecurity.calculateHmac(payload, secret)
    }
}
