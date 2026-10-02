@file:Suppress("DEPRECATION")

package com.pisophone.kiosk.security

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Core security coordinator, configuration storage, and cryptographic authentication manager.
 * Specialized policy, recovery, and data clearing functions are modularized in:
 * - [KioskRecoveryManager]
 * - [KioskPolicyManager]
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
    private const val KEY_CONFIGURED_ESP32_MAC = "configured_esp32_mac"
    private const val KEY_ASSIGNED_BOX_SLOT = "assigned_box_slot"
    private const val KEY_PROVISIONING_ADB_ALLOWED = "provisioning_adb_allowed"
    private const val KEY_ADB_GRACE_START = "adb_provisioning_grace_start"

    // WebADB keeps issuing commands (setup intent, broadcasts, reboot) for a few minutes after
    // `dpm set-device-owner`, which is when policies are first applied.
    private const val ADB_PROVISIONING_GRACE_MS = 15 * 60 * 1000L

    const val DEFAULT_SHARED_SECRET = "PISOPHONE_HMAC_MASTER_KEY"
    private const val KEY_SECRET_EXPLICITLY_PROVISIONED = "kiosk_secret_explicitly_provisioned"
    private const val DEFAULT_PIN = "1234"
    private const val TAG = "KioskSecurity"
    private const val KEY_DEVICE_SECRET = "device_crypto_secret"

    @Volatile
    private var prefsInstance: SharedPreferences? = null

    @Volatile
    private var encryptedPrefsInstance: SharedPreferences? = null

    private fun getPrefs(context: Context): SharedPreferences = prefsInstance ?: synchronized(this) {
        prefsInstance ?: buildPrefs(context.applicationContext).also {
            prefsInstance = it
        }
    }

    /**
     * Credential-encrypted prefs. NOT available before the user unlocks the device (Direct Boot),
     * so anything that must be read consistently across locked/unlocked starts (e.g. boot count
     * bookkeeping) must live in [getDirectBootPrefs] instead.
     */
    fun getEncryptedPrefs(context: Context): SharedPreferences? {
        if (encryptedPrefsInstance != null) return encryptedPrefsInstance

        val userManager = context.getSystemService(Context.USER_SERVICE) as? android.os.UserManager
        if (userManager != null && !userManager.isUserUnlocked) {
            Log.w(TAG, "EncryptedSharedPreferences unavailable before user unlock (Direct Boot)")
            return null
        }

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
                    androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
                encryptedPrefsInstance
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create EncryptedSharedPreferences: ${e.message}")
                null
            }
        }
    }

    /**
     * Falls back to a separate device-protected file when encrypted prefs are unavailable, so the
     * value read may differ between locked and unlocked starts. Do not use for state that must be
     * consistent across process restarts in Direct Boot.
     */
    fun getEncryptedPreferences(context: Context): SharedPreferences = getEncryptedPrefs(context) ?: getDirectBootPrefs(context, "secure_kiosk_prefs")

    fun getDirectBootPrefs(context: Context, name: String = PREFS_SECURITY_OLD): SharedPreferences {
        val deviceContext = context.createDeviceProtectedStorageContext()
        return deviceContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    private fun buildPrefs(context: Context): SharedPreferences = getDirectBootPrefs(context, PREFS_SECURITY_OLD)

    fun isBatteryAlertsEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_BATTERY_ALERTS_ENABLED, true)

    fun setBatteryAlertsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_BATTERY_ALERTS_ENABLED, enabled).apply()
    }

    fun getLowBatteryThreshold(context: Context): Int = getPrefs(context).getInt(KEY_LOW_BATTERY_THRESHOLD, 20)

    fun setLowBatteryThreshold(context: Context, threshold: Int) {
        getPrefs(context).edit().putInt(KEY_LOW_BATTERY_THRESHOLD, threshold.coerceIn(5, 50)).apply()
    }

    fun getHighBatteryThreshold(context: Context): Int = getPrefs(context).getInt(KEY_HIGH_BATTERY_THRESHOLD, 80)

    fun setHighBatteryThreshold(context: Context, threshold: Int) {
        getPrefs(context).edit().putInt(KEY_HIGH_BATTERY_THRESHOLD, threshold.coerceIn(50, 100)).apply()
    }

    fun getConfiguredEsp32Mac(context: Context): String = getPrefs(context).getString(KEY_CONFIGURED_ESP32_MAC, "") ?: ""

    fun setConfiguredEsp32Mac(context: Context, mac: String) {
        val clean = formatMacAddress(mac)
        getPrefs(context).edit().putString(KEY_CONFIGURED_ESP32_MAC, clean).apply()
    }

    fun getAssignedBoxSlot(context: Context): Int = getPrefs(context).getInt(KEY_ASSIGNED_BOX_SLOT, 1)

    fun setAssignedBoxSlot(context: Context, slot: Int) {
        if (slot > 0) {
            getPrefs(context).edit().putInt(KEY_ASSIGNED_BOX_SLOT, slot).apply()
        }
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

    /**
     * USB debugging is off for renters by default. It stays on during the provisioning grace
     * window, or when an admin explicitly enabled it (vault PIN / ENABLE_ADB recovery).
     */
    fun isAdbAllowed(context: Context): Boolean {
        val prefs = getPrefs(context)
        if (prefs.contains(KEY_PROVISIONING_ADB_ALLOWED)) {
            return prefs.getBoolean(KEY_PROVISIONING_ADB_ALLOWED, false)
        }
        return adbProvisioningGraceRemainingMs(context) > 0L
    }

    fun isAdbExplicitlyConfigured(context: Context): Boolean =
        getPrefs(context).contains(KEY_PROVISIONING_ADB_ALLOWED)

    /** Starts the grace window on first use (i.e. when policies are first applied). */
    fun adbProvisioningGraceRemainingMs(context: Context): Long {
        val prefs = getPrefs(context)
        val now = System.currentTimeMillis()
        var start = prefs.getLong(KEY_ADB_GRACE_START, 0L)
        if (start <= 0L) {
            start = now
            prefs.edit().putLong(KEY_ADB_GRACE_START, start).commit()
        }
        val elapsed = now - start
        if (elapsed < 0L) return 0L
        return (ADB_PROVISIONING_GRACE_MS - elapsed).coerceAtLeast(0L)
    }

    fun setAdbAllowed(context: Context, allowed: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_PROVISIONING_ADB_ALLOWED, allowed).apply()
        applyStrictKioskPolicies(context)
    }

    fun getHiddenApps(context: Context): Set<String> {
        val prefs = getPrefs(context)
        if (!prefs.contains(KEY_INITIALIZED_DEFAULT_HIDDEN)) {
            val defaultHidden = setOf(
                "com.android.settings",
                "com.google.android.settings",
            )
            prefs.edit()
                .putStringSet(KEY_HIDDEN_APPS, defaultHidden)
                .putBoolean(KEY_INITIALIZED_DEFAULT_HIDDEN, true)
                .apply()
            return defaultHidden + KioskPolicyManager.ADMIN_ONLY_PACKAGES
        }
        val stored = prefs.getStringSet(KEY_HIDDEN_APPS, emptySet()) ?: emptySet()
        return stored + KioskPolicyManager.ADMIN_ONLY_PACKAGES
    }

    fun setHiddenApps(context: Context, hiddenApps: Set<String>) {
        getPrefs(context).edit()
            .putStringSet(KEY_HIDDEN_APPS, hiddenApps)
            .putBoolean(KEY_INITIALIZED_DEFAULT_HIDDEN, true)
            .apply()
    }

    fun isAppHidden(context: Context, packageName: String): Boolean {
        if (KioskPolicyManager.isAdminOnlyPackage(packageName)) return true
        return getHiddenApps(context).contains(packageName)
    }

    fun toggleAppHidden(context: Context, packageName: String): Boolean {
        if (KioskPolicyManager.isAdminOnlyPackage(packageName)) return true
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

    fun getDeviceAlias(context: Context): String = getPrefs(context).getString(KEY_DEVICE_ALIAS, "") ?: ""

    fun getHardwareId(context: Context): String {
        val deviceContext = context.createDeviceProtectedStorageContext()
        val directPrefs = deviceContext.getSharedPreferences("kiosk_prefs", Context.MODE_PRIVATE)
        var savedUuid = directPrefs.getString("device_uuid", null)
        if (savedUuid.isNullOrBlank()) {
            val normalPrefs = context.getSharedPreferences("kiosk_prefs", Context.MODE_PRIVATE)
            savedUuid = normalPrefs.getString("device_uuid", null)
            if (savedUuid.isNullOrBlank()) {
                savedUuid = java.util.UUID.randomUUID().toString()
            }
            directPrefs.edit().putString("device_uuid", savedUuid).apply()
        }
        return savedUuid
    }

    fun setDeviceAlias(context: Context, alias: String) {
        getPrefs(context).edit().putString(KEY_DEVICE_ALIAS, alias.trim()).apply()
    }

    fun getSharedSecret(context: Context): String = DEFAULT_SHARED_SECRET

    /** True while the factory PIN is still in use (shown as a warning in the admin vault). */
    fun isAdminPinDefault(context: Context): Boolean = getAdminPin(context) == DEFAULT_PIN

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

    fun generateTimestampSignature(deviceId: String, ts: String, secret: String): String = calculateHmac("$deviceId:$ts", secret)

    /**
     * Signature for the ESP32 coin-slot calls (arm / unarm / ack). Binds the action, device,
     * timestamp and (for ack) the transaction id so a captured request cannot be reused for another
     * action or payment. Must match coinslotSignature() in esp32_firmware WebServerApi.cpp.
     */
    fun signCoinslotRequest(action: String, deviceId: String, ts: String, txId: String, secret: String): String {
        val payload = "v1:$action:$deviceId:$ts" + if (txId.isNotEmpty()) ":$txId" else ""
        return calculateHmac(payload, secret)
    }

    fun getAesKeySpec(secret: String): SecretKeySpec {
        val md = MessageDigest.getInstance("SHA-256")
        val keyBytes = md.digest(secret.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

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

    fun constantTimeEquals(a: String, b: String): Boolean = MessageDigest.isEqual(
        a.toByteArray(Charsets.UTF_8),
        b.toByteArray(Charsets.UTF_8),
    )

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
        name: String? = null,
    ): Boolean {
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
        Log.i(TAG, "[+] Successfully applied Direct Provisioning setup: MAC=$mac, Slot=$slot")
        return true
    }
}
