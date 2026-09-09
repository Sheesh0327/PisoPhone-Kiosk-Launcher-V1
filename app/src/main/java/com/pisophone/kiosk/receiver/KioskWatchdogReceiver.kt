package com.pisophone.kiosk.receiver

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.pisophone.kiosk.KioskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

class KioskWatchdogReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "KioskWatchdog"
        const val ACTION_WATCHDOG_PING = "com.pisophone.kiosk.action.WATCHDOG_PING"
        private const val WATCHDOG_INTERVAL_MS = 300_000L // Check every 5 minutes

        fun scheduleWatchdog(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val intent = Intent(context, KioskWatchdogReceiver::class.java).apply {
                    action = ACTION_WATCHDOG_PING
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = PendingIntent.getBroadcast(context, 1001, intent, flags)

                val triggerAt = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, triggerAt, pendingIntent)
                } else {
                    alarmManager.set(AlarmManager.ELAPSED_REALTIME, triggerAt, pendingIntent)
                }
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
        val serviceInstance = KioskService.activeInstance
        val isProcessRunning = KioskService.isServiceRunning || isServiceRunning(context, KioskService::class.java)
        
        // 1. Local App Loopback Health Check
        val isHttpHealthy = try {
            val url = URL("http://127.0.0.1:8080/challenge")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.requestMethod = "GET"
            val code = connection.responseCode
            connection.disconnect()
            code == 200
        } catch (e: Exception) {
            Log.w(TAG, "Local loopback HTTP check failed: ${e.message}")
            false
        }

        // 2. Overlay Attachment Health Check
        val isOverlayHealthy = serviceInstance?.isOverlayHealthy() ?: false

        if (!isProcessRunning || !isHttpHealthy || !isOverlayHealthy) {
            val isFullySetup = com.pisophone.kiosk.security.HardwareLockManager.isAppAllowedToRun(context)
            if (!isFullySetup) {
                Log.d(TAG, "Device not yet fully setup/activated. Watchdog skipping KioskService start.")
                return
            }

            if (isProcessRunning && serviceInstance != null && isHttpHealthy && !isOverlayHealthy) {
                Log.w(TAG, "KioskService process & HTTP server are running, but overlay is missing/unattached! Rebuilding overlay immediately...")
                serviceInstance.setupOverlay()
            } else {
                Log.w(TAG, "KioskService is NOT healthy (Process: $isProcessRunning, HTTP: $isHttpHealthy, Overlay: $isOverlayHealthy)! Reviving foreground service immediately...")
                val serviceIntent = Intent(context, KioskService::class.java)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to revive KioskService: ${e.message}")
                }
            }
        } else {
            Log.d(TAG, "KioskService is alive and healthy (HTTP 200 OK & Overlay Attached).")
        }
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        try {
            for (service in manager.getRunningServices(Int.MAX_VALUE)) {
                if (serviceClass.name == service.service.className) {
                    return true
                }
            }
        } catch (e: Exception) {
            // Fallback for newer Android versions with restricted getRunningServices:
            // Safely attempt to start service which is idempotent
            return false
        }
        return false
    }
}
