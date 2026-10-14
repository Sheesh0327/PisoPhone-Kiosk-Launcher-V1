package com.pisophone.kiosk.util

import android.content.Context

/**
 * Remembers when the app crashed, so a crash that keeps repeating does not become a restart storm: the first crashes restart the
 * app after a second, a third crash within ten minutes waits 30 seconds, a fifth waits two minutes. The crash times also give the
 * "restarts in the last 24 hours" count the owner sees.
 */
object CrashLoop {
    private const val PREFS = "crash_guard"
    private const val KEY_TIMES = "times"
    const val LOOP_WINDOW_MS = 10L * 60L * 1000L
    const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val MAX_KEPT = 50

    /** How long to wait before restarting after the latest crash, given how many crashes there were in the last ten minutes. */
    fun restartDelayMs(crashesInWindow: Int): Long = when {
        crashesInWindow >= 5 -> 120_000L
        crashesInWindow >= 3 -> 30_000L
        else -> 1_000L
    }

    /** The crash times (milliseconds since 1970) still worth keeping: the last 24 hours, newest last. */
    fun parse(stored: String?, now: Long): List<Long> =
        (stored ?: "").split(',').mapNotNull { it.trim().toLongOrNull() }.filter { now - it in 0..DAY_MS }.takeLast(MAX_KEPT)

    fun countWithin(times: List<Long>, now: Long, windowMs: Long): Int = times.count { now - it in 0..windowMs }

    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Notes a crash now and returns how long to wait before the restart. Safe to call from a crash handler. */
    fun recordCrash(context: Context, now: Long = System.currentTimeMillis()): Long = try {
        val times = parse(prefs(context).getString(KEY_TIMES, ""), now) + now
        prefs(context).edit().putString(KEY_TIMES, times.joinToString(",")).commit()
        restartDelayMs(countWithin(times, now, LOOP_WINDOW_MS))
    } catch (e: Exception) {
        restartDelayMs(0)
    }

    /** Crashes (so restarts) in the last 24 hours. */
    fun restartsLast24h(context: Context, now: Long = System.currentTimeMillis()): Int =
        try {
            parse(prefs(context).getString(KEY_TIMES, ""), now).size
        } catch (e: Exception) {
            0
        }

    /** The crashes of the last ten minutes, for the diagnostics line after a restart. */
    fun recentCrashes(context: Context, now: Long = System.currentTimeMillis()): Int =
        try {
            countWithin(parse(prefs(context).getString(KEY_TIMES, ""), now), now, LOOP_WINDOW_MS)
        } catch (e: Exception) {
            0
        }
}
