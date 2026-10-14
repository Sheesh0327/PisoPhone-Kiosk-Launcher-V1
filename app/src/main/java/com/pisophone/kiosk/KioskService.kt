package com.pisophone.kiosk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pisophone.kiosk.receiver.KioskWatchdogReceiver
import com.pisophone.kiosk.security.AutoUpdatePolicy
import com.pisophone.kiosk.security.KioskActivationManager
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.security.KioskUpdateManager
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
        private const val WIFI_CHECK_MS = 20_000L
        private const val WIFI_REACT_MIN_GAP_MS = 5_000L
        private const val CHANNEL_ID = "kiosk_channel"

        const val ACTION_ADMIN_BYPASS = "com.pisophone.kiosk.ADMIN_BYPASS"
        const val ACTION_TEST_TTS = "com.pisophone.kiosk.TEST_TTS"
        const val ACTION_LOCK_SESSION = "com.pisophone.kiosk.LOCK_SESSION"
        const val ACTION_ADMIN_ADJUST_TIME = "com.pisophone.kiosk.ADMIN_ADJUST_TIME"
        const val ACTION_MASTER_BOX_CONFIGURED = "com.pisophone.kiosk.MASTER_BOX_CONFIGURED"
        const val ACTION_HEALTH_CHECK = "com.pisophone.kiosk.HEALTH_CHECK"

        // Every command reaches the service the same way: as an intent delivered to onStartCommand. That starts
        // the service if it is not running and queues the command in order if it is, so there is one code path
        // and no need for callers to hold a reference to the live instance.

        fun configureMasterBox(
            context: Context,
            mac: String,
            slot: Int = -1,
            secret: String? = null,
            name: String? = null,
            wifiSsid: String? = null,
            wifiPassword: String? = null,
        ) {
            KioskSecurity.applyDirectProvisioning(
                context = context,
                secret = secret,
                mac = mac,
                slot = slot,
                name = name,
                wifiSsid = wifiSsid,
                wifiPassword = wifiPassword,
            )
            // (also without a password: the status line then says that no kiosk network is saved)
            Thread { com.pisophone.kiosk.network.KioskWifi.joinAndReport(context) }.start()
            KioskActivationManager.setPairingCompleted(context, true)
            // Credentials are already persisted above; the service only needs the MAC to start discovery.
            val cleanMac = KioskSecurity.formatMacAddress(mac)
            send(context, ACTION_MASTER_BOX_CONFIGURED) { putExtra("mac", cleanMac) }
        }

        fun triggerAdminBypass(context: Context, durationSeconds: Int = 900) =
            send(context, ACTION_ADMIN_BYPASS) { putExtra("duration", durationSeconds) }

        fun triggerLockSession(context: Context) = send(context, ACTION_LOCK_SESSION)

        fun triggerAdminTimeAdjust(context: Context, deltaSeconds: Int) =
            send(context, ACTION_ADMIN_ADJUST_TIME) { putExtra("delta_seconds", deltaSeconds) }

        fun triggerTestTts(context: Context, text: String = "PisoPhone voice system online and functional.") =
            send(context, ACTION_TEST_TTS) { putExtra("text", text) }

        /** Starts the service if it is dead, otherwise asks it to repair its HTTP listener and overlay. */
        fun requestHealthCheck(context: Context) = send(context, ACTION_HEALTH_CHECK)

        private fun send(context: Context, action: String, extras: Intent.() -> Unit = {}) {
            val intent = Intent(context, KioskService::class.java).apply {
                this.action = action
                extras()
            }
            try {
                context.startForegroundService(intent)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to send $action: ${e.message}")
            }
        }
    }

    private var engine: KioskEngine? = null
    private val stateManager: KioskStateManager by lazy { KioskStateManager(applicationContext) }

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
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
        com.pisophone.kiosk.util.CrashLoop.recentCrashes(this).takeIf { it > 0 }?.let {
            com.pisophone.kiosk.util.DiagnosticsLog.add("Restart", "The app restarted after a crash ($it in the last 10 minutes, ${com.pisophone.kiosk.util.CrashLoop.restartsLast24h(this)} in 24 hours)")
        }
        acquireLocks()

        val eng = KioskEngine(this, stateManager)
        engine = eng
        eng.start()
        startWifiKeeper()
        startUpdateKeeper()
    }

    // A phone that is not on the kiosk Wi-Fi cannot find its box (and so cannot pair or get its admin PIN): join it
    // again whenever it is not on it.
    private val wifiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val wifiKeeper = object : Runnable {
        override fun run() {
            Thread { com.pisophone.kiosk.network.KioskWifi.joinAndReport(applicationContext) }.start()
            wifiHandler.postDelayed(this, WIFI_CHECK_MS)
        }
    }

    private fun startWifiKeeper() {
        wifiHandler.removeCallbacks(wifiKeeper)
        wifiHandler.post(wifiKeeper)
        registerWifiWatcher()
    }

    // The moment the phone joins or loses any Wi-Fi (for example it wandered onto an open network), put it back on the kiosk
    // network without waiting for the next check.
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var lastWifiReactMs = 0L

    private fun registerWifiWatcher() {
        if (wifiCallback != null) return
        try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = reactToWifiChange()

                override fun onLost(network: Network) = reactToWifiChange()
            }
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
            wifiCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Could not watch the Wi-Fi: ${e.message}")
        }
    }

    private fun reactToWifiChange() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastWifiReactMs < WIFI_REACT_MIN_GAP_MS) return
        lastWifiReactMs = now
        Thread { com.pisophone.kiosk.network.KioskWifi.joinAndReport(applicationContext) }.start()
    }

    // New app releases install by themselves: every hour, and only while nobody is using the phone (the policy says when).
    private val updateKeeper = object : Runnable {
        override fun run() {
            KioskUpdateManager.autoUpdate(applicationContext) {
                AutoUpdatePolicy.isIdle(
                    stateManager.appState.value,
                    stateManager.sessionTimeRemaining.value,
                    stateManager.signedInAccount.value,
                    stateManager.isArenaMode.value,
                )
            }
            wifiHandler.postDelayed(this, AutoUpdatePolicy.CHECK_INTERVAL_MS)
        }
    }

    private fun startUpdateKeeper() {
        wifiHandler.removeCallbacks(updateKeeper)
        wifiHandler.postDelayed(updateKeeper, AutoUpdatePolicy.FIRST_CHECK_DELAY_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        KioskWatchdogReceiver.scheduleWatchdog(this)

        when (intent?.action) {
            ACTION_ADMIN_BYPASS -> {
                val duration = intent.getIntExtra("duration", 900)
                engine?.requestAdminBypass(duration)
            }
            ACTION_LOCK_SESSION -> {
                engine?.requestLockSession()
            }
            ACTION_ADMIN_ADJUST_TIME -> {
                val delta = intent.getIntExtra("delta_seconds", 0)
                if (delta != 0) {
                    engine?.requestAdminTimeAdjust(delta)
                }
            }
            ACTION_TEST_TTS -> {
                val text = intent.getStringExtra("text") ?: "PisoPhone voice system online and functional."
                engine?.speakTest(text)
            }
            ACTION_MASTER_BOX_CONFIGURED -> {
                intent.getStringExtra("mac")?.takeIf { it.isNotBlank() }?.let {
                    stateManager.esp32MacAddress.value = it
                }
                engine?.triggerCandidateDiscovery()
            }
            ACTION_HEALTH_CHECK -> engine?.runHealthRepair()
        }

        if (Settings.canDrawOverlays(this)) {
            engine?.overlayCoordinator?.show()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireLocks() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("pisophone_multicast_lock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
            // Keep the Wi-Fi radio fully awake while the screen is off so ESP32 heartbeats,
            // arm requests and the local HTTP server (/add_time) are not dropped by Wi-Fi sleep.
            // FULL_HIGH_PERF keeps Wi-Fi awake with the screen off but is a no-op from API 34,
            // where FULL_LOW_LATENCY is the only remaining option.
            @Suppress("DEPRECATION")
            val wifiMode = if (Build.VERSION.SDK_INT >= 34) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifi?.createWifiLock(wifiMode, "pisophone:kiosk_wifi_lock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pisophone:kiosk_service_wakelock")?.apply {
                acquire()
            }
            Log.d(TAG, "[+] Acquired MulticastLock, WifiLock and Partial WakeLock for reliable ESP32 networking.")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire MulticastLock, WifiLock or WakeLock: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Kiosk Service",
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        wifiHandler.removeCallbacks(wifiKeeper)
        wifiHandler.removeCallbacks(updateKeeper)
        try {
            wifiCallback?.let { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {
        }
        wifiCallback = null
        engine?.stop()
        engine = null
        releaseLocks()
        KioskWatchdogReceiver.scheduleWatchdog(applicationContext)
    }
}
