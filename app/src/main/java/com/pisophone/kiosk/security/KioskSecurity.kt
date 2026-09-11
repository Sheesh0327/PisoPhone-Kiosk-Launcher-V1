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
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Core security coordinator, configuration storage, and cryptographic authentication manager.
 * Specialized policy, recovery, and data clearing functions are modularized in:
 * - [KioskRecoveryManager]
 * - [KioskPolicyManager]
 * - [KioskDataCleaner]
 * - [KioskActivationManager]
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
    private const val KEY_CONFIGURED_ESP32_IP = "configured_esp32_ip"
    private const val KEY_CONFIGURED_ESP32_MAC = "configured_esp32_mac"
    private const val KEY_ASSIGNED_BOX_SLOT = "assigned_box_slot"
    private const val KEY_PROVISIONING_ADB_ALLOWED = "provisioning_adb_allowed"
    private const val KEY_APK_UPDATE_URL = "apk_update_url"
    
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
        return "https://pisophone.pages.dev/app-release.apk"
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
        val ip = getConfiguredEsp32Ip(context)
        return secret.isNotBlank() && (mac.isNotBlank() || ip.isNotBlank())
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

    private const val CUSTOM_KEYSTORE_ALIAS = "kiosk_custom_secret_key"
    private const val KEY_CUSTOM_ENCRYPTED_SECRET = "custom_encrypted_device_secret"

    private fun getCustomKeystoreEncryptedSecret(prefs: SharedPreferences): String? {
        val encryptedBase64 = prefs.getString(KEY_CUSTOM_ENCRYPTED_SECRET, null) ?: return null
        return try {
            val parts = encryptedBase64.split(":")
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.DEFAULT)
            val cipherText = Base64.decode(parts[1], Base64.DEFAULT)
            
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val secretKey = keyStore.getKey(CUSTOM_KEYSTORE_ALIAS, null) as? SecretKey ?: return null
            
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val plainTextBytes = cipher.doFinal(cipherText)
            String(plainTextBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Custom Keystore decryption failed: ${e.message}")
            null
        }
    }

    private fun setCustomKeystoreEncryptedSecret(prefs: SharedPreferences, secret: String) {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!keyStore.containsAlias(CUSTOM_KEYSTORE_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                val keySpec = KeyGenParameterSpec.Builder(
                    CUSTOM_KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
                keyGenerator.init(keySpec)
                keyGenerator.generateKey()
            }
            val secretKey = keyStore.getKey(CUSTOM_KEYSTORE_ALIAS, null) as SecretKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherText = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val cipherTextBase64 = Base64.encodeToString(cipherText, Base64.NO_WRAP)
            prefs.edit().putString(KEY_CUSTOM_ENCRYPTED_SECRET, "$ivBase64:$cipherTextBase64").apply()
        } catch (e: Exception) {
            Log.e(TAG, "Custom Keystore encryption failed: ${e.message}")
        }
    }

    fun getSharedSecret(context: Context): String {
        val encryptedPrefs = getEncryptedPrefs(context)
        if (encryptedPrefs != null) {
            try { 
                val existingSecret = encryptedPrefs.getString(KEY_DEVICE_SECRET, null)
                if (!existingSecret.isNullOrBlank()) return existingSecret
            } catch (e: Exception) { Log.e(TAG, "Encrypted prefs read failed: ${e.message}") }
        }
        
        val prefs = getPrefs(context)
        
        var secret = getCustomKeystoreEncryptedSecret(prefs)
        if (secret.isNullOrBlank()) {
            secret = null
        }
        
        if (secret == null && prefs.contains(KEY_DEVICE_SECRET)) {
            val oldPlainSecret = prefs.getString(KEY_DEVICE_SECRET, null)
            if (!oldPlainSecret.isNullOrBlank()) {
                setCustomKeystoreEncryptedSecret(prefs, oldPlainSecret)
                prefs.edit().remove(KEY_DEVICE_SECRET).apply()
                secret = oldPlainSecret
            }
        }
        
        if (secret == null) {
            val randomBytes = ByteArray(32)
            SecureRandom().nextBytes(randomBytes)
            secret = randomBytes.joinToString("") { "%02x".format(it) }
            
            if (encryptedPrefs != null) {
                try {
                    encryptedPrefs.edit().putString(KEY_DEVICE_SECRET, secret).apply()
                    return secret
                } catch (e: Exception) { Log.e(TAG, "Encrypted prefs write failed: ${e.message}") }
            }
            setCustomKeystoreEncryptedSecret(prefs, secret!!)
        }
        return secret!!
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
        
        if (!successWithEncryptedPrefs) {
            setCustomKeystoreEncryptedSecret(getPrefs(context), trimmed)
        }
        getPrefs(context).edit().remove(KEY_DEVICE_SECRET).apply()
    }

    fun getAdminPin(context: Context): String {
        val pin = getPrefs(context).getString(KEY_ADMIN_PIN, DEFAULT_PIN) ?: DEFAULT_PIN
        // Recover from AES decryption garbage corruption (wrong key matching 1/256 padding)
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

    fun getAesKeySpec(secret: String): SecretKeySpec {
        val md = MessageDigest.getInstance("SHA-256")
        val keyBytes = md.digest(secret.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    fun encrypt(plainText: String, secret: String): String {
        try {
            val keySpec = getAesKeySpec(secret)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val iv = ByteArray(16)
            SecureRandom().nextBytes(iv)
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            return bytesToHex(iv) + bytesToHex(encrypted)
        } catch (e: Exception) {
            Log.e(TAG, "AES Encryption error: ${e.message}")
            return ""
        }
    }

    fun decrypt(encryptedHex: String, secret: String): String {
        try {
            val hex = encryptedHex.trim()
            if (hex.length < 32) return ""
            val encryptedBytes = hexToBytes(hex)
            if (encryptedBytes.size < 17) return ""
            val iv = encryptedBytes.copyOfRange(0, 16)
            val cipherText = encryptedBytes.copyOfRange(16, encryptedBytes.size)
            val keySpec = getAesKeySpec(secret)
            val ivSpec = IvParameterSpec(iv)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decryptedBytes = cipher.doFinal(cipherText)
            return String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "AES Decryption error: ${e.message}")
            return ""
        }
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

    fun factoryResetDevice(context: Context): Boolean =
        KioskRecoveryManager.factoryResetDevice(context)

    // --- Direct Provisioning & WebADB Setup ---

    fun applyDirectProvisioning(
        context: Context,
        secret: String? = null,
        mac: String? = null,
        ip: String? = null,
        slot: Int = -1,
        name: String? = null
    ): Boolean {
        if (!secret.isNullOrBlank()) {
            setSharedSecret(context, secret.trim())
        } else if (secret != null) {
            Log.e(TAG, "[-] Rejected direct provisioning with empty or blank secret key for security!")
        }
        if (!ip.isNullOrBlank()) {
            setConfiguredEsp32Ip(context, ip.trim())
        }
        if (!mac.isNullOrBlank()) {
            val formattedMac = formatMacAddress(mac.trim())
            if (formattedMac.isNotBlank()) {
                setConfiguredEsp32Mac(context, formattedMac)
            }
        }
        if (slot > 0) {
            setAssignedBoxSlot(context, slot)
        }
        if (!name.isNullOrBlank()) {
            setDeviceAlias(context, name.trim())
        }
        Log.i(TAG, "[+] Successfully applied Direct Provisioning setup: MAC=$mac, IP=$ip, Slot=$slot, SecretConfigured=${!secret.isNullOrBlank()}")
        return true
    }
}
