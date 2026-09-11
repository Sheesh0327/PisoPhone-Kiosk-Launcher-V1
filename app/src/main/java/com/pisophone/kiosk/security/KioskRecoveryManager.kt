package com.pisophone.kiosk.security

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import com.pisophone.kiosk.receiver.KioskDeviceAdminReceiver

/**
 * Handles all emergency recovery, ADB restoration, lockdown escape,
 * and device de-provisioning operations.
 */
object KioskRecoveryManager {
    private const val TAG = "KioskRecovery"

    /**
     * Checks if USB Debugging (ADB) is currently enabled in global settings.
     */
    fun isUsbDebuggingEnabled(context: Context): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Emergency restoration of USB debugging.
     * Clears all DPM user restrictions, sets ADB_ENABLED = 1, and opens Developer Options.
     */
    fun emergencyEnableUsbDebugging(context: Context): Boolean {
        KioskSecurity.setAdbAllowed(context, true)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(context, KioskDeviceAdminReceiver::class.java)

        var success = false
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            try {
                dpm.clearUserRestriction(componentName, UserManager.DISALLOW_DEBUGGING_FEATURES)
                dpm.clearUserRestriction(componentName, UserManager.DISALLOW_USB_FILE_TRANSFER)
                dpm.clearUserRestriction(componentName, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)
                dpm.setGlobalSetting(componentName, Settings.Global.ADB_ENABLED, "1")
                Log.i(TAG, "Emergency: USB debugging restriction cleared and ADB_ENABLED set to 1 via DPM.")
                success = true
            } catch (e: Exception) {
                Log.w(TAG, "Emergency DPM ADB enable error: ${e.message}")
            }
        }

        try {
            Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 1)
            success = true
        } catch (e: Exception) {
            Log.w(TAG, "Direct Settings.Global.putInt ADB error: ${e.message}")
        }

        // Launch Developer Options so user can directly inspect and verify USB Debugging toggle
        try {
            val devIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(devIntent)
        } catch (e: Exception) {
            try {
                val settingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(settingsIntent)
            } catch (_: Exception) {}
        }
        return success
    }

    /**
     * Emergency exit from Kiosk Lockdown / LockTask mode.
     * Restores access to Android system settings and system launcher.
     */
    fun emergencyExitKiosk(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(context, KioskDeviceAdminReceiver::class.java)

        if (dpm.isDeviceOwnerApp(context.packageName)) {
            try {
                dpm.setLockTaskPackages(componentName, emptyArray())
                dpm.clearPackagePersistentPreferredActivities(componentName, context.packageName)
                dpm.clearUserRestriction(componentName, UserManager.DISALLOW_DEBUGGING_FEATURES)
                dpm.clearUserRestriction(componentName, UserManager.DISALLOW_FACTORY_RESET)
                dpm.clearUserRestriction(componentName, UserManager.DISALLOW_SAFE_BOOT)
            } catch (e: Exception) {
                Log.w(TAG, "Error clearing restrictions during emergency exit: ${e.message}")
            }
        }

        // Stop background kiosk service overlay
        try {
            context.stopService(Intent(context, com.pisophone.kiosk.KioskService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping KioskService: ${e.message}")
        }

        // Launch standard Android Settings
        try {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Settings: ${e.message}")
        }
    }

    /**
     * Emergency De-provision / Remove Device Owner.
     * Safely releases the device so developer can uninstall or manage freely without bricking.
     */
    fun emergencyClearDeviceOwner(context: Context): Boolean {
        return try {
            // Stop service
            context.stopService(Intent(context, com.pisophone.kiosk.KioskService::class.java))

            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(context, KioskDeviceAdminReceiver::class.java)

            if (dpm.isDeviceOwnerApp(context.packageName)) {
                try {
                    dpm.setLockTaskPackages(componentName, emptyArray())
                    dpm.clearPackagePersistentPreferredActivities(componentName, context.packageName)
                    dpm.clearUserRestriction(componentName, UserManager.DISALLOW_FACTORY_RESET)
                    dpm.clearUserRestriction(componentName, UserManager.DISALLOW_SAFE_BOOT)
                    dpm.clearUserRestriction(componentName, UserManager.DISALLOW_DEBUGGING_FEATURES)
                    dpm.setGlobalSetting(componentName, Settings.Global.ADB_ENABLED, "1")
                } catch (e: Exception) {
                    Log.w(TAG, "Error cleaning up policies before de-provision: ${e.message}")
                }
                dpm.clearDeviceOwnerApp(context.packageName)
                Log.i(TAG, "Device Owner successfully cleared!")
            }

            if (dpm.isAdminActive(componentName)) {
                dpm.removeActiveAdmin(componentName)
                Log.i(TAG, "Device Admin removed!")
            }

            try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {}

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear Device Owner: ${e.message}")
            false
        }
    }

    /**
     * Factory reset device if authorized.
     */
    fun factoryResetDevice(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(context, KioskDeviceAdminReceiver::class.java)

        return if (dpm.isDeviceOwnerApp(context.packageName)) {
            try {
                dpm.wipeData(0)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to factory reset device: ${e.message}")
                false
            }
        } else {
            false
        }
    }
}
