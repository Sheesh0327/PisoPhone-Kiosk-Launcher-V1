package com.pisophone.kiosk.security

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.util.Log
import com.pisophone.kiosk.provisioning.OverlayPermissionStep
import com.pisophone.kiosk.util.AppLauncher

/**
 * What a freshly set-up phone does on its first start, once the kiosk may draw over other apps:
 *  1. Play Store opens so the admin can sign in (the kiosk overlays are out of the way, see [AdminMaintenanceMode]).
 *  2. When the admin comes back to the kiosk screen, [AppVisibilityPolicy] disables or hides the pre-installed apps so only
 *     apps somebody installed show, and the admin-only apps are locked away again.
 *
 * Phones that were already in use when this version arrived are never touched: they are marked done the first time they
 * are seen. The step survives a restart (it is stored, not remembered).
 */
object FirstBootSetup {
    private const val TAG = "FirstBootSetup"
    private const val PREFS = "kiosk_first_boot"
    private const val KEY_STEP = "step"
    private const val KEY_LEFT_KIOSK = "left_kiosk_screen"

    /** A phone whose app was installed longer ago than this was set up before this feature: leave it alone. */
    const val FRESH_INSTALL_MAX_AGE_MS = 3L * 60 * 60 * 1000

    /** Long enough to sign in to Google; it ends earlier when the admin returns to the kiosk screen. */
    const val SIGN_IN_WINDOW_SECONDS = 30 * 60

    private const val PLAY_STORE = "com.android.vending"

    enum class Step(val code: Int) {
        UNSEEN(0), // not looked at yet
        PENDING(1), // a fresh phone: Play Store sign-in has not started
        SIGNING_IN(2), // Play Store was opened
        DONE(3), // nothing (more) to do
        ;

        companion object {
            fun fromCode(code: Int): Step = entries.firstOrNull { it.code == code } ?: UNSEEN
        }
    }

    /** Fresh phone or phone already in use? Pure, so it is tested without a phone. */
    fun classify(installAgeMs: Long): Step =
        if (installAgeMs in 0..FRESH_INSTALL_MAX_AGE_MS) Step.PENDING else Step.DONE

    private fun prefs(context: Context) = KioskSecurity.getDirectBootPrefs(context.applicationContext ?: context, PREFS)

    fun step(context: Context): Step = Step.fromCode(prefs(context).getInt(KEY_STEP, Step.UNSEEN.code))

    private fun setStep(context: Context, step: Step) {
        prefs(context).edit().putInt(KEY_STEP, step.code).commit()
    }

    private fun installAgeMs(context: Context): Long = try {
        System.currentTimeMillis() - context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
    } catch (e: Exception) {
        Long.MAX_VALUE
    }

    /**
     * Called whenever the kiosk screen resumes. Starts the Play Store sign-in on a fresh phone that is the device owner and
     * has the overlay permission; does nothing otherwise.
     */
    fun maybeStart(context: Context) {
        val app = context.applicationContext ?: context
        var current = step(app)
        if (current == Step.UNSEEN) {
            current = classify(installAgeMs(app))
            setStep(app, current)
            Log.i(TAG, "First look at this phone: $current")
        }
        if (current != Step.PENDING) return
        val dpm = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        if (dpm?.isDeviceOwnerApp(app.packageName) != true || OverlayPermissionStep.isNeeded(app)) return

        setStep(app, Step.SIGNING_IN)
        prefs(app).edit().putBoolean(KEY_LEFT_KIOSK, false).commit()
        AdminMaintenanceMode.begin(app, SIGN_IN_WINDOW_SECONDS)
        if (!AppLauncher.launchApp(app, PLAY_STORE)) {
            Log.w(TAG, "Play Store could not be opened: finishing the first-boot set-up without a sign-in step.")
            complete(app)
        }
    }

    /** The kiosk screen went away (Play Store is in front). */
    fun onKioskScreenLeft(context: Context) {
        val app = context.applicationContext ?: context
        if (step(app) == Step.SIGNING_IN) prefs(app).edit().putBoolean(KEY_LEFT_KIOSK, true).commit()
    }

    /** The admin is back on the kiosk screen after having been away: the sign-in is over. */
    fun onKioskScreenReturned(context: Context) {
        val app = context.applicationContext ?: context
        if (step(app) == Step.SIGNING_IN && prefs(app).getBoolean(KEY_LEFT_KIOSK, false)) complete(app)
    }

    /** Cleans up the app list and locks the admin-only apps away again. Never runs twice. */
    fun complete(context: Context) {
        val app = context.applicationContext ?: context
        if (step(app) == Step.DONE) return
        setStep(app, Step.DONE)
        Thread {
            try {
                AppVisibilityPolicy.apply(app)
            } catch (e: Exception) {
                Log.e(TAG, "App clean-up failed: ${e.message}", e)
            }
            AdminMaintenanceMode.end(app)
            Log.i(TAG, "First-boot set-up finished.")
        }.start()
    }

    /** An admin restored the system apps: do not clean them up again by itself. */
    fun markDone(context: Context) = setStep(context.applicationContext ?: context, Step.DONE)
}
