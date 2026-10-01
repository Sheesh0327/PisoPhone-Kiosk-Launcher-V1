package com.pisophone.kiosk.security

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Time-limited admin maintenance window opened by an admin bypass. While it is active the
 * admin-only packages (Settings, Play Store, package installer) are allowed in lock task mode
 * and Wi-Fi configuration is unblocked; both are revoked again when the window ends or the
 * session locks. The deadline is persisted so a process restart cannot leave access open.
 */
object AdminMaintenanceMode {
    private const val TAG = "AdminMaintenance"
    private const val PREFS_NAME = "kiosk_admin_maintenance"
    private const val KEY_UNTIL_MS = "maintenance_until_wall_ms"

    private val handler = Handler(Looper.getMainLooper())
    private var endRunnable: Runnable? = null

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
        KioskPolicyManager.applyMaintenanceAccess(app)
        scheduleEnd(app)
        Log.i(TAG, "Admin maintenance window open for ${durationSeconds}s.")
    }

    fun end(context: Context) {
        val app = context.applicationContext ?: context
        val p = prefs(app)
        if (p.getLong(KEY_UNTIL_MS, 0L) == 0L) return
        p.edit().putLong(KEY_UNTIL_MS, 0L).commit()
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
        synchronized(this) {
            endRunnable?.let { handler.removeCallbacks(it) }
            val r = Runnable { end(app) }
            endRunnable = r
            handler.postDelayed(r, remaining + 500L)
        }
    }
}
