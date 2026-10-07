package com.pisophone.kiosk.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

/**
 * Short on-screen status lines for the people setting a phone up: is it on the kiosk Wi-Fi, can it search for its box, did it
 * find it. The same line is not shown again until [repeatAfterMs] has passed, so the background checks that run every few
 * seconds do not flood the screen. Safe to call from any thread.
 */
object KioskStatusToast {
    private const val TAG = "KioskStatus"
    private const val PREFIX = "PisoPhone: "
    private val main by lazy { Handler(Looper.getMainLooper()) } // (lazy: the throttle is unit-tested without Android)
    private val lastShown = HashMap<String, Long>()

    fun show(context: Context, message: String, repeatAfterMs: Long = 60_000L, nowMs: Long = System.currentTimeMillis()) {
        if (!shouldShow(message, repeatAfterMs, nowMs)) return
        Log.i(TAG, message)
        val app = context.applicationContext
        main.post {
            try {
                Toast.makeText(app, PREFIX + message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.w(TAG, "Could not show a status toast: ${e.message}")
            }
        }
    }

    @Synchronized
    internal fun shouldShow(message: String, repeatAfterMs: Long, nowMs: Long): Boolean {
        val last = lastShown[message]
        if (last != null && nowMs - last < repeatAfterMs) return false
        lastShown[message] = nowMs
        return true
    }

    @Synchronized
    internal fun reset() = lastShown.clear()
}
