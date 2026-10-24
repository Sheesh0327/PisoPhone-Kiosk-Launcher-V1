package com.pisophone.kiosk.security

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import com.pisophone.kiosk.receiver.KioskDeviceAdminReceiver

/**
 * Makes the phone's app list look like a finished product: only apps somebody installed (from Play Store) are shown.
 *
 * Every pre-installed app that has a launcher icon is either
 *  - disabled ([Action.DISABLE], device owner `setApplicationHidden`: it disappears everywhere and stops running), or
 *  - only hidden from the kiosk launcher ([Action.HIDE_IN_LAUNCHER]) when the phone needs it (home, dialer, keyboard, web view,
 *    Google services, the phone vendor's own framework apps, ...).
 * Apps the user installed are never touched. Everything done here is recorded so [restoreAll] can undo it: it runs on a
 * de-provision, and an admin can ask for it (RESTORE_SYSTEM_APPS).
 */
object AppVisibilityPolicy {
    private const val TAG = "AppVisibilityPolicy"
    private const val PREFS = "kiosk_app_visibility"
    private const val KEY_DISABLED = "disabled_apps"
    private const val KEY_HIDDEN = "hidden_in_launcher"
    private const val KEY_GOOGLE_REINSTATED = "google_apps_reinstated"

    enum class Action { KEEP_VISIBLE, HIDE_IN_LAUNCHER, DISABLE }

    /** Exact package names, or prefixes (ending in '.'), that are only ever hidden, never disabled. */
    private val NEVER_DISABLE = listOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.google.android.settings",
        "com.android.vending",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.providers.",
        "com.android.bluetooth",
        "com.android.nfc",
        "com.android.shell",
        "com.android.keychain",
        "com.android.certinstaller",
        "com.android.location.fused",
        "com.android.inputmethod.",
        "com.google.android.inputmethod.",
        "com.android.webview",
        "com.google.android.webview",
        "com.android.managedprovisioning",
        "com.android.networkstack",
        "com.google.android.networkstack",
        "com.android.captiveportallogin",
        "com.google.android.captiveportallogin",
        "com.android.cellbroadcastreceiver",
        "com.google.android.cellbroadcastreceiver",
        "com.android.emergency",
        "com.android.stk",
        "com.android.carrierconfig",
        "com.android.ons",
        "com.android.documentsui",
        "com.google.android.documentsui",
        "com.android.externalstorage",
        "com.android.mtp",
        "com.google.android.ext.",
        "com.google.android.tts", // the kiosk speaks through the text-to-speech engine
        // A phone vendor's own apps are mixed up with its framework: never disabled, only hidden.
        "com.samsung.",
        "com.sec.",
        "com.miui.",
        "com.xiaomi.",
        "com.huawei.",
        "com.hihonor.",
        "com.oppo.",
        "com.coloros.",
        "com.heytap.",
        "com.vivo.",
        "com.oneplus.",
        "com.motorola.",
        "com.mediatek.",
        "com.qualcomm.",
        "com.qti.",
        "vendor.",
    )

    /**
     * Google's own apps the owner wants customers to keep: YouTube, Chrome, Maps, Photos, Gmail, Drive and the rest of the
     * `com.google.android.apps.` family (feature 029). They are never disabled and never hidden. Exact names, or prefixes ending in '.'.
     */
    private val KEEP_GOOGLE = listOf(
        "com.google.android.youtube",
        "com.google.android.gm",
        "com.android.chrome",
        "com.google.android.googlequicksearchbox",
        "com.google.android.videos",
        "com.google.android.music",
        "com.google.android.keep",
        "com.google.android.apps.",
    )

    /** Google apps that are settings or device management, not apps for customers: they stay out of sight as before. */
    private val GOOGLE_NOT_FOR_CUSTOMERS = listOf(
        "com.google.android.apps.wellbeing",
        "com.google.android.apps.turbo",
        "com.google.android.apps.pixel.",
        "com.google.android.apps.restore",
        "com.google.android.apps.setupwizard",
        "com.google.android.apps.work.",
        "com.google.android.apps.tips",
        "com.google.android.apps.safetyhub",
        "com.google.android.apps.privacy.",
        "com.google.android.apps.carrier.",
        "com.google.android.apps.stargate",
    )

    private fun matches(packageName: String, list: List<String>) =
        list.any { packageName == it || (it.endsWith(".") && packageName.startsWith(it)) }

    /** True for a Google app customers should keep (YouTube, Chrome, Maps, Photos...). */
    fun isKeptGoogleApp(packageName: String): Boolean =
        matches(packageName, KEEP_GOOGLE) && !matches(packageName, GOOGLE_NOT_FOR_CUSTOMERS)

    fun isNeverDisabled(packageName: String): Boolean =
        NEVER_DISABLE.any { packageName == it || (it.endsWith(".") && packageName.startsWith(it)) }

    /** What to do with one app that has a launcher icon. Pure, so it is tested without a phone. */
    fun decide(packageName: String, isSystem: Boolean, isCritical: Boolean, ownPackage: String): Action = when {
        packageName == ownPackage -> Action.KEEP_VISIBLE
        !isSystem -> Action.KEEP_VISIBLE
        isCritical -> Action.HIDE_IN_LAUNCHER
        isKeptGoogleApp(packageName) -> Action.KEEP_VISIBLE
        isNeverDisabled(packageName) -> Action.HIDE_IN_LAUNCHER
        else -> Action.DISABLE
    }

    data class Result(val disabled: List<String>, val hidden: List<String>, val failed: List<String>)

    private fun prefs(context: Context) = KioskSecurity.getDirectBootPrefs(context.applicationContext ?: context, PREFS)

    private fun admin(context: Context): Pair<DevicePolicyManager, ComponentName>? {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return null
        if (!dpm.isDeviceOwnerApp(context.packageName)) return null
        return dpm to ComponentName(context, KioskDeviceAdminReceiver::class.java)
    }

    /** Packages the phone needs to keep running: they are hidden at most, never disabled. */
    private fun criticalPackages(context: Context): Set<String> {
        val pm = context.packageManager
        val out = mutableSetOf<String>()
        fun add(name: String?) {
            if (!name.isNullOrBlank()) out.add(name.substringBefore('/'))
        }
        fun attempt(what: String, block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "Could not read $what: ${e.message}")
            }
        }
        // every home screen (the stock one is the fallback if the kiosk ever fails)
        attempt("home apps") {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .forEach { add(it.activityInfo.packageName) }
        }
        attempt("dialer") { add((context.getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager)?.defaultDialerPackage) }
        attempt("sms app") { add(android.provider.Telephony.Sms.getDefaultSmsPackage(context)) }
        attempt("keyboards") {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.enabledInputMethodList?.forEach { add(it.packageName) }
            add(Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD))
        }
        attempt("web view") { add(WebView.getCurrentWebViewPackage()?.packageName) }
        attempt("assistant") { add(Settings.Secure.getString(context.contentResolver, "assistant")) }
        attempt("accessibility services") {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')?.forEach { add(it) }
        }
        attempt("device admins") {
            (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager)?.activeAdmins?.forEach { add(it.packageName) }
        }
        return out
    }

    private fun isCriticalApp(info: ApplicationInfo, critical: Set<String>): Boolean =
        info.packageName in critical ||
            (info.flags and ApplicationInfo.FLAG_PERSISTENT) != 0 ||
            info.uid == Process.SYSTEM_UID

    /**
     * Applies the policy to every app with a launcher icon. Safe to repeat. Needs the device owner; returns null on a phone
     * where the app is not the owner (nothing is changed there).
     */
    fun apply(context: Context): Result? {
        val (dpm, component) = admin(context) ?: return null
        val pm = context.packageManager
        val critical = criticalPackages(context)
        val launcherApps = try {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { it.activityInfo.applicationInfo }
        } catch (e: Exception) {
            Log.w(TAG, "Could not list the apps: ${e.message}")
            return null
        }

        val disabled = mutableListOf<String>()
        val hidden = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (info in launcherApps.distinctBy { it.packageName }) {
            val isSystem = (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
            when (decide(info.packageName, isSystem, isCriticalApp(info, critical), context.packageName)) {
                Action.KEEP_VISIBLE -> Unit
                Action.HIDE_IN_LAUNCHER -> hidden.add(info.packageName)
                Action.DISABLE -> {
                    val ok = try {
                        dpm.setApplicationHidden(component, info.packageName, true)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not disable ${info.packageName}: ${e.message}")
                        false
                    }
                    // an app that cannot be disabled is hidden from the launcher instead
                    if (ok) {
                        disabled.add(info.packageName)
                    } else { failed.add(info.packageName); hidden.add(info.packageName) }
                }
            }
        }

        val alreadyHidden = KioskSecurity.getHiddenApps(context)
        val newlyHidden = hidden.filter { it !in alreadyHidden }
        if (newlyHidden.isNotEmpty()) KioskSecurity.setHiddenApps(context, alreadyHidden + newlyHidden)

        val p = prefs(context)
        p.edit()
            .putStringSet(KEY_DISABLED, (p.getStringSet(KEY_DISABLED, emptySet()).orEmpty()) + disabled)
            .putStringSet(KEY_HIDDEN, (p.getStringSet(KEY_HIDDEN, emptySet()).orEmpty()) + newlyHidden)
            .commit()
        Log.i(TAG, "First-boot app clean-up: ${disabled.size} disabled, ${hidden.size} hidden from the launcher, ${failed.size} could not be disabled.")
        return Result(disabled, hidden, failed)
    }

    /**
     * A phone cleaned up before feature 029 has YouTube, Chrome, Maps, Photos... switched off. Brings them back once: re-enables the Google apps
     * [apply] disabled and shows again those it hid (unless the phone needs them in the background: [criticalPackages]). Safe to repeat.
     */
    fun reinstateGoogleApps(context: Context): Int {
        val p = prefs(context)
        if (p.getBoolean(KEY_GOOGLE_REINSTATED, false)) return 0
        val adminPair = admin(context) ?: return 0
        val (dpm, component) = adminPair
        val critical = criticalPackages(context)
        val disabled = p.getStringSet(KEY_DISABLED, emptySet()).orEmpty()
        val hidden = p.getStringSet(KEY_HIDDEN, emptySet()).orEmpty()
        val back = disabled.filter { isKeptGoogleApp(it) && it !in critical }
        for (pkg in back) {
            try {
                dpm.setApplicationHidden(component, pkg, false)
            } catch (e: Exception) {
                Log.w(TAG, "Could not re-enable $pkg: ${e.message}")
            }
        }
        val shown = hidden.filter { isKeptGoogleApp(it) && it !in critical }
        if (shown.isNotEmpty()) KioskSecurity.setHiddenApps(context, KioskSecurity.getHiddenApps(context) - shown.toSet())
        p.edit()
            .putStringSet(KEY_DISABLED, disabled - back.toSet())
            .putStringSet(KEY_HIDDEN, hidden - shown.toSet())
            .putBoolean(KEY_GOOGLE_REINSTATED, true)
            .commit()
        Log.i(TAG, "Brought back ${back.size} Google apps and showed ${shown.size} again.")
        return back.size + shown.size
    }

    /** Undoes [apply]: re-enables what it disabled and shows again what it hid. Run before the device owner is removed. */
    fun restoreAll(context: Context) {
        val p = prefs(context)
        val disabled = p.getStringSet(KEY_DISABLED, emptySet()).orEmpty()
        val hidden = p.getStringSet(KEY_HIDDEN, emptySet()).orEmpty()
        admin(context)?.let { (dpm, component) ->
            for (pkg in disabled) {
                try {
                    dpm.setApplicationHidden(component, pkg, false)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not re-enable $pkg: ${e.message}")
                }
            }
        }
        if (hidden.isNotEmpty()) KioskSecurity.setHiddenApps(context, KioskSecurity.getHiddenApps(context) - hidden)
        p.edit().remove(KEY_DISABLED).remove(KEY_HIDDEN).commit()
        Log.i(TAG, "Restored ${disabled.size} disabled and ${hidden.size} hidden apps.")
    }
}
