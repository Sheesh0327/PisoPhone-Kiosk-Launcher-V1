package com.pisophone.kiosk.util

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import com.pisophone.kiosk.receiver.KioskDeviceAdminReceiver
import java.util.concurrent.Executors

/**
 * Leaves the phone clean for the next customer: wipes the data of the social, chat, mail and browser apps ([SocialApps]) and the
 * clipboard. Needs the phone to be the device owner and Android 9 or newer; anywhere else it only logs why it did nothing.
 * Games are left as they are. Call off the main thread.
 */
object GuestReset {
    private const val TAG = "GuestReset"
    private val executor = Executors.newSingleThreadExecutor()

    /** The installed packages that are on the wipe list, given every installed package name. */
    fun packagesToClear(installed: List<String>, ownPackage: String, extra: Set<String>, excluded: Set<String>): List<String> =
        installed.filter { it != ownPackage && SocialApps.isSocial(it, extra, excluded) }.distinct()

    /** Returns how many apps were asked to clear their data. */
    fun run(context: Context): Int {
        val app = context.applicationContext
        if (!KioskSettings.isGuestClearEnabled(app)) return 0
        val dpm = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        if (dpm == null || !dpm.isDeviceOwnerApp(app.packageName)) {
            DiagnosticsLog.add("Guest reset", "Skipped: this phone is not the device owner")
            return 0
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            DiagnosticsLog.add("Guest reset", "Skipped: clearing app data needs Android 9 or newer")
            return 0
        }
        val admin = ComponentName(app, KioskDeviceAdminReceiver::class.java)
        val installed = try {
            @Suppress("DEPRECATION")
            app.packageManager.getInstalledApplications(0).map { it.packageName }
        } catch (e: Exception) {
            Log.w(TAG, "Could not list the apps: ${e.message}")
            emptyList()
        }
        val targets = packagesToClear(installed, app.packageName, KioskSettings.getSocialExtra(app), KioskSettings.getSocialExcluded(app))
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        var asked = 0
        for (pkg in targets) {
            try {
                am?.killBackgroundProcesses(pkg)
                dpm.clearApplicationUserData(admin, pkg, executor) { cleared, ok ->
                    Log.i(TAG, "Cleared $cleared: $ok")
                }
                asked++
            } catch (e: Exception) {
                Log.w(TAG, "Could not clear $pkg: ${e.message}")
            }
        }
        try {
            (app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.clearPrimaryClip()
        } catch (_: Exception) {
        }
        DiagnosticsLog.add("Guest reset", "Asked $asked apps to clear their data and cleared the clipboard")
        return asked
    }
}
