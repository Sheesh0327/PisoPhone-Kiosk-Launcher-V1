@file:Suppress("DEPRECATION")
package com.pisophone.kiosk.security

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import java.io.File
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object KioskSecurity {
    private const val PREFS_SECURITY_OLD = "kiosk_security_vault"
    private const val PREFS_SECURITY_ENCRYPTED = "kiosk_security_vault_enc"
    
    private const val KEY_SHARED_SECRET = "hmac_shared_secret"
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

    private fun buildPrefs(context: Context): SharedPreferences {
        val deviceContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
        // Attempting to use EncryptedSharedPreferences during a Direct Boot (LOCKED_BOOT_COMPLETED)
        // will crash the app because CE storage is unavailable. We rely purely on DE storage.
        return deviceContext.getSharedPreferences(PREFS_SECURITY_OLD, Context.MODE_PRIVATE)
    }

    fun isAutoClearOnSleepEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_CLEAR_SLEEP, true) // Enabled by default
    }

    fun setAutoClearOnSleepEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_CLEAR_SLEEP, enabled).apply()
    }

    fun getSleepClearTimeoutMinutes(context: Context): Int {
        return getPrefs(context).getInt(KEY_SLEEP_TIMEOUT_MINUTES, 5) // Default 5 minutes
    }

    fun setSleepClearTimeoutMinutes(context: Context, minutes: Int) {
        getPrefs(context).edit().putInt(KEY_SLEEP_TIMEOUT_MINUTES, minutes.coerceAtLeast(1)).apply()
    }

    fun isBatteryAlertsEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_BATTERY_ALERTS_ENABLED, true) // Enabled by default
    }

    fun setBatteryAlertsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_BATTERY_ALERTS_ENABLED, enabled).apply()
    }

    fun getLowBatteryThreshold(context: Context): Int {
        return getPrefs(context).getInt(KEY_LOW_BATTERY_THRESHOLD, 20) // Default 20%
    }

    fun setLowBatteryThreshold(context: Context, threshold: Int) {
        getPrefs(context).edit().putInt(KEY_LOW_BATTERY_THRESHOLD, threshold.coerceIn(5, 50)).apply()
    }

    fun getHighBatteryThreshold(context: Context): Int {
        return getPrefs(context).getInt(KEY_HIGH_BATTERY_THRESHOLD, 80) // Default 80%
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
        // Defaults to true so WebADB / WebUSB management, updates, and license activation remain accessible
        return getPrefs(context).getBoolean(KEY_PROVISIONING_ADB_ALLOWED, true)
    }

    fun setAdbAllowed(context: Context, allowed: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_PROVISIONING_ADB_ALLOWED, allowed).apply()
        applyStrictKioskPolicies(context)
    }

    fun clearAppCacheAndData(context: Context): Boolean {
        return try {
            // 1. Clear internal cache directory
            val cacheDir = context.cacheDir
            if (cacheDir != null && cacheDir.isDirectory) {
                cacheDir.deleteRecursively()
            }
            // 2. Clear external cache directory
            val externalCacheDir = context.externalCacheDir
            if (externalCacheDir != null && externalCacheDir.isDirectory) {
                externalCacheDir.deleteRecursively()
            }
            // 3. Clear WebView cookies and WebStorage
            try {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
            } catch (e: Exception) {
                Log.w(TAG, "WebView cache clear warning: ${e.message}")
            }
            Log.d(TAG, "Successfully cleared app cache and web session data")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing app cache: ${e.message}")
            false
        }
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

            // Generate new 256-bit random secret if none exists
            val randomBytes = ByteArray(32)
            java.security.SecureRandom().nextBytes(randomBytes)
            val newSecret = randomBytes.joinToString("") { "%02x".format(it) }
            encryptedPrefs.edit().putString(KEY_DEVICE_SECRET, newSecret).apply()
            return newSecret
        }
        
        // Fallback for DirectBoot if encrypted prefs crash
        val prefs = getPrefs(context)
        var secret = prefs.getString(KEY_DEVICE_SECRET, null)
        if (secret == null) {
            val randomBytes = ByteArray(32)
            java.security.SecureRandom().nextBytes(randomBytes)
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
        return enteredPin == getAdminPin(context)
    }

    fun calculateHmac(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key.toByteArray(), "HmacSHA256")
        mac.init(secretKey)
        val hmacBytes = mac.doFinal(data.toByteArray())
        return hmacBytes.joinToString("") { "%02x".format(it) }
    }
    
    // --- Kiosk Hardening & Escape Prevention ---
    
    fun getAllowedLockTaskPackages(context: Context): Array<String> {
        val allowedPackages = mutableSetOf(
            context.packageName,
            "com.android.systemui",
            "com.android.settings",
            "com.google.android.settings",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.shell",
            "android",
            "com.android.certinstaller",
            "com.android.companiondevicemanager",
            "com.android.systemui.usb"
        )

        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfoList = context.packageManager.queryIntentActivities(intent, 0)
            for (info in resolveInfoList) {
                val pkg = info.activityInfo.packageName
                if (pkg.isNotBlank()) {
                    allowedPackages.add(pkg)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying launcher packages: ${e.message}")
        }

        return allowedPackages.toTypedArray()
    }

    // --- FreeKiosk Inspired Enterprise Hardening & Device Controls ---

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
        
        val level = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 0

        val status = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL

        val chargePlug = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val plugType = when (chargePlug) {
            android.os.BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            android.os.BatteryManager.BATTERY_PLUGGED_AC -> "AC Wall"
            android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "Battery"
        }

        val rawTemp = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempCelsius = rawTemp / 10.0f
        val voltage = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, 0) ?: 0

        val rawHealth = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, android.os.BatteryManager.BATTERY_HEALTH_UNKNOWN) ?: 0
        val healthStr = when (rawHealth) {
            android.os.BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            android.os.BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
            android.os.BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
            android.os.BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
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

    /**
     * Reboots the Android device using Device Policy Manager (requires Device Owner).
     */
    
    /**
     * Wipes the device (Factory Reset) using Device Policy Manager.
     */
    fun factoryResetDevice(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        try {
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                Log.w(TAG, "Initiating Factory Reset via DevicePolicyManager...")
                dpm.wipeData(0)
            } else {
                Log.e(TAG, "Cannot factory reset: App is not Device Owner")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during factory reset: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error during factory reset: ${e.message}")
        }
    }

    fun rebootDevice(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val componentName = android.content.ComponentName(context, com.pisophone.kiosk.receiver.KioskDeviceAdminReceiver::class.java)
        return try {
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                Log.i(TAG, "Rebooting device via DevicePolicyManager...")
                dpm.reboot(componentName)
                true
            } else {
                Log.w(TAG, "Cannot reboot device: App is not Device Owner.")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reboot device: ${e.message}", e)
            false
        }
    }

    /**
     * Turns screen off / locks device instantly using Device Policy Manager.
     */
    fun turnScreenOff(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        return try {
            dpm.lockNow()
            Log.i(TAG, "Screen locked / turned off via DevicePolicyManager.lockNow()")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to turn screen off: ${e.message}")
            false
        }
    }

    /**
     * Wakes the screen up and acquires a temporary wake lock.
     */
    fun wakeScreenUp(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = pm.newWakeLock(
                android.os.PowerManager.FULL_WAKE_LOCK or
                        android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        android.os.PowerManager.ON_AFTER_RELEASE,
                "ArcadeOS:ScreenWakeLock"
            )
            wakeLock.acquire(3000)
            Log.i(TAG, "WakeLock acquired to turn screen on.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock: ${e.message}")
        }
        dismissKeyguard(context)
    }

    /**
     * Dismisses and disables the system keyguard to restore touch responsiveness immediately.
     */
    fun dismissKeyguard(context: Context) {
        try {
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            @Suppress("DEPRECATION")
            val keyguardLock = km?.newKeyguardLock("ArcadeOS:KeyguardDismiss")
            keyguardLock?.disableKeyguard()

            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
            val componentName = android.content.ComponentName(context, com.pisophone.kiosk.receiver.KioskDeviceAdminReceiver::class.java)
            if (dpm != null && dpm.isDeviceOwnerApp(context.packageName)) {
                try {
                    dpm.setKeyguardDisabled(componentName, true)
                } catch (e: Exception) {
                    Log.w(TAG, "dpm.setKeyguardDisabled failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to dismiss keyguard: ${e.message}")
        }
    }

    /**
     * Sets device master media volume percentage (0 - 100%).
     */
    fun setMediaVolume(context: Context, volumePercent: Int) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val target = ((volumePercent.coerceIn(0, 100) / 100f) * maxVolume).toInt()
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0)
            Log.i(TAG, "Media volume set to $volumePercent% (level $target/$maxVolume)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume: ${e.message}")
        }
    }

    /**
     * Automatically grants all sensitive runtime permissions to the Kiosk app and whitelisted packages.
     * Prevents system permission dialogs from ever interrupting kiosk mode.
     */
    fun autoGrantAllPermissions(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val componentName = android.content.ComponentName(context, com.pisophone.kiosk.receiver.KioskDeviceAdminReceiver::class.java)

        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            Log.w(TAG, "Cannot auto-grant permissions: App is not Device Owner.")
            return
        }

        val permissionsToGrant = listOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            "android.permission.POST_NOTIFICATIONS",
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO"
        )

        val targetPackages = mutableSetOf(context.packageName)
        try {
            val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val apps = context.packageManager.queryIntentActivities(launcherIntent, 0)
            for (app in apps) {
                targetPackages.add(app.activityInfo.packageName)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query launcher packages for permission grants: ${e.message}")
        }

        for (pkg in targetPackages) {
            for (permission in permissionsToGrant) {
                try {
                    val currentState = dpm.getPermissionGrantState(componentName, pkg, permission)
                    if (currentState != android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) {
                        dpm.setPermissionGrantState(
                            componentName,
                            pkg,
                            permission,
                            android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                        )
                    }
                } catch (ignored: Exception) {
                    // Ignore unsupported permissions per API level
                }
            }
        }
        Log.i(TAG, "Auto-granted runtime permissions for ${targetPackages.size} packages.")
    }

    fun applyStrictKioskPolicies(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val componentName = android.content.ComponentName(context, com.pisophone.kiosk.receiver.KioskDeviceAdminReceiver::class.java)

        if (dpm.isDeviceOwnerApp(context.packageName)) {
            try {
                // 1. Configure USB debugging / development features restriction
                val adbAllowed = isAdbAllowed(context)
                try {
                    if (adbAllowed) {
                        dpm.clearUserRestriction(componentName, android.os.UserManager.DISALLOW_DEBUGGING_FEATURES)
                    } else {
                        dpm.addUserRestriction(componentName, android.os.UserManager.DISALLOW_DEBUGGING_FEATURES)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not update DISALLOW_DEBUGGING_FEATURES: ${e.message}")
                }

                // 2. Add Anti-Tamper Enterprise User Restrictions (inspired by FreeKiosk)
                try {
                    dpm.addUserRestriction(componentName, android.os.UserManager.DISALLOW_SAFE_BOOT)
                    dpm.addUserRestriction(componentName, android.os.UserManager.DISALLOW_FACTORY_RESET)
                    dpm.addUserRestriction(componentName, android.os.UserManager.DISALLOW_ADD_USER)
                    // Note: We do NOT add DISALLOW_MOUNT_PHYSICAL_MEDIA so WebADB / USB data communication is never disrupted
                    dpm.clearUserRestriction(componentName, android.os.UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not apply user restrictions: ${e.message}")
                }

                // 3. Set ADB global setting if permitted by device policy
                try {
                    dpm.setGlobalSetting(componentName, Settings.Global.ADB_ENABLED, if (adbAllowed) "1" else "0")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not set ADB_ENABLED global setting: ${e.message}")
                }

                // 4. Set Lock Task features to allow system dialogs and global actions
                // This is crucial so that system dialogs like USB debugging RSA prompt (UsbDebuggingActivity) are visible.
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    val lockTaskFeatures = android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                            android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
                    dpm.setLockTaskFeatures(componentName, lockTaskFeatures)
                }

                // 5. Disable Keyguard (Lock Screen)
                dpm.setKeyguardDisabled(componentName, true)

                // 6. Keep screen on while plugged in
                try {
                    dpm.setGlobalSetting(
                        componentName,
                        Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                        (android.os.BatteryManager.BATTERY_PLUGGED_AC or 
                         android.os.BatteryManager.BATTERY_PLUGGED_USB or 
                         android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS).toString()
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Could not set STAY_ON_WHILE_PLUGGED_IN: ${e.message}")
                }

                // 7. Whitelist all launcher packages AND system packages (SystemUI, Settings, PackageInstaller, PermissionController)
                // This ensures the USB debugging RSA key prompt (UsbDebuggingActivity) is displayed without being blocked by Lock Task mode.
                val allowedPackages = getAllowedLockTaskPackages(context)
                dpm.setLockTaskPackages(componentName, allowedPackages)

                // 8. Auto-grant runtime permissions silently
                autoGrantAllPermissions(context)
                
                // 9. Force this app as the default persistent home launcher
                try {
                    val filter = android.content.IntentFilter(android.content.Intent.ACTION_MAIN).apply {
                        addCategory(android.content.Intent.CATEGORY_HOME)
                        addCategory(android.content.Intent.CATEGORY_DEFAULT)
                    }
                    val activity = android.content.ComponentName(context, com.pisophone.kiosk.MainActivity::class.java)
                    dpm.addPersistentPreferredActivity(componentName, filter, activity)
                    Log.i(TAG, "Successfully set as default persistent home launcher.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set persistent preferred activity: ${e.message}")
                }
                
                Log.i(TAG, "Strict Kiosk device policies successfully applied (LockTask packages: ${allowedPackages.size}, USB debugging allowed, permissions granted).")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply strict kiosk policies: ${e.message}")
            }
        } else {
            Log.w(TAG, "Cannot apply strict kiosk policies. App is NOT Device Owner.")
        }
    }
}
