package com.pisophone.kiosk

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL

class CrashReporter(private val context: Context) : Thread.UncaughtExceptionHandler {
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            e.printStackTrace(pw)
            val crashLog = sw.toString()
            Log.e("CrashReporter", "Uncaught exception detected: $crashLog")

            // 1. Write crash log locally to disk for diagnosis
            try {
                val logDir = context.getExternalFilesDir(null) ?: context.filesDir
                val file = File(logDir, "crash.log")
                FileOutputStream(file).use { fos ->
                    fos.write(crashLog.toByteArray())
                }
            } catch (ex: Exception) {
                Log.e("CrashReporter", "Failed to write crash log to file: ${ex.message}")
            }

            // 2. Telemetry: Send to dynamically discovered ESP32 Master controller
            try {
                val deviceContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) context.createDeviceProtectedStorageContext() else context
                val prefs = deviceContext.getSharedPreferences("kiosk_persistent_state", Context.MODE_PRIVATE)
                val esp32Ip = prefs.getString("esp32_ip", null)
                val targetPort = prefs.getInt("target_port", 80)

                if (!esp32Ip.isNullOrBlank()) {
                    val url = URL("http://$esp32Ip:$targetPort/crash_report")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 2000
                    conn.readTimeout = 2000
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "text/plain")
                    conn.doOutput = true
                    conn.outputStream.use { os ->
                        os.write(crashLog.toByteArray())
                        os.flush()
                    }
                    conn.responseCode // execute request
                    conn.disconnect()
                }
            } catch (ex: Exception) {
                Log.w("CrashReporter", "Remote crash report to ESP32 failed gracefully: ${ex.message}")
            }

            // 3. Auto-Relaunch Kiosk: Schedule immediate revive via AlarmManager in 1000ms
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
                val intent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    android.app.PendingIntent.FLAG_CANCEL_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                } else {
                    android.app.PendingIntent.FLAG_CANCEL_CURRENT
                }
                val pendingIntent = android.app.PendingIntent.getActivity(context, 9999, intent, flags)
                val restartTime = SystemClock.elapsedRealtime() + 1000L
                alarmManager?.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, restartTime, pendingIntent)
                Log.d("CrashReporter", "Scheduled auto-restart revive in 1s.")
            } catch (ex: Exception) {
                Log.e("CrashReporter", "Failed to schedule auto-restart: ${ex.message}")
            }
        } catch (ex: Exception) {
            // Guarantee handler never crashes
        }
        
        defaultHandler?.uncaughtException(t, e)
    }

    companion object {
        fun init(context: Context) {
            Thread.setDefaultUncaughtExceptionHandler(CrashReporter(context))
        }
    }
}
