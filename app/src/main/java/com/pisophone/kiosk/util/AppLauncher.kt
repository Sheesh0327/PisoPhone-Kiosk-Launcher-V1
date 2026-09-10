package com.pisophone.kiosk.util

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.pisophone.kiosk.KioskService
import com.pisophone.kiosk.receiver.KioskDeviceAdminReceiver

object AppLauncher {
    private const val TAG = "AppLauncher"

    /**
     * Robust app launcher inspired by FreeKiosk.
     * Supports opening any installed application, whitelisting it dynamically for LockTask if Device Owner,
     * resolving fallbacks for non-standard launcher activities, and handling background/foreground intents cleanly.
     */
    fun launchApp(context: Context, packageName: String, bypassKiosk: Boolean = false): Boolean {
        Log.i(TAG, "Attempting to launch app: $packageName (bypassKiosk=$bypassKiosk)")
        
        if (!com.pisophone.kiosk.security.KioskActivationManager.isAppAllowedToRun(context)) {
            Log.w(TAG, "Launch app blocked: Device is not provisioned.")
            Toast.makeText(context, "App launch blocked: Provisioning required.", Toast.LENGTH_SHORT).show()
            return false
        }

        if (bypassKiosk) {
            try {
                KioskService.triggerAdminBypass(context, 900)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to trigger admin bypass: ${e.message}")
            }
        }

        // 1. Dynamic Lock Task Whitelisting (FreeKiosk Reference pattern)
        ensureLockTaskAllowed(context, packageName)

        val pm = context.packageManager
        var intent: Intent? = null

        // 2. Primary: Standard package launch intent
        try {
            intent = pm.getLaunchIntentForPackage(packageName)
        } catch (e: Exception) {
            Log.w(TAG, "getLaunchIntentForPackage failed: ${e.message}")
        }

        // 3. Fallback 1: Query Launcher category intent
        if (intent == null) {
            try {
                val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage(packageName)
                }
                val resolveInfoList = pm.queryIntentActivities(launcherIntent, 0)
                if (resolveInfoList.isNotEmpty()) {
                    val activityInfo = resolveInfoList[0].activityInfo
                    intent = Intent().apply {
                        component = ComponentName(activityInfo.packageName, activityInfo.name)
                        action = Intent.ACTION_MAIN
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fallback launcher query failed: ${e.message}")
            }
        }

        // 4. Fallback 2: Query Info category (TV/leanback/special apps)
        if (intent == null) {
            try {
                val infoIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_INFO)
                    setPackage(packageName)
                }
                val resolveInfoList = pm.queryIntentActivities(infoIntent, 0)
                if (resolveInfoList.isNotEmpty()) {
                    val activityInfo = resolveInfoList[0].activityInfo
                    intent = Intent().apply {
                        component = ComponentName(activityInfo.packageName, activityInfo.name)
                        action = Intent.ACTION_MAIN
                        addCategory(Intent.CATEGORY_INFO)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fallback info query failed: ${e.message}")
            }
        }

        // 5. Fallback 3: First exported Activity from package info
        if (intent == null) {
            try {
                @Suppress("DEPRECATION")
                val pkgInfo = pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
                val exportedActivity = pkgInfo.activities?.firstOrNull { it.exported } 
                    ?: pkgInfo.activities?.firstOrNull()
                if (exportedActivity != null) {
                    intent = Intent().apply {
                        component = ComponentName(exportedActivity.packageName, exportedActivity.name)
                        action = Intent.ACTION_MAIN
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fallback package info activity extraction failed: ${e.message}")
            }
        }

        if (intent != null) {
            try {
                intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                context.startActivity(intent)
                Log.i(TAG, "Successfully started activity for package: $packageName")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "startActivity failed for package $packageName: ${e.message}", e)
                Toast.makeText(context, "Failed to open app: ${e.localizedMessage ?: e.message}", Toast.LENGTH_SHORT).show()
                return false
            }
        } else {
            Log.e(TAG, "No launchable activity found for package: $packageName")
            Toast.makeText(context, "Cannot open $packageName: no launchable activity found", Toast.LENGTH_SHORT).show()
            return false
        }
    }

    /**
     * Dynamically ensures that the target package is included in the LockTask whitelisted packages.
     */
    fun ensureLockTaskAllowed(context: Context, packageName: String) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
            val adminComponent = ComponentName(context, KioskDeviceAdminReceiver::class.java)
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val current = dpm.getLockTaskPackages(adminComponent)?.toMutableSet() ?: mutableSetOf()
                    if (!current.contains(packageName)) {
                        current.add(packageName)
                        current.add(context.packageName)
                        current.add("com.android.settings")
                        current.add("com.android.systemui")
                        dpm.setLockTaskPackages(adminComponent, current.toTypedArray())
                        Log.i(TAG, "Dynamically allowlisted $packageName in LockTask packages.")
                    }
                } else {
                    val packages = arrayOf(packageName, context.packageName, "com.android.settings", "com.android.systemui")
                    dpm.setLockTaskPackages(adminComponent, packages)
                    Log.i(TAG, "Dynamically allowlisted $packageName in LockTask packages (legacy).")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not ensure LockTask packages for $packageName: ${e.message}")
        }
    }

    /**
     * Launches Android System Settings with automatic admin bypass.
     */
    fun launchSettings(context: Context) {
        try {
            KioskService.triggerAdminBypass(context, 900)
            ensureLockTaskAllowed(context, "com.android.settings")
            ensureLockTaskAllowed(context, "com.google.android.settings")
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Settings: ${e.message}")
            Toast.makeText(context, "Cannot open Settings: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Launches Android Wi-Fi Settings with automatic admin bypass.
     */
    fun launchWifiSettings(context: Context) {
        try {
            KioskService.triggerAdminBypass(context, 900)
            ensureLockTaskAllowed(context, "com.android.settings")
            ensureLockTaskAllowed(context, "com.google.android.settings")
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open Wi-Fi settings, falling back to general settings: ${e.message}")
            launchSettings(context)
        }
    }

    /**
     * Launches the Home screen / Kiosk Launcher.
     */
    fun launchHome(context: Context) {
        try {
            val intent = Intent(context, com.pisophone.kiosk.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Home: ${e.message}")
            Toast.makeText(context, "Failed to go Home", Toast.LENGTH_SHORT).show()
        }
    }
}
