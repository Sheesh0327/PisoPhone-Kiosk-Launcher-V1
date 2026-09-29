@file:Suppress("DEPRECATION")
package com.pisophone.kiosk.security

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.BatteryManager
import android.util.Log
import javax.crypto.spec.SecretKeySpec

/**
 * Core security coordinator, configuration storage, and cryptographic authentication manager.
 * Specialized policy, recovery, crypto, and data clearing functions are modularized in:
 * - [KioskRecoveryManager]
 * - [KioskPolicyManager]
 * - [KioskDataCleaner]
 * - [KioskActivationManager]
 * - [KioskCrypto]
 */
object KioskSecurity {
    private const val PREFS_SECURITY_OLD = "kiosk_security_vault"
    
    private const val KEY_ADMIN_PIN = "admin_access_pin"
    private const val KEY_DEVICE_ALIAS = "device_alias"
    private const val KEY_HIDDEN_APPS = "hidden_apps_set"
    private const val KEY_INITIALIZED_DEFAULT_HIDDEN = "initialized_default_hidden_v1"
    private const val KEY_BATTERY_ALERTS_ENABLED = "battery_alerts_enabled"
    private const val KEY_LOW_BATTERY_THRESHOLD = "low_battery_threshold"
    private const val KEY_HIGH_BATTERY_THRESHOLD = "high_battery_threshold"
    private const val KEY_CONFIGURED_ESP32_MAC = "configured_esp32_mac"
    private const val KEY_ASSIGNED_BOX_SLOT = "assigned_box_slot"
    private const val KEY_PROVISIONING_ADB_ALLOWED = "provisioning_adb_allowed"
    private const val KEY_APK_UPDATE_URL = "apk_update_url"
    
    private const val DEFAULT_PIN = "1234"
    private const val TAG = "KioskSecurity"
    private const val KEY_DEVICE_SECRET = "device_crypto_secret"
    const val MASTER_CRYPTO_SECRET = "PISOPHONE_HMAC_MASTER_KEY"

    @Volatile
    private var prefsInstance: SharedPreferences? = null

    @Volatile
    private var encryptedPrefsInstance: SharedPreferences? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return prefsInstance ?: synchronized(this) {
            prefsInstance ?: buildPrefs(context.applicationContext).also { 
                prefsInstance = it 
            }
        }
    }

    private fun getEncryptedPrefs(context: Context): SharedPreferences? {
        if (encryptedPrefsInstance != null) return encryptedPrefsInstance
        
        return synchronized(this) {
            if (encryptedPrefsInstance != null) return encryptedPrefsInstance
            try {
                val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                    .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                    .build()
                    
                encryptedPrefsInstance = androidx.security.crypto.EncryptedSharedPreferences.create(
                    context,
                    "secret_prefs",
                    masterKey,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                encryptedPrefsInstance
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create EncryptedSharedPreferences: ${e.message}")
                null
            }
        }
    }

    fun getDirectBootPrefs(context: Context, name: String = PREFS_SECURITY_OLD): SharedPreferences {
        val deviceContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
        return deviceContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    private fun buildPrefs(context: Context): SharedPreferences {
        return getDirectBootPrefs(context, PREFS_SECURITY_OLD)
    }

    fun isBatteryAlertsEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_BATTERY_ALERTS_ENABLED, true)
    }

    fun setBatteryAlertsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_BATTERY_ALERTS_ENABLED, enabled).apply()
    }

    fun getLowBatteryThreshold(context: Context): Int {
        return getPrefs(context).getInt(KEY_LOW_BATTERY_THRESHOLD, 20)
    }

    fun setLowBatteryThreshold(context: Context, threshold: Int) {
        getPrefs(context).edit().putInt(KEY_LOW_BATTERY_THRESHOLD, threshold.coerceIn(5, 50)).apply()
    }

    fun getHighBatteryThreshold(context: Context): Int {
        return getPrefs(context).getInt(KEY_HIGH_BATTERY_THRESHOLD, 80)
    }

    fun setHighBatteryThreshold(context: Context, threshold: Int) {
        getPrefs(context).edit().putInt(KEY_HIGH_BATTERY_THRESHOLD, threshold.coerceIn(50, 100)).apply()
    }

    fun getConfiguredEsp32Mac(context: Context): String {
        return getPrefs(context).getString(KEY_CONFIGURED_ESP32_MAC, "") ?: ""
    }

    fun setConfiguredEsp32Mac(context: Context, mac: String) {
        val clean = formatMacAddress(mac)
        getPrefs(context).edit().putString(KEY_CONFIGURED_ESP32_MAC, clean).apply()
    }

    fun getAssignedBoxSlot(context: Context): Int {
        return getPrefs(context).getInt(KEY_ASSIGNED_BOX_SLOT, 1)
    }

    fun setAssignedBoxSlot(context: Context, slot: Int) {
        if (slot > 0) {
            getPrefs(context).edit().putInt(KEY_ASSIGNED_BOX_SLOT, slot).apply()
        }
    }

    fun getApkUpdateUrl(context: Context): String {
        return "https://pisophone.pages.dev/update/app-release.apk"
    }

    fun setApkUpdateUrl(context: Context, url: String) {
        getPrefs(context).edit().putString(KEY_APK_UPDATE_URL, url.trim()).apply()
    }

    fun formatMacAddress(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val clean = input.replace("[^a-fA-F0-9]".toRegex(), "").uppercase()
        if (clean.length == 12) {
            return clean.chunked(2).joinToString(":")
        }
        return input.trim().uppercase()
    }

    fun isProvisioned(context: Context): Boolean {
        val secret = getSharedSecret(context)
        val mac = getConfiguredEsp32Mac(context)
        return secret.isNotBlank() && mac.isNotBlank()
    }

    fun isAdbAllowed(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PROVISIONING_ADB_ALLOWED, true)
    }

    fun setAdbAllowed(context: Context, allowed: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_PROVISIONING_ADB_ALLOWED, allowed).apply()
        applyStrictKioskPolicies(context)
    }

    fun clearAppCacheAndData(context: Context): Boolean {
        return KioskDataCleaner.clearAppCacheAndData(context)
    }

    fun getHiddenApps(context: Context): Set<String> {
        val prefs = getPrefs(context)
        if (!prefs.contains(KEY_INITIALIZED_DEFAULT_HIDDEN)) {
            val defaultHidden = setOf(
                "com.android.settings",
                "com.google.android.settings"
            )
            prefs.edit()
                .putStringSet(KEY_HIDDEN_APPS, defaultHidden)
                .putBoolean(KEY_INITIALIZED_DEFAULT_HIDDEN, true)
                .apply()
                return defaultHidden
        }
        return prefs.getStringSet(KEY_HIDDEN_APPS, emptySet()) ?: emptySet()
    }

    fun setHiddenApps(context: Context, hiddenApps: Set<String>) {
        getPrefs(context).edit()
            .putStringSet(KEY_HIDDEN_APPS, hiddenApps)
            .putBoolean(KEY_INITIALIZED_DEFAULT_HIDDEN, true)
            .apply()
    }

    fun isAppHidden(context: Context, packageName: String): Boolean {
        if (packageName == "com.android.vending") return false
        return getHiddenApps(context).contains(packageName)
    }

    fun toggleAppHidden(context: Context, packageName: String): Boolean {
        if (packageName == "com.android.vending") return false
        val current = getHiddenApps(context).toMutableSet()
        val isNowHidden = if (current.contains(packageName)) {
            current.remove(packageName)
            false
        } else {
            current.add(packageName)
            true
        }
        setHiddenApps(context, current)
        return isNowHidden
    }

    fun getDeviceAlias(context: Context): String {
        return getPrefs(context).getString(KEY_DEVICE_ALIAS, "") ?: ""
    }

    fun setDeviceAlias(context: Context, alias: String) {
        getPrefs(context).edit().putString(KEY_DEVICE_ALIAS, alias.trim()).apply()
    }

    fun getSharedSecret(context: Context? = null): String {
        if (context != null) {
            val encryptedPrefs = getEncryptedPrefs(context)
            val encSecret = try { encryptedPrefs?.getString(KEY_DEVICE_SECRET, null) } catch (_: Exception) { null }
            val candidate = encSecret ?: getPrefs(context).getString(KEY_DEVICE_SECRET, null)
            if (!candidate.isNullOrBlank() && candidate != DEFAULT_PIN) {
                if (!(candidate.length == 64 && candidate.all { it in "0123456789abcdefABCDEF" })) {
                    return candidate
                }
            }
        }
        return MASTER_CRYPTO_SECRET
    }

    fun setSharedSecret(context: Context, newSecret: String) {
        val trimmed = newSecret.trim()
        if (trimmed.isEmpty()) {
            Log.e(TAG, "Attempted to set an empty or blank shared secret. Rejected for security!")
            return
        }
        val encryptedPrefs = getEncryptedPrefs(context)
        var successWithEncryptedPrefs = false
        if (encryptedPrefs != null) {
            try { 
                encryptedPrefs.edit().putString(KEY_DEVICE_SECRET, trimmed).apply()
                successWithEncryptedPrefs = true
            } catch (e: Exception) { Log.e(TAG, "Encrypted prefs write failed: ${e.message}") }
        } 
        
        val prefs = getPrefs(context)
        if (!successWithEncryptedPrefs) {
            val keystoreSuccess = KioskCrypto.setCustomKeystoreEncryptedSecret(prefs, trimmed)
            if (!keystoreSuccess) {
                prefs.edit().putString(KEY_DEVICE_SECRET, trimmed).apply()
            } else {
                prefs.edit().remove(KEY_DEVICE_SECRET).apply()
            }
        } else {
            prefs.edit().remove(KEY_DEVICE_SECRET).apply()
        }
    }

    fun getAdminPin(context: Context): String {
        val pin = getPrefs(context).getString(KEY_ADMIN_PIN, DEFAULT_PIN) ?: DEFAULT_PIN
        if (pin.any { it < ' ' || it > '~' }) {
            setAdminPin(context, DEFAULT_PIN)
            return DEFAULT_PIN
        }
        return pin
    }

    fun setAdminPin(context: Context, newPin: String) {
        getPrefs(context).edit().putString(KEY_ADMIN_PIN, newPin.trim()).apply()
    }

    fun verifyAdminPin(context: Context, enteredPin: String): Boolean {
        val storedPin = getAdminPin(context)
        return constantTimeEquals(enteredPin.trim(), storedPin)
    }

    // --- Crypto Delegates ---

    fun calculateHmac(data: String, key: String): String = KioskCrypto.calculateHmac(data, key)

    fun generateTimestampSignature(deviceId: String, ts: String, secret: String): String =
        KioskCrypto.generateTimestampSignature(deviceId, ts, secret)

    fun getAesKeySpec(secret: String): SecretKeySpec = KioskCrypto.getAesKeySpec(secret)

    fun bytesToHex(bytes: ByteArray): String = KioskCrypto.bytesToHex(bytes)

    fun hexToBytes(hex: String): ByteArray = KioskCrypto.hexToBytes(hex)

    fun encrypt(plainText: String, secret: String): String = KioskCrypto.encrypt(plainText, secret)

    fun decrypt(encryptedHex: String, secret: String): String = KioskCrypto.decrypt(encryptedHex, secret)

    fun constantTimeEquals(a: String, b: String): Boolean = KioskCrypto.constantTimeEquals(a, b)

    // --- Battery Diagnostics ---

    data class BatteryInfo(
        val level: Int,
        val scale: Int,
        val percentage: Int,
        val isCharging: Boolean,
        val plugType: String,
        val temperatureCelsius: Float,
        val voltageMv: Int,
        val health: String
    )

    fun getBatteryDiagnostics(context: Context): BatteryInfo {
        val intentFilter = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)
        
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 0

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val chargePlug = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val plugType = when (chargePlug) {
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_AC -> "AC Wall"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "Battery"
        }

        val rawTemp = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempCelsius = rawTemp / 10.0f
        val voltage = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0

        val rawHealth = batteryStatus?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN) ?: 0
        val healthStr = when (rawHealth) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Unknown"
        }

        return BatteryInfo(
            level = level,
            scale = scale,
            percentage = pct,
            isCharging = isCharging,
            plugType = plugType,
            temperatureCelsius = tempCelsius,
            voltageMv = voltage,
            health = healthStr
        )
    }

    fun setMediaVolume(context: Context, volumePercent: Int) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = ((volumePercent.coerceIn(0, 100) / 100f) * maxVolume).toInt()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            Log.i(TAG, "Media volume set to $volumePercent% (level $target/$maxVolume)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume: ${e.message}")
        }
    }

    // --- Delegated Policy & Display Methods ---

    fun getAllowedLockTaskPackages(context: Context): Array<String> =
        KioskPolicyManager.getAllowedLockTaskPackages(context)

    fun autoGrantAllPermissions(context: Context) =
        KioskPolicyManager.autoGrantAllPermissions(context)

    fun applyStrictKioskPolicies(context: Context) =
        KioskPolicyManager.applyStrictKioskPolicies(context)

    fun wakeScreenUp(context: Context) =
        KioskPolicyManager.wakeScreenUp(context)

    fun dismissKeyguard(context: Context) =
        KioskPolicyManager.dismissKeyguard(context)

    fun turnScreenOff(context: Context): Boolean =
        KioskPolicyManager.turnScreenOff(context)

    fun setStatusBarDisabled(context: Context, disabled: Boolean): Boolean =
        KioskPolicyManager.setStatusBarDisabled(context, disabled)

    fun collapseStatusBar(context: Context) =
        KioskPolicyManager.collapseStatusBar(context)

    // --- Delegated Recovery Methods ---

    fun isUsbDebuggingEnabled(context: Context): Boolean =
        KioskRecoveryManager.isUsbDebuggingEnabled(context)

    fun emergencyEnableUsbDebugging(context: Context): Boolean =
        KioskRecoveryManager.emergencyEnableUsbDebugging(context)

    fun emergencyExitKiosk(context: Context) =
        KioskRecoveryManager.emergencyExitKiosk(context)

    fun emergencyClearDeviceOwner(context: Context): Boolean =
        KioskRecoveryManager.emergencyClearDeviceOwner(context)

    fun factoryResetDevice(context: Context): Boolean =
        KioskRecoveryManager.factoryResetDevice(context)

    // --- Direct Provisioning & WebADB Setup ---

    fun applyDirectProvisioning(
        context: Context,
        secret: String? = null,
        mac: String? = null,
        slot: Int = -1,
        name: String? = null
    ): Boolean {
        if (!secret.isNullOrBlank()) {
            setSharedSecret(context, secret.trim())
        } else if (secret != null) {
            Log.e(TAG, "[-] Rejected direct provisioning with empty or blank secret key for security!")
        }
        if (!mac.isNullOrBlank()) {
            val formattedMac = formatMacAddress(mac.trim())
            if (formattedMac.isNotBlank()) {
                setConfiguredEsp32Mac(context, formattedMac)
            }
        }
        if (slot > 0) {
            setAssignedBoxSlot(context, slot)
            setDeviceAlias(context, "PisoPhone $slot")
        } else if (!name.isNullOrBlank()) {
            setDeviceAlias(context, name.trim())
        }
        Log.i(TAG, "[+] Successfully applied Direct Provisioning setup: MAC=$mac, Slot=$slot, SecretConfigured=${!secret.isNullOrBlank()}")
        return true
    }
}
