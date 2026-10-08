package com.pisophone.kiosk.security

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Time-limited admin maintenance window opened by an admin bypass. While it is active the
 * admin-only packages (Settings, Play Store, package installer) are allowed in lock task mode
 * and Wi-Fi configuration is unblocked; both are revoked again when the window ends or the
 * session locks. The deadline is persisted so a process restart cannot leave access open.
 *
 * Play Store, Settings and the package installer ignore touches (and Play Store reports "screen overlay detected") while another
 * app has a window over them, even an invisible full-screen one. So every kiosk overlay window (lock screen and floating pill)
 * is taken off the screen ([suspendOverlays]) from the moment the admin opens one of those apps until the admin is back on the
 * kiosk's own screen or the window ends. An admin bypass that never opens such an app leaves the overlays alone.
 */
object AdminMaintenanceMode {
    private const val TAG = "AdminMaintenance"
    private const val PREFS_NAME = "kiosk_admin_maintenance"
    private const val KEY_UNTIL_MS = "maintenance_until_wall_ms"

    private val handler = Handler(Looper.getMainLooper())
    private var endRunnable: Runnable? = null

    private var windowOpen = false
    private var adminAppOpened = false
    private var kioskScreenInFront = true
    private val _suspendOverlays = MutableStateFlow(false)

    /** True while no kiosk overlay window may be on screen (an admin-only app is in front during a maintenance window). */
    val suspendOverlays: StateFlow<Boolean> = _suspendOverlays

    private fun refreshSuspendOverlays() {
        _suspendOverlays.value = windowOpen && adminAppOpened && !kioskScreenInFront
    }

    /** Called by the kiosk's main screen when it becomes visible (true) or leaves the front (false). */
    fun setKioskScreenInFront(inFront: Boolean) {
        synchronized(this) {
            if (inFront && !kioskScreenInFront) adminAppOpened = false // the admin is back: the pill returns
            kioskScreenInFront = inFront
            refreshSuspendOverlays()
        }
    }

    /** The admin is opening Play Store, Settings or the package installer from the kiosk. */
    fun noteAdminAppOpened() {
        synchronized(this) {
            adminAppOpened = true
            refreshSuspendOverlays()
        }
    }

    private fun setWindowOpen(open: Boolean) {
        synchronized(this) {
            windowOpen = open
            if (!open) adminAppOpened = false
            refreshSuspendOverlays()
        }
    }

    private fun prefs(context: Context) =
        KioskSecurity.getDirectBootPrefs(context.applicationContext ?: context, PREFS_NAME)

    fun isActive(context: Context): Boolean =
        prefs(context).getLong(KEY_UNTIL_MS, 0L) > System.currentTimeMillis()

    fun begin(context: Context, durationSeconds: Int) {
        val app = context.applicationContext ?: context
        val requested = System.currentTimeMillis() + durationSeconds.coerceAtLeast(1) * 1000L
        // Never shorten a window that is already open (e.g. a 5 min bypass inside a 15 min one).
        val until = maxOf(requested, prefs(app).getLong(KEY_UNTIL_MS, 0L))
        prefs(app).edit().putLong(KEY_UNTIL_MS, until).commit()
        setWindowOpen(true)
        KioskPolicyManager.applyMaintenanceAccess(app)
        scheduleEnd(app)
        Log.i(TAG, "Admin maintenance window open for ${durationSeconds}s.")
    }

    fun end(context: Context) {
        val app = context.applicationContext ?: context
        val p = prefs(app)
        if (p.getLong(KEY_UNTIL_MS, 0L) == 0L) return
        p.edit().putLong(KEY_UNTIL_MS, 0L).commit()
        setWindowOpen(false)
        synchronized(this) {
            endRunnable?.let { handler.removeCallbacks(it) }
            endRunnable = null
        }
        KioskPolicyManager.applyMaintenanceAccess(app)
        Log.i(TAG, "Admin maintenance window closed.")
    }

    /** Re-arms the end timer after a process restart while a window is still open. */
    fun scheduleEnd(context: Context) {
        val app = context.applicationContext ?: context
        val remaining = prefs(app).getLong(KEY_UNTIL_MS, 0L) - System.currentTimeMillis()
        if (remaining <= 0L) return
        setWindowOpen(true)
        synchronized(this) {
            endRunnable?.let { handler.removeCallbacks(it) }
            val r = Runnable { end(app) }
            endRunnable = r
            handler.postDelayed(r, remaining + 500L)
        }
    }
}
