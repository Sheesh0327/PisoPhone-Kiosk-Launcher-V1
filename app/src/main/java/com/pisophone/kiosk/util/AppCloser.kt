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

    /** What a [boost] did: how many apps were told to stop, how much memory came free, and which app was kept. */
    class BoostResult(val closed: Int, val freedMb: Long, val keptPackage: String?)

    /** Usage events: the numbers of ACTIVITY_RESUMED / MOVE_TO_FOREGROUND and ACTIVITY_PAUSED / MOVE_TO_BACKGROUND. */
    private const val EVENT_FOREGROUND = 1
    private const val EVENT_BACKGROUND = 2

    /** The packages a boost stops: every closable app except the one in front. */
    fun packagesToBoost(closable: List<String>, foreground: String?): List<String> = closable.filter { it != foreground }

    /**
     * The app in front, from usage events given oldest first as (package, event type): the last one that came to the front and has
     * not gone to the back again. [ignore] are packages that are never "the customer's app" (the kiosk, the system's own screens).
     */
    fun foregroundFromEvents(events: List<Pair<String, Int>>, ignore: Set<String>): String? {
        var current: String? = null
        for ((pkg, type) in events) {
            if (pkg in ignore) continue
            if (type == EVENT_FOREGROUND) {
                current = pkg
            } else if (type == EVENT_BACKGROUND && pkg == current) {
                current = null
            }
        }
        return current
    }

    /** True when the owner granted this app Usage Access (Settings > Apps > Special access), which is how it can see the app in front. */
    fun hasUsageAccess(context: Context): Boolean = try {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName) ==
            android.app.AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }

    /** The package of the app in front, or null when Usage Access is not granted or nothing is in front. */
    fun foregroundPackage(context: Context): String? {
        if (!hasUsageAccess(context)) return null
        return try {
            val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val end = System.currentTimeMillis()
            val events = usage.queryEvents(end - FOREGROUND_LOOKBACK_MS, end)
            val list = ArrayList<Pair<String, Int>>()
            val event = android.app.usage.UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                list.add(event.packageName to event.eventType)
            }
            foregroundFromEvents(list, setOf(context.packageName, "com.android.systemui"))
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the app in front: ${e.message}")
            null
        }
    }

    private fun availableMb(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024L * 1024L)
    }

    /**
     * The boost: stops every other customer app (like clearing the recent apps) and keeps the one in front, then frees what it can.
     * The app in front is known when Usage Access is granted; without it only background processes are stopped, which never
     * touches the app in front. Call off the main thread.
     */
    fun boost(context: Context): BoostResult {
        val app = context.applicationContext
        val foreground = foregroundPackage(app)
        val targets = packagesToBoost(closableApps(app), foreground)
        val before = availableMb(app)
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        targets.forEach { pkg ->
            try {
                am?.killBackgroundProcesses(pkg)
            } catch (e: Exception) {
                Log.w(TAG, "killBackgroundProcesses($pkg) failed: ${e.message}")
            }
        }
        // a full stop (suspend, then allow again) only when the app in front is known and so cannot be hit by it
        if (foreground != null) suspendAndResume(app, targets)
        System.gc()
        Thread.sleep(BOOST_SETTLE_MS)
        val freed = (availableMb(app) - before).coerceAtLeast(0L)
        Log.i(TAG, "Boost: ${targets.size} apps stopped, ${freed}MB free, kept $foreground")
        return BoostResult(targets.size, freed, foreground)
    }

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

    private const val FOREGROUND_LOOKBACK_MS = 6L * 60L * 60L * 1000L
    private const val BOOST_SETTLE_MS = 700L
    private const val BACKGROUND_WAIT_MS = 600L
    private const val SUSPEND_HOLD_MS = 300L
}
