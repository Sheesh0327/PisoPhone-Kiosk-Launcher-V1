package com.pisophone.kiosk.util

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import com.pisophone.kiosk.receiver.KioskDeviceAdminReceiver

/**
 * Stops whatever customer app is hung so the customer can open it again.
 *
 * The kiosk cannot see which app is in front without a special permission, so it closes every
 * launchable customer app. Core system apps and the kiosk itself are never touched.
 */
object AppCloser {
    private const val TAG = "AppCloser"

    /** True for apps the customer can open and that are safe to stop. */
    fun isClosable(packageName: String, ownPackage: String, appFlags: Int): Boolean {
        if (packageName == ownPackage) return false
        val system = appFlags and ApplicationInfo.FLAG_SYSTEM != 0
        val updatedSystem = appFlags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        // Chrome or YouTube on a phone are system apps with updates; Settings and SystemUI are not.
        return !system || updatedSystem
    }

    fun closableApps(context: Context): List<String> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return try {
            pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.applicationInfo }
                .filter { isClosable(it.packageName, context.packageName, it.flags) }
                .map { it.packageName }
                .distinct()
        } catch (e: Exception) {
            Log.w(TAG, "Could not list apps: ${e.message}")
            emptyList()
        }
    }

    /**
     * Goes back to the kiosk home, then stops the customer apps. Call off the main thread.
     * Returns how many apps were asked to stop.
     */
    fun closeCustomerApps(context: Context): Int {
        AppLauncher.launchHome(context)
        val packages = closableApps(context)
        if (packages.isEmpty()) return 0
        // The apps must be in the background before the system agrees to kill them.
        Thread.sleep(BACKGROUND_WAIT_MS)

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        packages.forEach { pkg ->
            try {
                am?.killBackgroundProcesses(pkg)
            } catch (e: Exception) {
                Log.w(TAG, "killBackgroundProcesses($pkg) failed: ${e.message}")
            }
        }
        suspendAndResume(context, packages)
        Log.i(TAG, "Asked ${packages.size} customer apps to stop")
        return packages.size
    }

    /** As device owner, suspending a package force-stops it; resuming makes it launchable again. */
    private fun suspendAndResume(context: Context, packages: List<String>) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
            if (!dpm.isDeviceOwnerApp(context.packageName)) return
            val admin = ComponentName(context, KioskDeviceAdminReceiver::class.java)
            val array = packages.toTypedArray()
            dpm.setPackagesSuspended(admin, array, true)
            Thread.sleep(SUSPEND_HOLD_MS)
            dpm.setPackagesSuspended(admin, array, false)
        } catch (e: Exception) {
            Log.w(TAG, "Suspend/resume failed: ${e.message}")
        }
    }

    private const val BACKGROUND_WAIT_MS = 600L
    private const val SUSPEND_HOLD_MS = 300L
}
