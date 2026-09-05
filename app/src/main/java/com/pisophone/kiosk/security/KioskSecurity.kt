@file:Suppress("DEPRECATION")
package com.pisophone.kiosk.security

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.BatteryManager
import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Core security coordinator, configuration storage, and cryptographic authentication manager.
 * Specialized policy, recovery, and data clearing functions are modularized in:
 * - [KioskRecoveryManager]
 * - [KioskPolicyManager]
 * - [KioskDataCleaner]
 * - [HardwareLockManager]
 */
object KioskSecurity {
    private const val PREFS_SECURITY_OLD = "kiosk_security_vault"
    
    private const val KEY_ADMIN_PIN = "admin_access_pin"
    private const val KEY_DEVICE_ALIAS = "device_alias"
    private const val KEY_HIDDEN_APPS = "hidden_apps_set"
    private const val KEY_INITIALIZED_DEFAULT_HIDDEN = "initialized_default_hidden_v1"
    private const val KEY_AUTO_CLEAR_SLEEP = "auto_clear_on_sleep_enabled"
    private const val KEY_SLEEP_TIMEOUT_MINUTES = "sleep_clear_timeout_minutes"
    private const val KEY_BATTERY_ALERTS_ENABLED = "battery_alerts_enabled"
    private const val KEY_LOW_BATTERY_THRESHOLD = "low_battery_threshold"
    private const val KEY_HIGH_BATTERY_THRESHOLD = "high_battery_threshold"
    private const val KEY_CONFIGURED_ESP32_IP = "configured_esp32_ip"
    private const val KEY_PROVISIONING_ADB_ALLOWED = "provisioning_adb_allowed"
    
    private const val DEFAULT_PIN = "1234"
    private const val TAG = "KioskSecurity"
    private const val KEY_DEVICE_SECRET = "device_crypto_secret"

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

    fun isAutoClearOnSleepEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_CLEAR_SLEEP, true)
    }

    fun setAutoClearOnSleepEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_CLEAR_SLEEP, enabled).apply()
    }

    fun getSleepClearTimeoutMinutes(context: Context): Int {
        return getPrefs(context).getInt(KEY_SLEEP_TIMEOUT_MINUTES, 5)
    }

    fun setSleepClearTimeoutMinutes(context: Context, minutes: Int) {
        getPrefs(context).edit().putInt(KEY_SLEEP_TIMEOUT_MINUTES, minutes.coerceAtLeast(1)).apply()
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

    fun getConfiguredEsp32Ip(context: Context): String {
        return getPrefs(context).getString(KEY_CONFIGURED_ESP32_IP, "") ?: ""
    }

    fun setConfiguredEsp32Ip(context: Context, ip: String) {
        getPrefs(context).edit().putString(KEY_CONFIGURED_ESP32_IP, ip.trim()).apply()
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
        return getHiddenApps(context).contains(packageName)
    }

    fun toggleAppHidden(context: Context, packageName: String): Boolean {
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

    fun getSharedSecret(context: Context): String {
        val encryptedPrefs = getEncryptedPrefs(context)
        if (encryptedPrefs != null) {
            val existingSecret = encryptedPrefs.getString(KEY_DEVICE_SECRET, null)
            if (existingSecret != null) return existingSecret

            val randomBytes = ByteArray(32)
            SecureRandom().nextBytes(randomBytes)
            val newSecret = randomBytes.joinToString("") { "%02x".format(it) }
            encryptedPrefs.edit().putString(KEY_DEVICE_SECRET, newSecret).apply()
            return newSecret
        }
        
        val prefs = getPrefs(context)
        var secret = prefs.getString(KEY_DEVICE_SECRET, null)
        if (secret == null) {
            val randomBytes = ByteArray(32)
            SecureRandom().nextBytes(randomBytes)
            secret = randomBytes.joinToString("") { "%02x".format(it) }
            prefs.edit().putString(KEY_DEVICE_SECRET, secret).apply()
        }
        return secret!!
    }

    fun setSharedSecret(context: Context, newSecret: String) {
        val encryptedPrefs = getEncryptedPrefs(context)
        if (encryptedPrefs != null) {
            encryptedPrefs.edit().putString(KEY_DEVICE_SECRET, newSecret.trim()).apply()
        } else {
            getPrefs(context).edit().putString(KEY_DEVICE_SECRET, newSecret.trim()).apply()
        }
    }

    fun getAdminPin(context: Context): String {
        return getPrefs(context).getString(KEY_ADMIN_PIN, DEFAULT_PIN) ?: DEFAULT_PIN
    }

    fun setAdminPin(context: Context, newPin: String) {
        getPrefs(context).edit().putString(KEY_ADMIN_PIN, newPin.trim()).apply()
    }

    fun verifyAdminPin(context: Context, enteredPin: String): Boolean {
        val storedPin = getAdminPin(context)
        if (storedPin == DEFAULT_PIN) {
            // Rule 7 Compliance: Default credential must not be usable in production.
            return false 
        }
        return constantTimeEquals(enteredPin.trim(), storedPin)
    }

    fun calculateHmac(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        val hmacBytes = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return hmacBytes.joinToString("") { "%02x".format(it) }
    }

    fun generateTimestampSignature(deviceId: String, ts: String, secret: String): String {
        return calculateHmac("$deviceId:$ts", secret)
    }

    fun constantTimeEquals(a: String, b: String): Boolean {
        return MessageDigest.isEqual(
            a.toByteArray(Charsets.UTF_8),
            b.toByteArray(Charsets.UTF_8)
        )
    }

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

    // --- Delegated Recovery Methods ---

    fun isUsbDebuggingEnabled(context: Context): Boolean =
        KioskRecoveryManager.isUsbDebuggingEnabled(context)

    fun emergencyEnableUsbDebugging(context: Context): Boolean =
        KioskRecoveryManager.emergencyEnableUsbDebugging(context)

    fun emergencyExitKiosk(context: Context) =
        KioskRecoveryManager.emergencyExitKiosk(context)

    fun emergencyClearDeviceOwner(context: Context): Boolean =
        KioskRecoveryManager.emergencyClearDeviceOwner(context)

    fun rebootDevice(context: Context): Boolean =
        KioskRecoveryManager.rebootDevice(context)

    fun factoryResetDevice(context: Context): Boolean =
        KioskRecoveryManager.factoryResetDevice(context)
}
