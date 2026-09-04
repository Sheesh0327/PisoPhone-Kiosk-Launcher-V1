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
import com.pisophone.kiosk.system.KioskSystemMonitor
import com.pisophone.kiosk.system.KioskSystemMonitorDelegate
import com.pisophone.kiosk.util.HardwareFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.UUID

class KioskService : Service() {
    companion object {
        private const val TAG = "KioskService"
        private const val ARMING_TIMEOUT_SECONDS = 15
        private const val SERVER_PORT = 8080
        private const val ESP32_WEB_PORT = 8055
        private const val PREFS_NAME = "kiosk_persistent_state"

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
                service.esp32Ip = if (trimmed.isNotBlank()) trimmed else null
                service.saveState()
                if (trimmed.isNotBlank()) {
                    service.probeEsp32Connection(trimmed)
                } else {
                    service.triggerCandidateDiscovery()
                }
            }
        }

        fun triggerEsp32Rescan(context: Context) {
            activeInstance?.let { service ->
                service.triggerCandidateDiscovery()
            }
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

        fun triggerTestTts(context: Context, text: String = "Arcade OS voice system online and functional.") {
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

    private fun getEsp32HostAndPort(rawIp: String?): Pair<String, Int> {
        if (::esp32Manager.isInitialized) {
            return esp32Manager.getEsp32HostAndPort(rawIp)
        }
        if (rawIp.isNullOrBlank()) return Pair("", ESP32_WEB_PORT)
        val parts = rawIp.split(":")
        var host = parts[0]
        val port = if (parts.size > 1) parts[1].toIntOrNull() ?: ESP32_WEB_PORT else ESP32_WEB_PORT
        if (host == "127.0.0.1" || host == "localhost") host = "10.0.2.2"
        return Pair(host, port)
    }

    // App state: 0 = full screen block, 1 = waiting for coin, 2 = unlocked, 3 = unlocked + top-up waiting
    private val appState = MutableStateFlow(0) 
    private val sessionTimeRemaining = MutableStateFlow(0)
    private val paymentTimeout = MutableStateFlow(0)
    private val coinsInserted = MutableStateFlow(0)
    private val themeIndex = MutableStateFlow(0)
    
    private val deviceIp = MutableStateFlow("127.0.0.1")
    private val deviceId = MutableStateFlow("")
    private val isEsp32Online = MutableStateFlow(false)
    private val esp32MacAddress = MutableStateFlow("")
    private val isSlotBusy = MutableStateFlow(false)
    private var slotBusyJob: Job? = null
    private var esp32Ip: String? = null
    private var lastHeartbeatTime = 0L

    // Master-configured pricing values (populated directly by ESP32)
    private val pricePerCoin = MutableStateFlow(5.0)
    private val minutesPerCoin = MutableStateFlow(30)

    private var overlay: KioskOverlay? = null
    private var serviceStartTimeMs = 0L
    private lateinit var coinEventRepo: com.pisophone.kiosk.repository.CoinEventRepository
    private lateinit var systemMonitor: KioskSystemMonitor

    private fun getRealTimeBatteryInfo(): Pair<Int, Boolean> {
        return if (::systemMonitor.isInitialized) {
            systemMonitor.getRealTimeBatteryInfo()
        } else {
            Pair(100, false)
        }
    }

    private fun playAnnoyingLowBatteryTone() = audioManager.playAnnoyingLowBatteryTone()
    private fun playHighBatteryAttentionTone() = audioManager.playHighBatteryAttentionTone()
    private fun playSynthesizedTone(freqHz: Int, durationMs: Int) = audioManager.playSynthesizedTone(freqHz, durationMs)
    private fun playCoinSound() = audioManager.playCoinSound()
    private fun startWaitingMusic() = audioManager.startWaitingMusic()
    private fun stopWaitingMusic() = audioManager.stopWaitingMusic()
    private fun triggerVibration(pattern: LongArray) = HardwareFeedback.triggerVibration(this, pattern)
    private fun triggerFlashlight(durationMs: Long = 150L) = HardwareFeedback.triggerFlashlight(this, durationMs)

    fun speakWarning(text: String) {
        if (::audioManager.isInitialized) {
            audioManager.speakWarning(text)
        }
    }

    fun performAdminBypass(durationSeconds: Int = 900) {
        Log.i(TAG, "Admin bypass granted for $durationSeconds seconds.")
        appState.value = 2
        sessionTimeRemaining.value = durationSeconds
        saveState()
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, "Admin Bypass Active (${durationSeconds / 60}m Maintenance)", Toast.LENGTH_SHORT).show()
        }
    }

    fun performLockSession() {
        Log.i(TAG, "Lock session requested by admin.")
        appState.value = 0
        sessionTimeRemaining.value = 0
        saveState()
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        activeInstance = this
        coinEventRepo = com.pisophone.kiosk.repository.CoinEventRepository(
            com.pisophone.kiosk.db.AppDatabase.getDatabase(this).coinEventDao()
        )
        coinProcessor = CoinProcessor(
            context = this,
            scope = scope,
            coinEventRepo = coinEventRepo,
            onCreditsApplied = { seconds, pesoAmount ->
                coinsInserted.value += pesoAmount
                sessionTimeRemaining.value += seconds
                paymentTimeout.value = ARMING_TIMEOUT_SECONDS
                if (appState.value == 0) {
                    appState.value = 1
                } else if (appState.value == 2) {
                    appState.value = 3
                }
                saveState()
            },
            onFeedbackTrigger = {
                playCoinSound()
                triggerFlashlight()
            }
        )
        serviceStartTimeMs = System.currentTimeMillis()
        CrashReporter.init(this)
        deviceIp.value = getLocalIpAddress()
        
        val deviceContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) createDeviceProtectedStorageContext() else this
        val prefs = deviceContext.getSharedPreferences("kiosk_prefs", Context.MODE_PRIVATE)
        var savedUuid = prefs.getString("device_uuid", null)
        if (savedUuid == null) {
            savedUuid = UUID.randomUUID().toString()
            prefs.edit().putString("device_uuid", savedUuid).apply()
        }
        deviceId.value = savedUuid!!
        
        restoreState()
        audioManager = KioskAudioManager(this, scope)
        audioManager.initAudioEngine()

        systemMonitor = KioskSystemMonitor(
            context = this,
            scope = scope,
            delegate = object : KioskSystemMonitorDelegate {
                override fun isUnlicensed(): Boolean = appState.value == 4
                override fun onScreenSleep() {
                    overlay?.onScreenSleep()
                }
                override fun onScreenWake() {
                    overlay?.onScreenWake()
                }
                override fun onPerformSleepClear() {
                    appState.value = 0
                    sessionTimeRemaining.value = 0
                    coinsInserted.value = 0
                    saveState()
                }
                override fun getAudioManager(): KioskAudioManager? = if (::audioManager.isInitialized) audioManager else null
            }
        )

        esp32Manager = Esp32ConnectionManager(
            context = this,
            scope = scope,
            delegate = object : Esp32ConnectionDelegate {
                override fun getDeviceId(): String = deviceId.value
                override fun getSecretKey(): String = this@KioskService.getSecretKey()
                override fun getAppState(): Int = appState.value
                override fun getSessionTimeRemaining(): Int = sessionTimeRemaining.value
                override fun getRealTimeBatteryInfo(): Pair<Int, Boolean> = this@KioskService.getRealTimeBatteryInfo()
                
                override fun onEsp32Discovered(ip: String) {
                    esp32Ip = ip
                    isEsp32Online.value = true
                    lastHeartbeatTime = System.currentTimeMillis()
                    saveState()
                }

                override fun onOnlineStatusChanged(isOnline: Boolean, mac: String?) {
                    isEsp32Online.value = isOnline
                    if (isOnline) lastHeartbeatTime = System.currentTimeMillis()
                    if (!mac.isNullOrBlank()) esp32MacAddress.value = mac
                }

                override fun onConfigSynced(price: Double?, minutes: Int?, alias: String?, isUnlicensed: Boolean) {
                    price?.let { pricePerCoin.value = it }
                    minutes?.let { minutesPerCoin.value = it }
                    alias?.takeIf { it.isNotBlank() }?.let {
                        val current = KioskSecurity.getDeviceAlias(this@KioskService)
                        if (current != it) {
                            KioskSecurity.setDeviceAlias(this@KioskService, it)
                            Log.d(TAG, "[+] Synchronized device nickname from Master: $it")
                        }
                    }
                    if (isUnlicensed) {
                        if (appState.value != 4) appState.value = 4
                    } else if (appState.value == 4) {
                        appState.value = 0
                        HardwareLockManager.sealToCurrentDevice(this@KioskService)
                    }
                    saveState()
                }

                override fun onCoinMessageReceived(seconds: Int, amount: Double, txId: String?, challenge: String, sig: String) {
                    if (!HardwareLockManager.isAppAllowedToRun(applicationContext)) {
                        Log.e(TAG, "Hardware Lock or Expired Trial active: Discarding coin event on locked device.")
                        return
                    }
                    if (challenge.isBlank() || sig.isBlank() || !verifyChallengeAndSignature(challenge, sig)) {
                        Log.e(TAG, "Unverified coin message over WebSocket: challenge or signature missing/invalid")
                        return
                    }
                    Log.d(TAG, "Received coin via WebSocket: seconds=$seconds, amount=₱$amount, tx_id=$txId")
                    addTimeFromMaster(seconds, "WebSocket Port 81", txId, amount)
                    paymentTimeout.value = ARMING_TIMEOUT_SECONDS
                }

                override fun onSlotBusy() {
                    triggerSlotBusy()
                    if (appState.value == 3) {
                        appState.value = 2
                    } else {
                        appState.value = 0
                    }
                }

                override fun onArmSuccess() {
                    isEsp32Online.value = true
                    lastHeartbeatTime = System.currentTimeMillis()
                }
            }
        )
        if (!esp32Ip.isNullOrBlank()) {
            esp32Manager.setEsp32Ip(esp32Ip)
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
        esp32Manager.startHeartbeatLoop { deviceIp.value }
        startTimer()
        systemMonitor.registerScreenOffReceiver()
        systemMonitor.registerBatteryMonitor()

        scope.launch {
            while (isActive) {
                try {
                    HardwareLockManager.syncWithBackend(this@KioskService)
                } catch (e: Exception) {
                    Log.d(TAG, "Periodic server license sync: ${e.message}")
                }
                delay(60_000L)
            }
        }

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
            appState.collect { state ->
                if (state == 1 || state == 3) {
                    startWaitingMusic()
                } else {
                    stopWaitingMusic()
                }
            }
        }
    }

    private fun saveState() {
        try {
            val deviceContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) createDeviceProtectedStorageContext() else this
            val prefs = deviceContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val txSet = if (::coinProcessor.isInitialized) coinProcessor.getProcessedTxSet().take(20).toSet() else emptySet()
            prefs.edit()
                .putInt("app_state", appState.value)
                .putInt("session_time_remaining", sessionTimeRemaining.value)
                .putInt("coins_inserted", coinsInserted.value)
                .putFloat("price_per_coin", pricePerCoin.value.toFloat())
                .putInt("minutes_per_coin", minutesPerCoin.value)
                .putString("esp32_ip", esp32Ip)
                .putInt("target_port", ESP32_WEB_PORT)
                .putStringSet("processed_tx_ids", txSet)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist state to SharedPreferences: ${e.message}")
        }
    }

    private fun restoreState() {
        try {
            val deviceContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) createDeviceProtectedStorageContext() else this
            val prefs = deviceContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedState = prefs.getInt("app_state", 0)
            val savedTime = prefs.getInt("session_time_remaining", 0)
            pricePerCoin.value = prefs.getFloat("price_per_coin", 5.0f).toDouble()
            minutesPerCoin.value = prefs.getInt("minutes_per_coin", 30)
            esp32Ip = prefs.getString("esp32_ip", null)
            if (esp32Ip.isNullOrBlank()) {
                val configured = KioskSecurity.getConfiguredEsp32Ip(this)
                if (configured.isNotBlank()) {
                    esp32Ip = configured
                }
            }

            // Restore recent processed transaction IDs to prevent double crediting after reboot/restart
            val savedTxSet = prefs.getStringSet("processed_tx_ids", emptySet()) ?: emptySet()
            if (::coinProcessor.isInitialized) {
                coinProcessor.restoreProcessedTxIds(savedTxSet)
            }

            if (savedTime > 0) {
                sessionTimeRemaining.value = savedTime
                appState.value = if (savedState == 1 || savedState == 3) 3 else 2
                Log.d(TAG, "Restored active session: ${savedTime}s remaining")
            } else {
                appState.value = 0
                sessionTimeRemaining.value = 0
            }
            
            // Always reset uncommitted coinsInserted counter on service boot
            coinsInserted.value = 0
            saveState()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore state: ${e.message}")
        }
    }
    
    private fun getLocalIpAddress(): String {
        var fallbackIp: String? = null
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        val ip = address.hostAddress
                        if (ip != null) {
                            if (networkInterface.name.contains("wlan") || networkInterface.name.contains("eth")) {
                                return ip
                            }
                            if (fallbackIp == null) {
                                fallbackIp = ip
                            }
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return fallbackIp ?: "127.0.0.1"
    }

    private fun verifyChallengeAndSignature(challenge: String, signature: String): Boolean {
        return nanoServer?.verifyChallengeAndSignature(challenge, signature) ?: run {
            val expectedSignature = KioskSecurity.calculateHmac(challenge, getSecretKey())
            KioskSecurity.constantTimeEquals(signature, expectedSignature)
        }
    }

    // ========================================================================
    // MASTER-CONTROLLED TIME CREDITING (DUMB CLIENT ARCHITECTURE)
    // ========================================================================
    @Synchronized
    private fun addTimeFromMaster(seconds: Int, source: String, txId: String? = null, amount: Double = 1.0): Boolean {
        if (!::coinProcessor.isInitialized) return false
        val isStartup = (System.currentTimeMillis() - serviceStartTimeMs < 3000 && appState.value == 0)
        return coinProcessor.processCoinCredit(
            seconds = seconds,
            source = source,
            txId = txId,
            amount = amount,
            isStartupPhase = isStartup
        )
    }

    // ========================================================================
    // ESP32 CONNECTION & DISCOVERY DELEGATES
    // ========================================================================

    fun triggerCandidateDiscovery() {
        if (::esp32Manager.isInitialized) {
            esp32Manager.triggerCandidateDiscovery(deviceIp.value)
        }
    }

    fun probeEsp32Connection(ip: String): Boolean {
        return if (::esp32Manager.isInitialized) esp32Manager.probeEsp32Connection(ip) else false
    }

    // ========================================================================
    // LOCAL HTTP SERVER (PORT 8080)
    // ========================================================================

    private fun startServer() {
        try {
            val delegate = object : KioskServerDelegate {
                override fun getSecretKey(): String = this@KioskService.getSecretKey()

                override fun onHeartbeat(clientIp: String?) {
                    isEsp32Online.value = true
                    lastHeartbeatTime = System.currentTimeMillis()
                    if (!clientIp.isNullOrEmpty() && clientIp != "127.0.0.1") {
                        if (esp32Ip != clientIp) {
                            esp32Ip = clientIp
                            saveState()
                        }
                    }
                }

                override fun getStatusJson(): JSONObject {
                    val (curBat, isChg) = getRealTimeBatteryInfo()
                    return JSONObject().apply {
                        put("device_id", deviceId.value)
                        put("alias", KioskSecurity.getDeviceAlias(applicationContext))
                        put("state", appState.value)
                        put("time_remaining", sessionTimeRemaining.value)
                        put("battery", curBat)
                        put("charging", isChg)
                        put("online", true)
                    }
                }

                override fun getSessionTimeRemaining(): Int = sessionTimeRemaining.value

                override fun getAppState(): Int = appState.value

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
                    sessionTimeRemaining.value += seconds
                    if (sessionTimeRemaining.value <= 0) {
                        sessionTimeRemaining.value = 0
                        appState.value = 0
                    }
                    saveState()
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this@KioskService, "${-seconds / 60} minutes deducted!", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onConfigUpdated(price: Double?, minutes: Int?, deviceName: String?, adminPin: String?) {
                    price?.let { pricePerCoin.value = it }
                    minutes?.let { minutesPerCoin.value = it }
                    deviceName?.let { 
                        val trimmed = it.trim()
                        KioskSecurity.setDeviceAlias(applicationContext, trimmed)
                    }
                    adminPin?.let { if (it.isNotBlank()) KioskSecurity.setAdminPin(applicationContext, it) }
                    saveState()
                    val currentName = KioskSecurity.getDeviceAlias(applicationContext).takeIf { it.isNotBlank() } ?: "Terminal"
                    Log.d(TAG, "Master pushed config update: Price=₱${pricePerCoin.value}, Minutes=${minutesPerCoin.value}m, DeviceName=$currentName, Pin=$adminPin")
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

    // ========================================================================
    // DEDICATED SESSION & VOICE SUPERVISOR TIMER WITH OFFLINE RESILIENCE
    // ========================================================================

    private fun startTimer() {
        scope.launch {
            while (isActive) {
                try {
                    delay(1000)
                    
                    // Session arming / waiting countdown
                    if (appState.value == 1 || appState.value == 3) {
                        if (paymentTimeout.value > 0) {
                            paymentTimeout.value -= 1
                        } else if (paymentTimeout.value == 0 && coinsInserted.value > 0) {
                            finishPayment()
                        } else if (paymentTimeout.value == 0 && coinsInserted.value == 0) {
                            closeSession(sendUnarmToEsp = true)
                            if (appState.value == 3) {
                                appState.value = 2
                            } else {
                                appState.value = 0
                            }
                            saveState()
                        }
                    }
                    
                    // Active session countdown
                    if (appState.value == 2 || appState.value == 3) {
                        if (sessionTimeRemaining.value > 0) {
                            sessionTimeRemaining.value -= 1
                            
                            if (sessionTimeRemaining.value % 60 == 0) {
                                saveState()
                            }

                            when (sessionTimeRemaining.value) {
                                300 -> speakWarning("5 minutes time remaining")
                                180 -> speakWarning("3 minutes time remaining")
                                60 -> speakWarning("1 minute time remaining")
                                10 -> speakWarning("10 seconds time remaining")
                                5 -> speakWarning("Five")
                                4 -> speakWarning("Four")
                                3 -> speakWarning("Three")
                                2 -> speakWarning("Two")
                                1 -> speakWarning("One")
                            }

                            if (sessionTimeRemaining.value == 0) {
                                appState.value = 0 // Lock screen
                                speakWarning("Time expired")
                                saveState()
                                
                                val startMain = Intent(Intent.ACTION_MAIN).apply {
                                    addCategory(Intent.CATEGORY_HOME)
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                try {
                                    startActivity(startMain)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to start HOME activity: ${e.message}")
                                }
                            }
                        }
                    }

                    // Annoying Battery & Charging Reminders Supervisor (Works in all states)
                    if (::systemMonitor.isInitialized) {
                        systemMonitor.checkPeriodicBatteryAlerts()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception in timer loop: ${e.message}")
                }
            }
        }
    }

    // ========================================================================
    // PORT 81 WEBSOCKET ARMING & MASTER TIME PUSH
    // ========================================================================

    private fun closeSession(sendUnarmToEsp: Boolean = false) {
        if (::esp32Manager.isInitialized) {
            esp32Manager.closeSession(sendUnarmToEsp)
        }
    }

    private fun triggerSlotBusy() {
        isSlotBusy.value = true
        slotBusyJob?.cancel()
        slotBusyJob = scope.launch {
            delay(5000)
            isSlotBusy.value = false
        }
    }

    private fun armSlot() {
        if (::esp32Manager.isInitialized) {
            esp32Manager.armSlot(ARMING_TIMEOUT_SECONDS)
        }
    }

    private fun finishPayment() {
        closeSession(sendUnarmToEsp = true)
        if (coinsInserted.value > 0) {
            appState.value = 2 // Unlocked
        } else {
            if (appState.value == 3) {
                appState.value = 2
            } else {
                appState.value = 0
            }
        }
        coinsInserted.value = 0
        saveState()
    }
    
    private fun setupOverlay() {
        overlay = KioskOverlay(
            context = this,
            appStateFlow = appState,
            sessionTimeFlow = sessionTimeRemaining,
            paymentTimeoutFlow = paymentTimeout,
            coinsInsertedFlow = coinsInserted,
            themeIndexFlow = themeIndex,
            isEsp32OnlineFlow = isEsp32Online,
            esp32MacAddressFlow = esp32MacAddress,
            isSlotBusyFlow = isSlotBusy,
            pricePerCoinFlow = pricePerCoin,
            minutesPerCoinFlow = minutesPerCoin,
            deviceIpFlow = deviceIp,
            batteryStatusFlow = systemMonitor.batteryStatus,
            onInsertCoinClick = { 
                if (appState.value == 4) return@KioskOverlay // Prevent inserting coins if unlicensed
                if (appState.value == 2) {
                    appState.value = 3
                } else {
                    appState.value = 1
                }
                coinsInserted.value = 0
                paymentTimeout.value = ARMING_TIMEOUT_SECONDS
                armSlot()
            },
            onDoneClick = {
                finishPayment()
            },
            onThemeChange = {
                themeIndex.value = (themeIndex.value + 1) % 3
            },
            onActivateClick = { code ->
                com.pisophone.kiosk.security.HardwareLockManager.sealToCurrentDevice(this@KioskService)
                com.pisophone.kiosk.security.HardwareLockManager.activateOneYearLicense(this@KioskService, code)
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
                val text = intent.getStringExtra("text") ?: "Arcade OS voice system online and functional."
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
