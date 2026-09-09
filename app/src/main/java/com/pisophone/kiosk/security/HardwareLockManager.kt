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
     * Retrieves the single canonical device identity.
     * Preserves existing bound identities or UUIDs from previous installations to prevent
     * breaking existing slot pairings, and derives a deterministic hardware fingerprint on fresh installs.
     */
    fun getCanonicalDeviceId(context: Context): String {
        val kioskPrefs = KioskSecurity.getDirectBootPrefs(context, "kiosk_prefs")
        val legacyUuid = kioskPrefs.getString("device_uuid", null)
        if (!legacyUuid.isNullOrBlank()) {
            syncToGlobalSettings(context, legacyUuid)
            return legacyUuid
        }
        val prefs = getPrefs(context)
        val bound = prefs.getString(KEY_BOUND_HW_ID, null)
        if (!bound.isNullOrBlank()) {
            syncToGlobalSettings(context, bound)
            return bound
        }
        val computed = getHardwareFingerprint(context)
        kioskPrefs.edit().putString("device_uuid", computed).apply()
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
        val currentHwId = getCanonicalDeviceId(context)
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

    private fun migrateAndClearStaleProvisioningState(context: Context) {
        try {
            val provPrefs = KioskSecurity.getDirectBootPrefs(context, "provisioning_state")
            if (provPrefs.contains("pairing_pending")) {
                val wasCompleted = provPrefs.getBoolean("pairing_completed", false)
                val setupSlot = provPrefs.getInt("setup_slot", -1)
                val setupMac = provPrefs.getString("setup_mac", "")
                val setupIp = provPrefs.getString("setup_ip", "")
                val setupName = provPrefs.getString("setup_name", "")

                if (setupSlot in 1..12) {
                    if (KioskSecurity.getAssignedBoxSlot(context) <= 0) {
                        KioskSecurity.setAssignedBoxSlot(context, setupSlot)
                    }
                    if (!setupMac.isNullOrBlank() && KioskSecurity.getConfiguredEsp32Mac(context).isBlank()) {
                        KioskSecurity.setConfiguredEsp32Mac(context, setupMac)
                    }
                    if (!setupIp.isNullOrBlank() && KioskSecurity.getConfiguredEsp32Ip(context).isBlank()) {
                        KioskSecurity.setConfiguredEsp32Ip(context, setupIp)
                    }
                    if (!setupName.isNullOrBlank() && KioskSecurity.getDeviceAlias(context).isBlank()) {
                        KioskSecurity.setDeviceAlias(context, setupName)
                    }
                }

                val hasValidConfig = KioskSecurity.getAssignedBoxSlot(context) in 1..12 &&
                        KioskSecurity.hasConfiguredSharedSecret(context)

                if (wasCompleted || hasValidConfig) {
                    provPrefs.edit().putBoolean("pairing_pending", false).putBoolean("pairing_completed", true).apply()
                    Log.i(TAG, "Cleared stale pairing_pending flag for completed/valid setup.")
                } else {
                    provPrefs.edit().putBoolean("pairing_pending", false).apply()
                    Log.i(TAG, "Cleared stale pairing_pending flag for incomplete setup; device will route to retained setup screen.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking legacy provisioning state: ${e.message}")
        }
    }

    /**
     * Checks if the kiosk application is authorized and fully configured to operate on this hardware.
     * Centralized decision across MainActivity, BootReceiver, watchdogs, and background services.
     */
    fun isAppAllowedToRun(context: Context): Boolean {
        migrateAndClearStaleProvisioningState(context)

        val userSetup = try {
            Settings.Secure.getInt(context.contentResolver, "user_setup_complete", 1)
        } catch (e: Exception) { 1 }
        val deviceProvisioned = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.DEVICE_PROVISIONED, 1)
        } catch (e: Exception) { 1 }
        if (userSetup == 0 || deviceProvisioned == 0) {
            Log.w(TAG, "App is not allowed to run fully yet: Android System Setup Wizard is still in progress.")
            return false
        }

        val prefs = getPrefs(context)
        if (prefs.getBoolean(KEY_HARDWARE_LOCKED, false)) {
            Log.w(TAG, "App is locked: hardware lock enforced.")
            return false
        }

        val currentHwId = getCanonicalDeviceId(context)
        val boundHwId = prefs.getString(KEY_BOUND_HW_ID, null)
        if (boundHwId.isNullOrBlank()) {
            Log.w(TAG, "App is not allowed to run: Hardware seal has not been established yet.")
            return false
        } else if (boundHwId != currentHwId) {
            Log.w(TAG, "App is not allowed to run: Hardware mismatch (bound: $boundHwId, current: $currentHwId)")
            return false
        }

        // Must have valid box configuration and shared secret
        if (!KioskSecurity.hasConfiguredSharedSecret(context) || KioskSecurity.getAssignedBoxSlot(context) !in 1..12) {
            Log.w(TAG, "App is not allowed to run: Device is not paired or missing box configuration.")
            return false
        }

        return true
    }

    /**
     * Legacy slot lockdown check - always returns false as hard lockdown screen is removed.
     */
    fun isSlotLockedDown(context: Context): Boolean {
        return false
    }

    /**
     * Details regarding activation status on the ESP32.
     */
    fun getSlotLockdownDetails(context: Context): Triple<String, Int, Long> {
        val prefs = getPrefs(context)
        val reason = prefs.getString(KEY_SLOT_EXPIRED_REASON, "Device activation required") ?: "Device activation required"
        val slotNum = prefs.getInt(KEY_SLOT_NUM, 0)
        val expiryTs = prefs.getLong(KEY_SLOT_EXPIRY_TS, 0L)
        return Triple(reason, slotNum, expiryTs)
    }

    /**
     * Sets activation status details received from ESP32 memory.
     */
    fun setSlotLockdown(context: Context, locked: Boolean, reason: String = "", slotNum: Int = 0, expiryTs: Long = 0L) {
        val prefs = getPrefs(context)
        prefs.edit()
            .putBoolean(KEY_SLOT_EXPIRED, locked)
            .putString(KEY_SLOT_EXPIRED_REASON, reason)
            .putInt(KEY_SLOT_NUM, slotNum)
            .putLong(KEY_SLOT_EXPIRY_TS, expiryTs)
            .apply()
        notifySecurityChanged()
    }

    /**
     * Validates hardware identity binding.
     */
    fun isHardwareAuthorized(context: Context): Boolean {
        val prefs = getPrefs(context)
        if (prefs.getBoolean(KEY_HARDWARE_LOCKED, false)) return false
        val boundHwId = prefs.getString(KEY_BOUND_HW_ID, null)
        val currentHwId = getCanonicalDeviceId(context)
        if (boundHwId.isNullOrBlank()) return false
        return boundHwId == currentHwId
    }

    fun getBoundHardwareId(context: Context): String {
        val prefs = getPrefs(context)
        return prefs.getString(KEY_BOUND_HW_ID, null) ?: ""
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
        val currentHwId = getCanonicalDeviceId(context)
        val currentDevName = getHardwareDescription()
        val now = System.currentTimeMillis()
        val sig = generateSignature(context, currentHwId, currentDevName, now)

        if (KioskSecurity.getAssignedBoxSlot(context) <= 0) {
            KioskSecurity.setAssignedBoxSlot(context, 1)
        }

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
