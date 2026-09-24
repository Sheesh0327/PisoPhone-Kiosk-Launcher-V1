package com.pisophone.kiosk.security

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import com.pisophone.kiosk.MainActivity
import com.pisophone.kiosk.receiver.KioskDeviceAdminReceiver

/**
 * Manages all Enterprise Device Policy Manager (DPM) configurations,
 * LockTask package whitelists, runtime permission automation, and display/keyguard policies.
 */
object KioskPolicyManager {
    private const val TAG = "KioskPolicyManager"

    /**
     * Determines whether a package is a critical system dependency that should never be blocked.
     */
    fun isSystemPackageWhitelisted(packageName: String): Boolean {
        return packageName == "com.android.systemui" ||
                packageName == "com.android.settings" ||
                packageName == "com.android.packageinstaller" ||
                packageName == "com.google.android.packageinstaller" ||
                packageName == "com.android.permissioncontroller" ||
                packageName == "com.google.android.permissioncontroller" ||
                packageName == "com.android.vending" ||
                packageName == "com.google.android.gms" ||
                packageName.startsWith("com.android.inputmethod") ||
                packageName.startsWith("com.google.android.inputmethod")
    }

    /**
     * Computes the complete whitelist array for LockTask mode.
     */
    fun getAllowedLockTaskPackages(context: Context): Array<String> {
        val packages = mutableSetOf(
            context.packageName,
            "com.android.systemui",
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.vending",
            "com.google.android.gms"
        )

        try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val apps = context.packageManager.queryIntentActivities(mainIntent, 0)
            for (app in apps) {
                val pkg = app.activityInfo.packageName
                if (!KioskSecurity.isAppHidden(context, pkg)) {
                    packages.add(pkg)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying launcher packages: ${e.message}")
        }

        return packages.toTypedArray()
    }

    /**
     * Automatically grants all sensitive runtime permissions to the Kiosk app and whitelisted packages.
     * Prevents system permission dialogs from ever interrupting kiosk mode.
     */
    fun autoGrantAllPermissions(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(context, KioskDeviceAdminReceiver::class.java)

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
                    if (currentState != DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) {
                        dpm.setPermissionGrantState(
                            componentName,
                            pkg,
                            permission,
                            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                        )
                    }
                } catch (e: Exception) {}
            }
        }
        Log.i(TAG, "Auto-granted runtime permissions for ${targetPackages.size} packages.")
    }

    /**
     * Applies strict Enterprise Device Owner policies to the terminal.
     */
    fun applyStrictKioskPolicies(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(context, KioskDeviceAdminReceiver::class.java)

        if (dpm.isDeviceOwnerApp(context.packageName)) {
            try {
                // 1. Configure USB debugging / development features restriction
                val adbAllowed = KioskSecurity.isAdbAllowed(context)
                try {
                    if (adbAllowed) {
                        dpm.clearUserRestriction(componentName, UserManager.DISALLOW_DEBUGGING_FEATURES)
                    } else {
                        dpm.addUserRestriction(componentName, UserManager.DISALLOW_DEBUGGING_FEATURES)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not update DISALLOW_DEBUGGING_FEATURES: ${e.message}")
                }

                // 2. Add Anti-Tamper Enterprise User Restrictions
                try {
                    dpm.addUserRestriction(componentName, UserManager.DISALLOW_SAFE_BOOT)
                    dpm.addUserRestriction(componentName, UserManager.DISALLOW_FACTORY_RESET)
                    dpm.clearUserRestriction(componentName, UserManager.DISALLOW_ADD_USER)
                    dpm.clearUserRestriction(componentName, UserManager.DISALLOW_MODIFY_ACCOUNTS)
                    dpm.addUserRestriction(componentName, UserManager.DISALLOW_USB_FILE_TRANSFER)
                    dpm.addUserRestriction(componentName, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)
                    dpm.addUserRestriction(componentName, UserManager.DISALLOW_APPS_CONTROL)
                    dpm.addUserRestriction(componentName, UserManager.DISALLOW_UNINSTALL_APPS)
                    dpm.addUserRestriction(componentName, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
                    dpm.addUserRestriction(componentName, UserManager.DISALLOW_SYSTEM_ERROR_DIALOGS)
                    dpm.addUserRestriction(componentName, UserManager.DISALLOW_NETWORK_RESET)
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val lockTaskFeatures = DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                            DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
                    dpm.setLockTaskFeatures(componentName, lockTaskFeatures)
                }

                // 5. Disable Keyguard
                dpm.setKeyguardDisabled(componentName, true)

                // 6. Keep screen on while plugged in
                try {
                    dpm.setGlobalSetting(
                        componentName,
                        Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                        (BatteryManager.BATTERY_PLUGGED_AC or
                                BatteryManager.BATTERY_PLUGGED_USB or
                                BatteryManager.BATTERY_PLUGGED_WIRELESS).toString()
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Could not set STAY_ON_WHILE_PLUGGED_IN: ${e.message}")
                }

                // 7. Whitelist all launcher packages and system dependencies
                val allowedPackages = getAllowedLockTaskPackages(context)
                dpm.setLockTaskPackages(componentName, allowedPackages)

                // 8. Auto-grant runtime permissions silently
                autoGrantAllPermissions(context)

                // 9. Disable Notification Shade and Status Bar Expansion
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        val disabled = dpm.setStatusBarDisabled(componentName, true)
                        Log.i(TAG, "DevicePolicyManager.setStatusBarDisabled(true) executed: $disabled")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to disable status bar via DPM: ${e.message}")
                    }
                }

                Log.i(TAG, "Strict Kiosk device policies successfully applied.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply strict kiosk policies: ${e.message}")
            }
        } else {
            Log.w(TAG, "Cannot apply strict kiosk policies. App is NOT Device Owner.")
        }
    }

    /**
     * Wakes the screen up and acquires a temporary wake lock.
     */
    fun wakeScreenUp(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
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
     * Dismisses and disables the system keyguard.
     */
    fun dismissKeyguard(context: Context) {
        try {
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            @Suppress("DEPRECATION")
            val keyguardLock = km?.newKeyguardLock("ArcadeOS:KeyguardDismiss")
            keyguardLock?.disableKeyguard()

            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val componentName = ComponentName(context, KioskDeviceAdminReceiver::class.java)
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
     * Turn screen off / lock device.
     */
    fun turnScreenOff(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
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
     * Programmatically enable or disable the Android notification shade / status bar pull-down.
     */
    fun setStatusBarDisabled(context: Context, disabled: Boolean): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
        val componentName = ComponentName(context, KioskDeviceAdminReceiver::class.java)
        if (dpm.isDeviceOwnerApp(context.packageName) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return try {
                val res = dpm.setStatusBarDisabled(componentName, disabled)
                Log.i(TAG, "setStatusBarDisabled($disabled) executed: $res")
                res
            } catch (e: Exception) {
                Log.w(TAG, "Failed to setStatusBarDisabled($disabled): ${e.message}")
                false
            }
        }
        return false
    }

    /**
     * Collapses notification shade and status bar panels as a secondary safeguard.
     */
    fun collapseStatusBar(context: Context) {
        // Enforce status bar disable policy if Device Owner
        setStatusBarDisabled(context, true)

        // Close system dialogs/shade safely without hidden API reflection
        try {
            @Suppress("DEPRECATION")
            val closeDialogsIntent = android.content.Intent(android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            context.sendBroadcast(closeDialogsIntent)
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_CLOSE_SYSTEM_DIALOGS broadcast exception: ${e.message}")
        }
    }

    /**
     * Checks if this application is currently active as the Android Device Owner.
     */
    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
        return try {
            dpm.isDeviceOwnerApp(context.packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Error checking Device Owner state: ${e.message}")
            false
        }
    }
}
