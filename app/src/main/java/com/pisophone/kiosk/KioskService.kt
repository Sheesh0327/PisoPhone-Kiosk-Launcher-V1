package com.pisophone.kiosk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pisophone.kiosk.receiver.KioskWatchdogReceiver
import com.pisophone.kiosk.security.KioskActivationManager
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.service.KioskEngine
import com.pisophone.kiosk.service.KioskStateManager

/**
 * Foreground Service wrapper maintaining Android process priority and wake locks.
 * Business logic, timers, networking, and UI overlays are delegated to [KioskEngine].
 */
class KioskService : Service() {
    companion object {
        private const val TAG = "KioskService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "kiosk_channel"

        const val ACTION_ADMIN_BYPASS = "com.pisophone.kiosk.ADMIN_BYPASS"
        const val ACTION_TEST_TTS = "com.pisophone.kiosk.TEST_TTS"
        const val ACTION_LOCK_SESSION = "com.pisophone.kiosk.LOCK_SESSION"

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        @Volatile
        var activeInstance: KioskService? = null
            private set

        fun configureMasterBox(
            context: Context,
            mac: String,
            slot: Int = -1,
            secret: String? = null,
            name: String? = null
        ) {
            KioskSecurity.applyDirectProvisioning(
                context = context,
                secret = secret,
                mac = mac,
                slot = slot,
                name = name
            )
            KioskActivationManager.setPairingCompleted(context, true)
            val cleanMac = KioskSecurity.formatMacAddress(mac)
            activeInstance?.let { service ->
                if (cleanMac.isNotBlank()) {
                    service.stateManager.esp32MacAddress.value = cleanMac
                }
                service.engine?.triggerDirectPairing(targetMac = cleanMac)
            }
        }

        fun triggerDirectPairing(context: Context, ip: String? = null, mac: String? = null) {
            activeInstance?.engine?.triggerDirectPairing(ip, mac)
        }

        fun updateConfiguredEsp32Mac(context: Context, mac: String): Boolean {
            val clean = KioskSecurity.formatMacAddress(mac)
            if (clean.isNotBlank()) {
                KioskSecurity.setConfiguredEsp32Mac(context, clean)
                activeInstance?.let { service ->
                    service.stateManager.esp32MacAddress.value = clean
                    service.engine?.triggerDirectPairing(targetMac = clean)
                }
                return true
            }
            return false
        }

        fun updateConfiguredEsp32Ip(context: Context, ip: String): Boolean {
            val valid = KioskSecurity.setConfiguredEsp32Ip(context, ip)
            if (valid) {
                activeInstance?.engine?.updateEsp32StaticIp(ip)
            }
            return valid
        }

        fun triggerAdminBypass(context: Context, durationSeconds: Int = 900) {
            val instance = activeInstance
            if (instance != null) {
                instance.performAdminBypass(durationSeconds)
            } else {
                val intent = Intent(context, KioskService::class.java).apply {
                    action = ACTION_ADMIN_BYPASS
                    putExtra("duration", durationSeconds)
                }
                startServiceCompat(context, intent)
            }
        }

        fun triggerLockSession(context: Context) {
            val instance = activeInstance
            if (instance != null) {
                instance.performLockSession()
            } else {
                val intent = Intent(context, KioskService::class.java).apply {
                    action = ACTION_LOCK_SESSION
                }
                startServiceCompat(context, intent)
            }
        }

        fun triggerTestTts(context: Context, text: String = "PisoPhone voice system online and functional.") {
            val instance = activeInstance
            if (instance != null) {
                instance.speakWarning(text)
            } else {
                val intent = Intent(context, KioskService::class.java).apply {
                    action = ACTION_TEST_TTS
                    putExtra("text", text)
                }
                startServiceCompat(context, intent)
            }
        }

        private fun startServiceCompat(context: Context, intent: Intent) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed startServiceCompat: ${e.message}")
            }
        }
    }

    private var engine: KioskEngine? = null
    val stateManager: KioskStateManager by lazy { KioskStateManager(applicationContext) }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        activeInstance = this

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kiosk Active")
            .setContentText("Monitoring coin slot on port 8080")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        CrashReporter.init(this)
        acquireLocks()

        val eng = KioskEngine(this, stateManager)
        engine = eng
        eng.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        KioskWatchdogReceiver.scheduleWatchdog(this)

        when (intent?.action) {
            ACTION_ADMIN_BYPASS -> {
                val duration = intent.getIntExtra("duration", 900)
                performAdminBypass(duration)
            }
            ACTION_LOCK_SESSION -> {
                performLockSession()
            }
            ACTION_TEST_TTS -> {
                val text = intent.getStringExtra("text") ?: "PisoPhone voice system online and functional."
                speakWarning(text)
            }
        }

        if (Settings.canDrawOverlays(this)) {
            engine?.overlayCoordinator?.show()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun isOverlayHealthy(): Boolean = engine?.overlayCoordinator?.isOverlayHealthy() ?: false

    fun ensureHttpServerRunning(): Boolean = engine?.ensureHttpServerRunning() ?: false

    fun setupOverlay() {
        engine?.overlayCoordinator?.setupOverlay()
    }

    fun performAdminBypass(durationSeconds: Int = 900) {
        engine?.performAdminBypass(durationSeconds)
    }

    fun performLockSession() {
        engine?.performLockSession()
    }

    fun speakWarning(text: String) {
        engine?.speakWarning(text)
    }

    fun triggerDirectPairing(ip: String? = null, mac: String? = null) {
        engine?.triggerDirectPairing(ip, mac)
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pisophone:kiosk_service_wakelock")?.apply {
                acquire()
            }
            Log.d(TAG, "[+] Acquired Partial WakeLock for reliable kiosk background operations.")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Kiosk Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        activeInstance = null
        engine?.stop()
        engine = null
        releaseLocks()
        KioskWatchdogReceiver.scheduleWatchdog(applicationContext)
    }
}
