package com.pisophone.kiosk.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.pisophone.kiosk.KioskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class KioskWatchdogReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "KioskWatchdog"
        const val ACTION_WATCHDOG_PING = "com.pisophone.kiosk.action.WATCHDOG_PING"
        private const val WATCHDOG_INTERVAL_MS = 60_000L // Alarm backup check every 60 seconds

        fun scheduleWatchdog(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val intent = Intent(context, KioskWatchdogReceiver::class.java).apply {
                    action = ACTION_WATCHDOG_PING
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val pendingIntent = PendingIntent.getBroadcast(context, 1001, intent, flags)

                val triggerAt = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, triggerAt, pendingIntent)
                Log.d(TAG, "Watchdog alarm scheduled for +${WATCHDOG_INTERVAL_MS / 1000}s")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule watchdog: ${e.message}")
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(TAG, "Watchdog ping received. Inspecting KioskService health...")
        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                ensureKioskServiceRunningAsync(appContext)
                scheduleWatchdog(appContext) // Rearm for next check
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun ensureKioskServiceRunningAsync(context: Context) {
        // One path whether the service is alive or dead: starting it revives it, and an already running service
        // repairs its own HTTP listener and overlay when it receives the health check.
        Log.d(TAG, "Sending health check to KioskService (revives it if it is not running)")
        KioskService.requestHealthCheck(context)
    }
}
