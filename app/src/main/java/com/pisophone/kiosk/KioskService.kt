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
import com.pisophone.kiosk.network.Esp32ConnectionDelegate
import com.pisophone.kiosk.network.Esp32ConnectionManager
import com.pisophone.kiosk.overlay.KioskOverlay
import com.pisophone.kiosk.receiver.KioskWatchdogReceiver
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.security.HardwareLockManager
import com.pisophone.kiosk.server.KioskHttpServer
import com.pisophone.kiosk.server.KioskServerDelegate
import com.pisophone.kiosk.service.CoinProcessor
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
import org.json.JSONObject
import java.io.File

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
        if (!com.pisophone.kiosk.security.HardwareLockManager.isAppAllowedToRun(this) || stateManager.appState.value == 4) {
            Log.w(TAG, "Admin bypass rejected: Device is not activated or is unlicensed.")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "⚠️ Bypass Unavailable: Device requires license activation.", Toast.LENGTH_LONG).show()
            }
            return
        }
        Log.i(TAG, "Admin bypass granted for $durationSeconds seconds.")
        stateManager.appState.value = 2
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
        stateManager.saveState()
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        activeInstance = this
        serviceStartTimeMs = System.currentTimeMillis()
        CrashReporter.init(this)

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
                stateManager.coinsInserted.value += pesoAmount
                stateManager.sessionTimeRemaining.value += seconds
                stateManager.paymentTimeout.value = ARMING_TIMEOUT_SECONDS
                if (stateManager.appState.value == 0) {
                    stateManager.appState.value = 1
                } else if (stateManager.appState.value == 2) {
                    stateManager.appState.value = 3
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
                override fun isUnlicensed(): Boolean = stateManager.appState.value == 4
                override fun onScreenSleep() { overlay?.onScreenSleep() }
                override fun onScreenWake() { overlay?.onScreenWake() }
                override fun onPerformSleepClear() {
                    stateManager.appState.value = 0
                    stateManager.sessionTimeRemaining.value = 0
                    stateManager.coinsInserted.value = 0
                    stateManager.saveState()
                }
                override fun getAudioManager(): KioskAudioManager? = if (::audioManager.isInitialized) audioManager else null
            }
        )

        esp32Manager = Esp32ConnectionManager(
            context = this,
            scope = scope,
            delegate = object : Esp32ConnectionDelegate {
                override fun getDeviceId(): String = stateManager.deviceId.value
                override fun getSecretKey(): String = this@KioskService.getSecretKey()
                override fun getAppState(): Int = stateManager.appState.value
                override fun getSessionTimeRemaining(): Int = stateManager.sessionTimeRemaining.value
                override fun getRealTimeBatteryInfo(): Pair<Int, Boolean> = this@KioskService.getRealTimeBatteryInfo()
                
                override fun onEsp32Discovered(ip: String) {
                    stateManager.esp32Ip = ip
                    stateManager.isEsp32Online.value = true
                    stateManager.saveState()
                }

                override fun onOnlineStatusChanged(isOnline: Boolean, mac: String?) {
                    stateManager.isEsp32Online.value = isOnline
                    if (!mac.isNullOrBlank()) stateManager.esp32MacAddress.value = mac
                }

                override fun onConfigSynced(price: Double?, minutes: Int?, alias: String?, isUnlicensed: Boolean) {
                    price?.let { stateManager.pricePerCoin.value = it }
                    minutes?.let { stateManager.minutesPerCoin.value = it }
                    alias?.takeIf { it.isNotBlank() }?.let {
                        val current = KioskSecurity.getDeviceAlias(this@KioskService)
                        if (current != it) {
                            KioskSecurity.setDeviceAlias(this@KioskService, it)
                            Log.d(TAG, "[+] Synchronized device nickname from Master: $it")
                        }
                    }
                    if (isUnlicensed) {
                        if (stateManager.appState.value != 4) stateManager.appState.value = 4
                    } else if (stateManager.appState.value == 4) {
                        stateManager.appState.value = 0
                        HardwareLockManager.sealToCurrentDevice(this@KioskService)
                    }
                    stateManager.saveState()
                }

                override fun onCoinMessageReceived(seconds: Int, amount: Double, txId: String?) {
                    if (!HardwareLockManager.isAppAllowedToRun(applicationContext)) {
                        Log.e(TAG, "Hardware Lock or Expired Trial active: Discarding coin event on locked device.")
                        return
                    }
                    if (txId.isNullOrBlank()) {
                        Log.e(TAG, "Invalid coin message over WebSocket: missing transaction ID")
                        return
                    }

                    Log.d(TAG, "Received validated coin via WebSocket: seconds=$seconds, amount=₱$amount, tx_id=$txId")
                    addTimeFromMaster(seconds, "WebSocket Port 81", txId, amount)
                    stateManager.paymentTimeout.value = ARMING_TIMEOUT_SECONDS
                }

                override fun onSlotBusy() {
                    triggerSlotBusy()
                    if (stateManager.appState.value == 3) {
                        stateManager.appState.value = 2
                    } else {
                        stateManager.appState.value = 0
                    }
                }

                override fun onArmSuccess() {
                    stateManager.isEsp32Online.value = true
                }
            }
        )
        if (!stateManager.esp32Ip.isNullOrBlank()) {
            esp32Manager.setEsp32Ip(stateManager.esp32Ip)
        }

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
        
        setupOverlay()
        startServer()
        esp32Manager.startNsdDiscovery()
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
            com.pisophone.kiosk.security.HardwareLockManager.licenseUpdateVersion.collect {
                val isSetup = com.pisophone.kiosk.security.HardwareLockManager.isTutorialCompleted(this@KioskService) &&
                              com.pisophone.kiosk.security.HardwareLockManager.isAppAllowedToRun(this@KioskService)
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
        val isStartup = (System.currentTimeMillis() - serviceStartTimeMs < 3000 && stateManager.appState.value == 0)
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

    private fun startServer() {
        try {
            val delegate = object : KioskServerDelegate {
                override fun getSecretKey(): String = this@KioskService.getSecretKey()

                override fun onHeartbeat(clientIp: String?) {
                    stateManager.isEsp32Online.value = true
                    if (!clientIp.isNullOrEmpty() && clientIp != "127.0.0.1") {
                        if (stateManager.esp32Ip != clientIp) {
                            stateManager.esp32Ip = clientIp
                            stateManager.saveState()
                        }
                    }
                }

                override fun getStatusJson(): JSONObject {
                    val (curBat, isChg) = getRealTimeBatteryInfo()
                    return JSONObject().apply {
                        put("device_id", stateManager.deviceId.value)
                        put("alias", KioskSecurity.getDeviceAlias(applicationContext))
                        put("state", stateManager.appState.value)
                        put("time_remaining", stateManager.sessionTimeRemaining.value)
                        put("battery", curBat)
                        put("charging", isChg)
                        put("online", true)
                    }
                }

                override fun getSessionTimeRemaining(): Int = stateManager.sessionTimeRemaining.value
                override fun getAppState(): Int = stateManager.appState.value

                override fun getAuditEventsJson(): String {
                    return kotlinx.coroutines.runBlocking {
                        val events = coinEventRepo.getLatestEvents(100)
                        val jsonArray = org.json.JSONArray()
                        for (event in events) {
                            val obj = org.json.JSONObject()
                            obj.put("id", event.id)
                            obj.put("txId", event.txId)
                            obj.put("secondsAdded", event.secondsAdded)
                            obj.put("source", event.source)
                            obj.put("timestamp", event.timestamp)
                            jsonArray.put(obj)
                        }
                        jsonArray.toString()
                    }
                }

                override fun onCoinCredited(seconds: Int, source: String, txId: String?, amount: Double): Boolean {
                    return addTimeFromMaster(seconds, source, txId, amount)
                }

                override fun onDeductTime(seconds: Int) {
                    stateManager.sessionTimeRemaining.value += seconds
                    if (stateManager.sessionTimeRemaining.value <= 0) {
                        stateManager.sessionTimeRemaining.value = 0
                        stateManager.appState.value = 0
                    }
                    stateManager.saveState()
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this@KioskService, "${-seconds / 60} minutes deducted!", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onConfigUpdated(price: Double?, minutes: Int?, deviceName: String?, adminPin: String?) {
                    price?.let { stateManager.pricePerCoin.value = it }
                    minutes?.let { stateManager.minutesPerCoin.value = it }
                    deviceName?.let { 
                        val trimmed = it.trim()
                        KioskSecurity.setDeviceAlias(applicationContext, trimmed)
                    }
                    adminPin?.let { if (it.isNotBlank()) KioskSecurity.setAdminPin(applicationContext, it) }
                    stateManager.saveState()
                    val currentName = KioskSecurity.getDeviceAlias(applicationContext).takeIf { it.isNotBlank() } ?: "Terminal"
                    Log.d(TAG, "Master pushed config update: Price=₱${stateManager.pricePerCoin.value}, Minutes=${stateManager.minutesPerCoin.value}m, DeviceName=$currentName, Pin=$adminPin")
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this@KioskService, "Config Synced: $currentName", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onTriggerAction(action: String) {
                    Handler(Looper.getMainLooper()).post {
                        when (action) {
                            "vibrate" -> HardwareFeedback.triggerVibration(this@KioskService, longArrayOf(0, 1500))
                            "sound" -> {
                                val ringtone = android.media.RingtoneManager.getRingtone(applicationContext, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION))
                                ringtone?.play()
                            }
                            "flash" -> HardwareFeedback.triggerFlashlight(this@KioskService, 1500L)
                            "locate" -> {
                                HardwareFeedback.triggerVibration(this@KioskService, longArrayOf(0, 1500))
                                val ringtone = android.media.RingtoneManager.getRingtone(applicationContext, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION))
                                ringtone?.play()
                                HardwareFeedback.triggerFlashlight(this@KioskService, 1500L)
                            }
                            "enable_adb" -> {
                                KioskSecurity.emergencyEnableUsbDebugging(applicationContext)
                                Toast.makeText(this@KioskService, "⚡ Remote: USB Debugging Re-Enabled!", Toast.LENGTH_LONG).show()
                            }
                            "recovery", "emergency_recovery" -> {
                                KioskSecurity.emergencyEnableUsbDebugging(applicationContext)
                                KioskSecurity.emergencyExitKiosk(applicationContext)
                                Toast.makeText(this@KioskService, "⚠️ Remote: Emergency Recovery & ADB Enabled!", Toast.LENGTH_LONG).show()
                            }
                            "exit_kiosk" -> {
                                KioskSecurity.emergencyExitKiosk(applicationContext)
                            }
                            "deprovision" -> {
                                KioskSecurity.emergencyClearDeviceOwner(applicationContext)
                            }
                            "factory_reset" -> {
                                KioskSecurity.factoryResetDevice(applicationContext)
                            }
                        }
                        Toast.makeText(this@KioskService, "Device Identified: ${KioskSecurity.getDeviceAlias(applicationContext)}", Toast.LENGTH_LONG).show()
                    }
                }

                override fun getCrashLog(): String? {
                    return try {
                        val logDir = this@KioskService.getExternalFilesDir(null) ?: this@KioskService.filesDir
                        val file = File(logDir, "crash.log")
                        if (file.exists()) file.readText() else null
                    } catch (_: Exception) {
                        null
                    }
                }
            }

            nanoServer = KioskHttpServer(this, SERVER_PORT, delegate)
            nanoServer?.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.d(TAG, "NanoHTTPD Server listening on port $SERVER_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NanoHTTPD server: ${e.message}")
        }
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
    
    private fun setupOverlay() {
        val isFullySetup = com.pisophone.kiosk.security.HardwareLockManager.isTutorialCompleted(this) &&
                           com.pisophone.kiosk.security.HardwareLockManager.isAppAllowedToRun(this)
        if (!isFullySetup) {
            Log.d(TAG, "Device not activated or fully setup. Lock screen overlay deferred.")
            return
        }
        if (overlay != null) return

        overlay = KioskOverlay(
            context = this,
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
            batteryStatusFlow = systemMonitor.batteryStatus,
            onInsertCoinClick = { 
                if (stateManager.appState.value == 4) return@KioskOverlay
                if (stateManager.appState.value == 2) {
                    stateManager.appState.value = 3
                } else {
                    stateManager.appState.value = 1
                }
                stateManager.coinsInserted.value = 0
                stateManager.paymentTimeout.value = ARMING_TIMEOUT_SECONDS
                armSlot()
            },
            onDoneClick = { finishPayment() },
            onThemeChange = { stateManager.themeIndex.value = (stateManager.themeIndex.value + 1) % 3 },
            onActivateClick = { code ->
                HardwareLockManager.sealToCurrentDevice(this@KioskService)
                HardwareLockManager.activateOneYearLicense(this@KioskService, code)
                if (::esp32Manager.isInitialized) {
                    esp32Manager.sendActivationCode(code)
                }
            }
        )
        scope.launch(Dispatchers.Main) {
            overlay?.show()
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
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
    }
}
