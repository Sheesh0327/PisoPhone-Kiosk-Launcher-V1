package com.pisophone.kiosk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.pisophone.kiosk.audio.KioskAudioManager
import com.pisophone.kiosk.network.Esp32ConnectionManager
import com.pisophone.kiosk.overlay.KioskOverlay
import com.pisophone.kiosk.receiver.KioskWatchdogReceiver
import com.pisophone.kiosk.security.KioskActivationManager
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.server.KioskHttpServer
import com.pisophone.kiosk.service.CoinProcessor
import com.pisophone.kiosk.service.KioskEsp32Coordinator
import com.pisophone.kiosk.service.KioskServerCoordinator
import com.pisophone.kiosk.service.KioskSessionSupervisor
import com.pisophone.kiosk.service.KioskStateManager
import com.pisophone.kiosk.system.KioskSystemMonitor
import com.pisophone.kiosk.system.KioskSystemMonitorDelegate
import com.pisophone.kiosk.util.HardwareFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KioskService : Service() {
    companion object {
        private const val TAG = "KioskService"
        private const val ARMING_TIMEOUT_SECONDS = 15
        private const val SERVER_PORT = 8080

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
            ip: String? = null,
            slot: Int = -1,
            secret: String? = null,
            name: String? = null
        ) {
            KioskSecurity.applyDirectProvisioning(
                context = context,
                secret = secret,
                mac = mac,
                ip = ip,
                slot = slot,
                name = name
            )
            KioskActivationManager.setPairingCompleted(context, true)
            val cleanMac = KioskSecurity.formatMacAddress(mac)
            activeInstance?.let { service ->
                if (cleanMac.isNotBlank()) {
                    service.stateManager.esp32MacAddress.value = cleanMac
                }
                if (!ip.isNullOrBlank()) {
                    service.stateManager.esp32Ip = ip.trim()
                    service.stateManager.saveState()
                    service.probeEsp32Connection(ip.trim())
                } else {
                    service.triggerCandidateDiscovery()
                }
            }
        }

        fun setManualEsp32Ip(context: Context, ip: String) {
            val trimmed = ip.trim()
            KioskSecurity.setConfiguredEsp32Ip(context, trimmed)
            activeInstance?.let { service ->
                service.stateManager.esp32Ip = if (trimmed.isNotBlank()) trimmed else null
                service.stateManager.saveState()
                if (trimmed.isNotBlank()) {
                    service.probeEsp32Connection(trimmed)
                } else {
                    service.triggerCandidateDiscovery()
                }
            }
        }

        fun triggerEsp32Rescan(context: Context) {
            activeInstance?.triggerCandidateDiscovery()
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }

    private fun getSecretKey(): String = KioskSecurity.getSharedSecret(this)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var nanoServer: KioskHttpServer? = null
    private lateinit var coinProcessor: CoinProcessor
    private lateinit var audioManager: KioskAudioManager
    private lateinit var esp32Manager: Esp32ConnectionManager
    private lateinit var stateManager: KioskStateManager
    private lateinit var supervisor: KioskSessionSupervisor
    private lateinit var coinEventRepo: com.pisophone.kiosk.repository.CoinEventRepository
    private lateinit var systemMonitor: KioskSystemMonitor

    private var slotBusyJob: Job? = null
    private var overlay: KioskOverlay? = null
    private var serviceStartTimeMs = 0L
    private var lastArmClickTimeMs = 0L
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private fun getRealTimeBatteryInfo(): Pair<Int, Boolean> {
        return if (::systemMonitor.isInitialized) {
            systemMonitor.getRealTimeBatteryInfo()
        } else {
            Pair(100, false)
        }
    }

    private fun playCoinSound() = audioManager.playCoinSound()
    private fun startWaitingMusic() = audioManager.startWaitingMusic()
    private fun stopWaitingMusic() = audioManager.stopWaitingMusic()
    private fun triggerFlashlight(durationMs: Long = 150L) = HardwareFeedback.triggerFlashlight(this, durationMs)

    fun speakWarning(text: String) {
        if (::audioManager.isInitialized) {
            audioManager.speakWarning(text)
        }
    }

    fun performAdminBypass(durationSeconds: Int = 900) {
        if (!KioskActivationManager.isAppAllowedToRun(this)) {
            Log.w(TAG, "Admin bypass rejected: Device is not provisioned.")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "⚠️ Bypass Unavailable: Device requires provisioning.", Toast.LENGTH_LONG).show()
            }
            return
        }
        Log.i(TAG, "Admin bypass granted for $durationSeconds seconds.")
        val now = System.currentTimeMillis()
        stateManager.appState.value = 2
        stateManager.sessionExpiryDeadlineMs.value = now + (durationSeconds * 1000L)
        stateManager.sessionTimeRemaining.value = durationSeconds
        stateManager.saveState()
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, "Admin Bypass Active (${durationSeconds / 60}m Maintenance)", Toast.LENGTH_SHORT).show()
        }
    }

    fun performLockSession() {
        Log.i(TAG, "Lock session requested by admin.")
        stateManager.appState.value = 0
        stateManager.sessionTimeRemaining.value = 0
        stateManager.sessionExpiryDeadlineMs.value = 0L
        stateManager.saveState()
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        activeInstance = this
        serviceStartTimeMs = System.currentTimeMillis()

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "kiosk_channel")
            .setContentTitle("Kiosk Active")
            .setContentText("Monitoring coin slot on port 8080")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }

        CrashReporter.init(this)

        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            multicastLock = wifi?.createMulticastLock("pisophone_multicast_lock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
            val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            wakeLock = powerManager?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "pisophone:kiosk_service_wakelock")?.apply {
                acquire(10 * 60 * 1000L)
            }
            Log.d(TAG, "[+] Acquired MulticastLock and Partial WakeLock for reliable ESP32 networking.")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire MulticastLock or WakeLock: ${e.message}")
        }

        stateManager = KioskStateManager(this)
        val initialTxSet = stateManager.restoreState()

        coinEventRepo = com.pisophone.kiosk.repository.CoinEventRepository(
            com.pisophone.kiosk.db.AppDatabase.getDatabase(this).coinEventDao()
        )
        coinProcessor = CoinProcessor(
            context = this,
            scope = scope,
            coinEventRepo = coinEventRepo,
            onCreditsApplied = { seconds, pesoAmount ->
                val now = System.currentTimeMillis()
                val currentDeadline = stateManager.sessionExpiryDeadlineMs.value
                val newDeadline = if (currentDeadline > now) {
                    currentDeadline + (seconds * 1000L)
                } else {
                    now + (seconds * 1000L)
                }
                stateManager.sessionExpiryDeadlineMs.value = newDeadline
                stateManager.sessionTimeRemaining.value = ((newDeadline - now) / 1000L).toInt()
                stateManager.paymentTimeout.value = ARMING_TIMEOUT_SECONDS
                if (stateManager.appState.value == 0) {
                    stateManager.coinsInserted.value += pesoAmount
                    stateManager.appState.value = 1
                } else if (stateManager.appState.value == 1 || stateManager.appState.value == 3) {
                    stateManager.coinsInserted.value += pesoAmount
                } else if (stateManager.appState.value == 2) {
                    Log.d(TAG, "Coin credited directly to active session: +${seconds}s (₱$pesoAmount)")
                }
                stateManager.saveState()
            },
            onFeedbackTrigger = {
                playCoinSound()
                triggerFlashlight()
            }
        )
        if (initialTxSet.isNotEmpty()) {
            coinProcessor.restoreProcessedTxIds(initialTxSet)
        }

        audioManager = KioskAudioManager(this, scope)
        audioManager.initAudioEngine()

        systemMonitor = KioskSystemMonitor(
            context = this,
            scope = scope,
            delegate = object : KioskSystemMonitorDelegate {
                override fun onScreenSleep() { overlay?.onScreenSleep() }
                override fun onScreenWake() { overlay?.onScreenWake() }
                override fun getAudioManager(): KioskAudioManager? = if (::audioManager.isInitialized) audioManager else null
            }
        )

        val esp32Coordinator = KioskEsp32Coordinator(
            context = this,
            stateManager = stateManager,
            armingTimeoutSeconds = ARMING_TIMEOUT_SECONDS,
            getSecretKey = { getSecretKey() },
            getRealTimeBatteryInfo = { getRealTimeBatteryInfo() },
            onAddCoinTime = { seconds, source, txId, amount ->
                addTimeFromMaster(seconds, source, txId, amount)
            },
            onSlotBusyTriggered = { triggerSlotBusy() }
        )

        esp32Manager = Esp32ConnectionManager(
            context = this,
            scope = scope,
            delegate = esp32Coordinator
        )
        if (!stateManager.esp32Ip.isNullOrBlank()) {
            esp32Manager.setEsp32Ip(stateManager.esp32Ip)
        }

        if (!KioskActivationManager.isPairingCompleted(this) && !KioskSecurity.isProvisioned(this)) {
            KioskActivationManager.startSetupWindow(this)
        }
        setupOverlay()
        scope.launch {
            KioskActivationManager.activationUpdateVersion.collect {
                val allowed = KioskActivationManager.isAppAllowedToRun(this@KioskService)
                Handler(Looper.getMainLooper()).post {
                    if (!allowed) {
                        overlay?.remove()
                        overlay = null
                    } else if (overlay == null) {
                        setupOverlay()
                    }
                }
            }
        }

        val serverCoordinator = KioskServerCoordinator(
            context = this,
            stateManager = stateManager,
            coinEventRepo = coinEventRepo,
            getSecretKey = { getSecretKey() },
            getRealTimeBatteryInfo = { getRealTimeBatteryInfo() },
            getAudioManager = { if (::audioManager.isInitialized) audioManager else null },
            onAddCoinTime = { seconds, source, txId, amount ->
                addTimeFromMaster(seconds, source, txId, amount)
            }
        )

        try {
            nanoServer = KioskHttpServer(this, SERVER_PORT, serverCoordinator)
            nanoServer?.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.d(TAG, "NanoHTTPD Server listening on port $SERVER_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NanoHTTPD server: ${e.message}")
        }

        esp32Manager.triggerCandidateDiscovery(stateManager.deviceIp.value)
        esp32Manager.startHeartbeatLoop { stateManager.deviceIp.value }

        supervisor = KioskSessionSupervisor(
            context = this,
            scope = scope,
            stateManager = stateManager,
            onSpeakWarning = { speakWarning(it) },
            onFinishPayment = { finishPayment() },
            onCloseSession = { closeSession(it) },
            onCheckBatteryAlerts = { systemMonitor.checkPeriodicBatteryAlerts() }
        )
        supervisor.start()
        startHealthMonitor()

        systemMonitor.registerScreenOffReceiver()
        systemMonitor.registerBatteryMonitor()

        scope.launch {
            try {
                val recentEvents = coinEventRepo.getLatestEvents(200)
                val txSet = recentEvents.mapNotNull { it.txId.takeIf { tx -> tx.isNotBlank() } }.toSet()
                coinProcessor.restoreProcessedTxIds(txSet)
                Log.d(TAG, "Hydrated ${txSet.size} transaction IDs from Room DB into cache.")
            } catch (e: Exception) {
                Log.e(TAG, "Error hydrating transaction cache: ${e.message}")
            }
        }

        scope.launch {
            stateManager.appState.collect { state ->
                if (state == 1 || state == 3) {
                    startWaitingMusic()
                } else {
                    stopWaitingMusic()
                }
            }
        }

        scope.launch {
            KioskActivationManager.activationUpdateVersion.collect {
                val isSetup = KioskActivationManager.isAppAllowedToRun(this@KioskService)
                if (isSetup && overlay == null) {
                    withContext(Dispatchers.Main) {
                        setupOverlay()
                    }
                }
            }
        }
    }

    @Synchronized
    private fun addTimeFromMaster(seconds: Int, source: String, txId: String? = null, amount: Double = 1.0): Boolean {
        if (!::coinProcessor.isInitialized) return false
        val now = System.currentTimeMillis()
        val isStartup = (now - serviceStartTimeMs < 3000 && stateManager.appState.value == 0)
        return coinProcessor.processCoinCredit(
            seconds = seconds,
            source = source,
            txId = txId,
            amount = amount,
            isStartupPhase = isStartup
        )
    }

    fun triggerCandidateDiscovery() {
        if (::esp32Manager.isInitialized) {
            esp32Manager.triggerCandidateDiscovery(stateManager.deviceIp.value)
        }
    }

    fun probeEsp32Connection(ip: String): Boolean {
        return if (::esp32Manager.isInitialized) esp32Manager.probeEsp32Connection(ip) else false
    }

    private fun closeSession(sendUnarmToEsp: Boolean = false) {
        if (::esp32Manager.isInitialized) {
            esp32Manager.closeSession(sendUnarmToEsp)
        }
    }

    private fun triggerSlotBusy() {
        stateManager.isSlotBusy.value = true
        slotBusyJob?.cancel()
        slotBusyJob = scope.launch {
            delay(5000)
            stateManager.isSlotBusy.value = false
        }
    }

    private fun armSlot() {
        if (::esp32Manager.isInitialized) {
            esp32Manager.armSlot(ARMING_TIMEOUT_SECONDS)
        }
    }

    private fun finishPayment() {
        closeSession(sendUnarmToEsp = true)
        if (stateManager.coinsInserted.value > 0) {
            stateManager.appState.value = 2
        } else {
            if (stateManager.appState.value == 3) {
                stateManager.appState.value = 2
            } else {
                stateManager.appState.value = 0
            }
        }
        stateManager.coinsInserted.value = 0
        stateManager.saveState()
    }

    private fun startHealthMonitor() {
        scope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(10_000L)
                try {
                    supervisor.ensureRunning()
                    val appState = stateManager.appState.value
                    if (appState == 2 || appState == 3) {
                        val deadline = stateManager.sessionExpiryDeadlineMs.value
                        val now = System.currentTimeMillis()
                        if (deadline > 0L && now >= deadline) {
                            Log.w(TAG, "Health monitor: Session deadline expired ($deadline <= $now). Forcing lock state.")
                            stateManager.appState.value = 0
                            stateManager.sessionTimeRemaining.value = 0
                            stateManager.sessionExpiryDeadlineMs.value = 0L
                            stateManager.saveState()
                        }
                    }

                    val currentAppState = stateManager.appState.value
                    if (currentAppState == 0 || currentAppState == 1) {
                        if (overlay == null || overlay?.isAttached() != true) {
                            Log.w(TAG, "Health monitor: Overlay missing or detached while locked/armed (AppState: $currentAppState). Rebuilding...")
                            overlay?.remove()
                            overlay = null
                            setupOverlay()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Health monitor check failed: ${e.message}")
                }
            }
        }
    }

    fun isOverlayHealthy(): Boolean {
        val isFullySetup = KioskActivationManager.isAppAllowedToRun(this)
        if (!isFullySetup) return true
        return overlay != null && overlay?.isAttached() == true
    }

    fun setupOverlay() {
        val isFullySetup = KioskActivationManager.isAppAllowedToRun(this)
        if (!isFullySetup) {
            Log.d(TAG, "Device not activated or fully setup. Lock screen overlay deferred.")
            return
        }

        scope.launch(Dispatchers.Main) {
            if (overlay != null && overlay?.isAttached() == true) return@launch

            if (overlay == null) {
                try {
                    overlay = KioskOverlay(
                        context = this@KioskService,
                        appStateFlow = stateManager.appState,
                        sessionTimeFlow = stateManager.sessionTimeRemaining,
                        paymentTimeoutFlow = stateManager.paymentTimeout,
                        coinsInsertedFlow = stateManager.coinsInserted,
                        themeIndexFlow = stateManager.themeIndex,
                        isEsp32OnlineFlow = stateManager.isEsp32Online,
                        esp32MacAddressFlow = stateManager.esp32MacAddress,
                        isSlotBusyFlow = stateManager.isSlotBusy,
                        pricePerCoinFlow = stateManager.pricePerCoin,
                        minutesPerCoinFlow = stateManager.minutesPerCoin,
                        deviceIpFlow = stateManager.deviceIp,
                        slotNumberFlow = stateManager.slotNumber,
                        batteryStatusFlow = systemMonitor.batteryStatus,
                        slotWarningDaysLeftFlow = stateManager.slotWarningDaysLeft,
                        isSlotExpiredFlow = stateManager.isSlotExpired,
                        slotExpiryReasonFlow = stateManager.slotExpiryMessage,
                        onInsertCoinClick = {
                            if (stateManager.appState.value == 4) return@KioskOverlay
                            if (stateManager.isSlotExpired.value || KioskActivationManager.isSlotLockedDown(this@KioskService)) {
                                Log.w(TAG, "Coin insertion blocked: Device not activated on ESP32.")
                                Handler(Looper.getMainLooper()).post {
                                    Toast.makeText(applicationContext, "Device not activated. Please activate this device in the ESP32 Kiosk Manager.", Toast.LENGTH_LONG).show()
                                }
                                return@KioskOverlay
                            }
                            if (stateManager.appState.value == 2) {
                                stateManager.appState.value = 3
                            } else {
                                stateManager.appState.value = 1
                            }
                            stateManager.coinsInserted.value = 0
                            stateManager.paymentTimeout.value = ARMING_TIMEOUT_SECONDS
                            lastArmClickTimeMs = System.currentTimeMillis()
                            armSlot()
                        },
                        onDoneClick = { finishPayment() },
                        onThemeChange = { stateManager.themeIndex.value = (stateManager.themeIndex.value + 1) % 3 }
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error constructing KioskOverlay: ${e.message}", e)
                    overlay = null
                    return@launch
                }
            }

            val attached = overlay?.show() ?: false
            if (!attached) {
                Log.w(TAG, "Failed to attach overlay window. Resetting overlay reference for retry.")
                overlay?.remove()
                overlay = null
            }
        }
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
            scope.launch(Dispatchers.Main) {
                overlay?.show()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "kiosk_channel",
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
        scope.cancel()
        if (::supervisor.isInitialized) {
            supervisor.stop()
        }
        if (::systemMonitor.isInitialized) {
            systemMonitor.shutdown()
        }
        KioskWatchdogReceiver.scheduleWatchdog(applicationContext)
        if (::esp32Manager.isInitialized) {
            esp32Manager.shutdown()
        }
        if (::audioManager.isInitialized) {
            audioManager.shutdown()
        }
        nanoServer?.stop()
        overlay?.remove()
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
    }
}
