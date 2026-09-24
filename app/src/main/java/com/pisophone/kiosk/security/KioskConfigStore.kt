package com.pisophone.kiosk.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Manages configuration and preference persistence for the Kiosk application.
 */
object KioskConfigStore {
    private const val TAG = "KioskConfigStore"
    private const val PREFS_SECURITY_OLD = "kiosk_security_vault"
    
    private const val KEY_ADMIN_PIN = "admin_access_pin"
    private const val KEY_DEVICE_ALIAS = "device_alias"
    private const val KEY_HIDDEN_APPS = "hidden_apps_set"
    private const val KEY_INITIALIZED_DEFAULT_HIDDEN = "initialized_default_hidden_v1"
    private const val KEY_BATTERY_ALERTS_ENABLED = "battery_alerts_enabled"
    private const val KEY_LOW_BATTERY_THRESHOLD = "low_battery_threshold"
    private const val KEY_HIGH_BATTERY_THRESHOLD = "high_battery_threshold"
    private const val KEY_CONFIGURED_ESP32_MAC = "configured_esp32_mac"
    private const val KEY_CONFIGURED_ESP32_IP = "configured_esp32_ip"
    private const val KEY_ASSIGNED_BOX_SLOT = "assigned_box_slot"
    private const val KEY_PROVISIONING_ADB_ALLOWED = "provisioning_adb_allowed"
    private const val KEY_APK_UPDATE_URL = "apk_update_url"
    private const val KEY_SECRET_EXPLICITLY_PROVISIONED = "kiosk_secret_explicitly_provisioned"
    
    const val DEFAULT_ESP32_IP = "192.168.1.10"
    const val DEFAULT_PIN = "1234"

    @Volatile
    private var prefsInstance: SharedPreferences? = null

    @Volatile
    private var encryptedPrefsInstance: SharedPreferences? = null

    fun getPrefs(context: Context): SharedPreferences {
        return prefsInstance ?: synchronized(this) {
            prefsInstance ?: buildPrefs(context.applicationContext).also { 
                prefsInstance = it 
            }
        }
    }

    fun getEncryptedPrefs(context: Context): SharedPreferences? {
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

    fun getEncryptedPreferences(context: Context): SharedPreferences {
        return getEncryptedPrefs(context) ?: getDirectBootPrefs(context, "secure_kiosk_prefs")
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

    fun clearPinnedEsp32Mac(context: Context) {
        getPrefs(context).edit().remove(KEY_CONFIGURED_ESP32_MAC).apply()
    }

    fun getConfiguredEsp32Ip(context: Context): String {
        val ip = getPrefs(context).getString(KEY_CONFIGURED_ESP32_IP, DEFAULT_ESP32_IP) ?: DEFAULT_ESP32_IP
        return if (ip.isNotBlank()) ip.trim() else DEFAULT_ESP32_IP
    }

    fun setConfiguredEsp32Ip(context: Context, ip: String): Boolean {
        val trimmed = ip.trim()
        if (isValidIpv4(trimmed)) {
            getPrefs(context).edit().putString(KEY_CONFIGURED_ESP32_IP, trimmed).apply()
            return true
        }
        return false
    }

    fun isValidIpv4(ip: String): Boolean {
        val trimmed = ip.trim()
        val parts = trimmed.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            val num = part.toIntOrNull()
            num != null && num in 0..255 && (part.length == 1 || !part.startsWith("0"))
        }
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
        val prefs = getPrefs(context)
        val isExplicit = prefs.getBoolean(KEY_SECRET_EXPLICITLY_PROVISIONED, false)
        val mac = getConfiguredEsp32Mac(context)
        return isExplicit && mac.isNotBlank()
    }

    fun isAdbAllowed(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PROVISIONING_ADB_ALLOWED, true)
    }

    fun setAdbAllowed(context: Context, allowed: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_PROVISIONING_ADB_ALLOWED, allowed).apply()
        KioskPolicyManager.applyStrictKioskPolicies(context)
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

    fun getHardwareId(context: Context): String {
        val prefs = context.getSharedPreferences("kiosk_prefs", Context.MODE_PRIVATE)
        var savedUuid = prefs.getString("device_uuid", null)
        if (savedUuid.isNullOrBlank()) {
            savedUuid = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_uuid", savedUuid).apply()
        }
        return savedUuid
    }

    fun setDeviceAlias(context: Context, alias: String) {
        getPrefs(context).edit().putString(KEY_DEVICE_ALIAS, alias.trim()).apply()
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
}
