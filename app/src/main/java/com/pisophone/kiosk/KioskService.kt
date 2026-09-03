@file:Suppress("DEPRECATION")
package com.pisophone.kiosk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.pisophone.kiosk.model.BatteryAlertState
import com.pisophone.kiosk.model.BatteryStatus
import com.pisophone.kiosk.overlay.KioskOverlay
import com.pisophone.kiosk.receiver.KioskWatchdogReceiver
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.security.HardwareLockManager
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class KioskService : Service() {
    companion object {
        private const val TAG = "KioskService"
        private const val ARMING_TIMEOUT_SECONDS = 15
        private const val SERVER_PORT = 8080
        private const val ESP32_WEB_PORT = 8055
        private const val ESP32_WS_PORT = 81
        private const val PREFS_NAME = "kiosk_persistent_state"
        private const val HEARTBEAT_TIMEOUT_MS = 20000L // 20s inactivity threshold
        private const val NSD_SERVICE_TYPE = "_http._tcp."

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
    private fun getEsp32HostAndPort(rawIp: String?): Pair<String, Int> {
        if (rawIp.isNullOrBlank()) return Pair("", ESP32_WEB_PORT)
        val parts = rawIp.split(":")
        var host = parts[0]
        val port = if (parts.size > 1) parts[1].toIntOrNull() ?: ESP32_WEB_PORT else ESP32_WEB_PORT
        if (host == "127.0.0.1" || host == "localhost") host = "10.0.2.2"
        return Pair(host, port)
    }

    private val activeChallenges = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val processedCoinTxIds = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var lastCoinCreditedTime = 0L
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

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

    // Battery Health & Annoying Charging Reminders
    private val batteryStatus = MutableStateFlow(BatteryStatus())
    private var batteryReceiver: android.content.BroadcastReceiver? = null
    private var lastBatteryVoiceReminderMs = 0L
    private var previousAlertState = BatteryAlertState.NONE

    private var overlay: KioskOverlay? = null
    private var serviceStartTimeMs = 0L
    private lateinit var coinEventRepo: com.pisophone.kiosk.repository.CoinEventRepository
    
    // Screen Off / Sleep Auto-Clear Handler
    private var screenOffReceiver: android.content.BroadcastReceiver? = null
    private var screenOffTimeMs = 0L
    private var sleepClearJob: Job? = null
    @Volatile private var isCacheClearedDuringSleep = false

    private fun performSleepClear() {
        if (isCacheClearedDuringSleep) return
        isCacheClearedDuringSleep = true
        Log.d(TAG, "Phone screen asleep past timeout. Executing auto cache & session reset.")

        // 1. Clear app cache directory, external cache, and web cookies/storage
        KioskSecurity.clearAppCacheAndData(applicationContext)

        // 2. Reset kiosk paid session to locked screen
        appState.value = 0
        sessionTimeRemaining.value = 0
        coinsInserted.value = 0
        saveState()

        // 3. Relaunch Kiosk Lock Screen Activity/Home
        try {
            val startMain = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(startMain)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch HOME screen after sleep clear: ${e.message}")
        }
    }

    private fun registerScreenOffReceiver() {
        if (screenOffReceiver != null) return
        screenOffReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        val enabled = KioskSecurity.isAutoClearOnSleepEnabled(this@KioskService)
                        val timeoutMinutes = KioskSecurity.getSleepClearTimeoutMinutes(this@KioskService)
                        Log.d(TAG, "Screen OFF detected. Auto-clear enabled=$enabled, timeout=${timeoutMinutes}m")
                        
                        overlay?.onScreenSleep()
                        screenOffTimeMs = System.currentTimeMillis()
                        isCacheClearedDuringSleep = false
                        sleepClearJob?.cancel()

                        if (enabled) {
                            val timeoutMs = timeoutMinutes * 60 * 1000L
                            sleepClearJob = scope.launch {
                                delay(timeoutMs)
                                performSleepClear()
                            }
                        }
                    }
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                        val enabled = KioskSecurity.isAutoClearOnSleepEnabled(this@KioskService)
                        val timeoutMinutes = KioskSecurity.getSleepClearTimeoutMinutes(this@KioskService)
                        val elapsedMs = if (screenOffTimeMs > 0) System.currentTimeMillis() - screenOffTimeMs else 0L
                        val timeoutMs = timeoutMinutes * 60 * 1000L

                        Log.d(TAG, "Screen ON/User Present detected. Elapsed off time=${elapsedMs / 1000}s")

                        // Ensure Keyguard is dismissed and touch input is restored immediately
                        KioskSecurity.wakeScreenUp(this@KioskService)
                        KioskSecurity.dismissKeyguard(this@KioskService)
                        overlay?.onScreenWake()

                        if (enabled && screenOffTimeMs > 0 && elapsedMs >= timeoutMs) {
                            performSleepClear()
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(
                                    this@KioskService,
                                    "Sleep timeout (${timeoutMinutes}m) reached: App cache & session reset.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }

                        sleepClearJob?.cancel()
                        screenOffTimeMs = 0L
                    }
                }
            }
        }

        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenOffReceiver, filter)
    }

    private fun unregisterScreenOffReceiver() {
        try {
            if (screenOffReceiver != null) {
                unregisterReceiver(screenOffReceiver)
                screenOffReceiver = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering screen receiver: ${e.message}")
        }
    }

    private fun registerBatteryMonitor() {
        if (batteryReceiver != null) return
        batteryReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                updateBatteryStatus(intent)
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(batteryReceiver, filter)

        try {
            val initialIntent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            updateBatteryStatus(initialIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read initial battery intent: ${e.message}")
        }
    }

    private fun unregisterBatteryMonitor() {
        try {
            if (batteryReceiver != null) {
                unregisterReceiver(batteryReceiver)
                batteryReceiver = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering battery receiver: ${e.message}")
        }
    }

    private fun getRealTimeBatteryInfo(): Pair<Int, Boolean> {
        try {
            val intent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = intent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1) ?: 0

            var pct = if (level >= 0 && scale > 0) {
                (level * 100 / scale.toFloat()).toInt().coerceIn(0, 100)
            } else {
                -1
            }

            if (pct < 0) {
                val bm = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
                val bmCap = try {
                    bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                } catch (_: Exception) { -1 }
                if (bmCap in 0..100) {
                    pct = bmCap
                }
            }

            val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == android.os.BatteryManager.BATTERY_STATUS_FULL ||
                             plugged > 0

            if (pct >= 0) {
                val alertsEnabled = KioskSecurity.isBatteryAlertsEnabled(this)
                val lowThreshold = KioskSecurity.getLowBatteryThreshold(this)
                val highThreshold = KioskSecurity.getHighBatteryThreshold(this)

                val newAlertState = when {
                    appState.value == 4 -> BatteryAlertState.NONE
                    !alertsEnabled -> BatteryAlertState.NONE
                    pct <= lowThreshold && !isCharging -> BatteryAlertState.LOW_BATTERY_UNPLUGGED
                    pct >= highThreshold && isCharging -> BatteryAlertState.HIGH_BATTERY_PLUGGED
                    else -> BatteryAlertState.NONE
                }
                previousAlertState = newAlertState
                batteryStatus.value = BatteryStatus(level = pct, isCharging = isCharging, alertState = newAlertState)
                return Pair(pct, isCharging)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading real time battery info: ${e.message}")
        }
        return Pair(batteryStatus.value.level, batteryStatus.value.isCharging)
    }

    private fun updateBatteryStatus(intent: Intent?) {
        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1) ?: 0

        val bm = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        val bmCapacity = try {
            bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        } catch (_: Exception) { -1 }

        val pct = when {
            level >= 0 && scale > 0 -> (level * 100 / scale.toFloat()).toInt().coerceIn(0, 100)
            bmCapacity in 0..100 -> bmCapacity
            else -> batteryStatus.value.level.coerceIn(0, 100)
        }
        val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == android.os.BatteryManager.BATTERY_STATUS_FULL ||
                         plugged > 0

        val alertsEnabled = KioskSecurity.isBatteryAlertsEnabled(this)
        val lowThreshold = KioskSecurity.getLowBatteryThreshold(this)
        val highThreshold = KioskSecurity.getHighBatteryThreshold(this)

        val newAlertState = when {
            appState.value == 4 -> BatteryAlertState.NONE
            !alertsEnabled -> BatteryAlertState.NONE
            pct <= lowThreshold && !isCharging -> BatteryAlertState.LOW_BATTERY_UNPLUGGED
            pct >= highThreshold && isCharging -> BatteryAlertState.HIGH_BATTERY_PLUGGED
            else -> BatteryAlertState.NONE
        }

        // Announce transition resolutions immediately for clear feedback
        if (appState.value != 4) {
            if (previousAlertState == BatteryAlertState.LOW_BATTERY_UNPLUGGED && isCharging) {
                speakWarning("Charger connected. Battery charging.")
                playSynthesizedTone(1046, 220) // High C6 confirmation chime
            } else if (previousAlertState == BatteryAlertState.HIGH_BATTERY_PLUGGED && !isCharging) {
                speakWarning("Charger disconnected. Battery protection active.")
                playSynthesizedTone(784, 220) // G5 confirmation chime
            }
        }

        previousAlertState = newAlertState
        batteryStatus.value = BatteryStatus(level = pct, isCharging = isCharging, alertState = newAlertState)
    }

    private fun playAnnoyingLowBatteryTone() {
        scope.launch(Dispatchers.IO) {
            try {
                val sampleRate = 44100
                val durationSec = 0.45
                val numSamples = (sampleRate * durationSec).toInt()
                val buffer = ShortArray(numSamples)
                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    // Urgent alternating siren warble (880 Hz / 1175 Hz)
                    val freq = if ((t * 9).toInt() % 2 == 0) 880.0 else 1174.66
                    val decay = 1.0 - (t / durationSec) * 0.2
                    val sample = (Math.sin(2.0 * Math.PI * freq * t) * 32767 * decay * 0.85).toInt().coerceIn(-32768, 32767)
                    buffer[i] = sample.toShort()
                }
                playPcmBuffer(buffer, sampleRate)
            } catch (_: Exception) {}
        }
    }

    private fun playHighBatteryAttentionTone() {
        scope.launch(Dispatchers.IO) {
            try {
                val sampleRate = 44100
                val durationSec = 0.4
                val numSamples = (sampleRate * durationSec).toInt()
                val buffer = ShortArray(numSamples)
                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    val freq = if (t < 0.2) 1318.51 else 1567.98
                    val env = 1.0 - (t / durationSec) * 0.15
                    val sample = (Math.sin(2.0 * Math.PI * freq * t) * 32767 * env * 0.75).toInt().coerceIn(-32768, 32767)
                    buffer[i] = sample.toShort()
                }
                playPcmBuffer(buffer, sampleRate)
            } catch (_: Exception) {}
        }
    }

    private fun playPcmBuffer(buffer: ShortArray, sampleRate: Int) {
        try {
            val track = android.media.AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(buffer.size * 2)
                .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                .build()
            track.write(buffer, 0, buffer.size)
            track.play()
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    track.stop()
                    track.release()
                } catch (_: Exception) {}
            }, ((buffer.size.toDouble() / sampleRate) * 1000 + 100).toLong())
        } catch (_: Exception) {}
    }

    private fun triggerVibration(pattern: LongArray) {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator?.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, -1)
            }
        } catch (_: Exception) {}
    }
    
    // Native Android Network Service Discovery (NSD) for mDNS
    private var nsdManager: NsdManager? = null
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var isNsdDiscovering = false

    private var tts: android.speech.tts.TextToSpeech? = null
    private var isTtsReady = false
    private var waitingMusicTrack: android.media.AudioTrack? = null
    @Volatile private var isWaitingMusicDesired = false
    private var waitingMusicJob: kotlinx.coroutines.Job? = null
    private val audioLock = Any()
    private var precomputedWaitingBuffer: ShortArray? = null

    // OkHttpClient with zero read timeout for long-lived WebSockets
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    private var coinAudioTrack: android.media.AudioTrack? = null

    private fun initAudioEngine() {
        initTts()
        scope.launch(Dispatchers.Default) {
            precomputedWaitingBuffer = generateWaitingMusicBuffer()
            
            // Pre-initialize coin audio track
            try {
                val sampleRate = 44100
                val durationSec = 0.38
                val numSamples = (durationSec * sampleRate).toInt()
                val buffer = ShortArray(numSamples)
                val splitSample = (0.085 * sampleRate).toInt()
                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate.toDouble()
                    val valSample: Double
                    val env: Double
                    if (i < splitSample) {
                        val f = 987.77 // B5
                        env = 1.0 - (t / 0.085) * 0.15
                        valSample = 0.7 * Math.sin(2.0 * Math.PI * f * t) + 0.25 * Math.sin(4.0 * Math.PI * f * t)
                    } else {
                        val f = 1318.51 // E6
                        val t2 = t - 0.085
                        env = Math.exp(-t2 * 8.5)
                        valSample = 0.75 * Math.sin(2.0 * Math.PI * f * t) + 0.2 * Math.sin(4.0 * Math.PI * f * t) + 0.1 * Math.sin(6.0 * Math.PI * f * t)
                    }
                    val sample = (valSample * env * 32767.0 * 0.88).toInt().coerceIn(-32768, 32767)
                    buffer[i] = sample.toShort()
                }
                val audioAttributes = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val audioFormat = android.media.AudioFormat.Builder()
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                    .build()
                coinAudioTrack = android.media.AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(buffer.size * 2)
                    .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                    .build()
                coinAudioTrack?.write(buffer, 0, buffer.size)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pre-initialize coin audio track", e)
            }
        }
    }

    private fun generateWaitingMusicBuffer(): ShortArray {
        val sampleRate = 44100
        val loopDurationSec = 15.0
        val totalSamples = (loopDurationSec * sampleRate).toInt()
        val buffer = ShortArray(totalSamples)

        val notes = doubleArrayOf(
            523.25, 659.25, 783.99, 1046.50, // C5, E5, G5, C6 (s 1-4)
            880.00, 698.46, 783.99, 659.25,  // A5, F5, G5, E5 (s 5-8)
            587.33, 659.25, 783.99, 880.00,  // D5, E5, G5, A5 (s 9-12)
            987.77, 1046.50, 1174.66         // B5, C6, D6 (s 13-15 urgency)
        )

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate.toDouble()
            val beatIdx = Math.min(14, t.toInt())
            val beatT = t - beatIdx

            // 1. Rhythmic clock tick on every second
            val tickEnv = Math.exp(-beatT * 35.0)
            val tickVal = 0.28 * Math.sin(2.0 * Math.PI * 2200.0 * beatT) * tickEnv

            // 2. Warm bass pulse
            val bassF = when {
                beatIdx < 4 -> 130.81
                beatIdx < 8 -> 174.61
                beatIdx < 12 -> 196.00
                else -> 130.81
            }
            val bassEnv = Math.exp(-beatT * 3.5)
            val bassVal = 0.32 * Math.sin(2.0 * Math.PI * bassF * t) * bassEnv

            // 3. Arpeggiated melody note
            val noteF = notes[beatIdx]
            val subBeat = ((beatT * 4) % 4).toInt()
            val arpMult = when (subBeat) {
                0 -> 1.0
                1 -> 1.25
                2 -> 1.5
                else -> 1.25
            }
            val curF = noteF * arpMult
            val subT = (beatT * 4) - (beatT * 4).toInt()
            val melEnv = Math.exp(-subT * 6.0)
            val melVal = 0.22 * Math.sin(2.0 * Math.PI * curF * t) * melEnv

            var total = (tickVal + bassVal + melVal) * 0.75
            if (t < 0.1) {
                total *= (t / 0.1)
            } else if (t > 14.8) {
                total *= ((15.0 - t) / 0.2)
            }

            val sample = (total * 32767.0).toInt().coerceIn(-32768, 32767)
            buffer[i] = sample.toShort()
        }
        return buffer
    }

    private var pendingSpeechText: String? = null

    private fun initTts() {
        try {
            tts = android.speech.tts.TextToSpeech(applicationContext) { status ->
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    val result = tts?.setLanguage(java.util.Locale.US)
                    if (result == android.speech.tts.TextToSpeech.LANG_MISSING_DATA ||
                        result == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.setLanguage(java.util.Locale.getDefault())
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        val audioAttributes = android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                        tts?.setAudioAttributes(audioAttributes)
                    }
                    tts?.setSpeechRate(1.02f)
                    tts?.setPitch(1.0f)
                    isTtsReady = true
                    Log.i(TAG, "TextToSpeech initialized successfully.")

                    pendingSpeechText?.let { pending ->
                        pendingSpeechText = null
                        speakWarning(pending)
                    }
                } else {
                    Log.w(TAG, "TextToSpeech init failed with status $status")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TTS: ${e.message}")
        }
    }


    private fun triggerAlertHardware() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val vibrator = getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator?.vibrate(android.os.VibrationEffect.createOneShot(1500, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(1500)
                }
            } catch (e: Exception) {}
            
            try {
                val cameraManager = getSystemService(android.content.Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
                val cameraId = cameraManager?.cameraIdList?.firstOrNull()
                if (cameraId != null) {
                    cameraManager.setTorchMode(cameraId as String, true)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        try { cameraManager.setTorchMode(cameraId as String, false) } catch (e: Exception) {}
                    }, 1500)
                }
            } catch (e: Exception) {}
        }
    }

    fun speakWarning(text: String) {
        if (!isTtsReady || tts == null) {
            pendingSpeechText = text
            initTts()
            return
        }

        triggerAlertHardware()
        if (isTtsReady && tts != null) {
            try {
                val params = android.os.Bundle().apply {
                    putFloat(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                }
                tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, params, "kiosk_warning_${System.currentTimeMillis()}")
            } catch (e: Exception) {
                Log.e(TAG, "TTS speak failed: ${e.message}")
                playSynthesizedTone(880, 160)
            }
        } else {
            playSynthesizedTone(880, 160)
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

    private fun playCoinSound() {
        scope.launch(Dispatchers.IO) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                    vibrator?.vibrate(android.os.VibrationEffect.createOneShot(120, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                    vibrator?.vibrate(120)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Vibrator trigger failed: ${e.message}")
            }

            try {
                coinAudioTrack?.let {
                    if (it.state == android.media.AudioTrack.STATE_INITIALIZED) {
                        it.stop()
                        it.reloadStaticData()
                        it.play()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play coin sound", e)
            }
        }
    }

    private fun playSynthesizedTone(freqHz: Int, durationMs: Int) {
        scope.launch(Dispatchers.IO) {
            try {
                val sampleRate = 44100
                val numSamples = (sampleRate * (durationMs / 1000.0)).toInt()
                val buffer = ShortArray(numSamples)
                for (i in 0 until numSamples) {
                    val angle = 2.0 * Math.PI * i / (sampleRate.toDouble() / freqHz)
                    val decay = 1.0 - (i.toDouble() / numSamples.toDouble())
                    buffer[i] = (Math.sin(angle) * 32767 * decay * 0.7).toInt().toShort()
                }
                val track = android.media.AudioTrack.Builder()
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        android.media.AudioFormat.Builder()
                            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(buffer.size * 2)
                    .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                    .build()
                track.write(buffer, 0, buffer.size)
                track.play()
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        track.stop()
                        track.release()
                    } catch (_: Exception) {}
                }, (durationMs + 60).toLong())
            } catch (_: Exception) {}
        }
    }

    private fun startWaitingMusic() {
        isWaitingMusicDesired = true
        waitingMusicJob?.cancel()
        waitingMusicJob = scope.launch(Dispatchers.IO) {
            synchronized(audioLock) {
                if (!isWaitingMusicDesired) return@launch
                stopWaitingMusicInternalLocked()

                val buffer = precomputedWaitingBuffer ?: generateWaitingMusicBuffer().also { precomputedWaitingBuffer = it }
                if (!isWaitingMusicDesired) return@launch

                try {
                    val sampleRate = 44100
                    val audioAttributes = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()

                    val audioFormat = android.media.AudioFormat.Builder()
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                        .build()

                    val track = android.media.AudioTrack.Builder()
                        .setAudioAttributes(audioAttributes)
                        .setAudioFormat(audioFormat)
                        .setBufferSizeInBytes(buffer.size * 2)
                        .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                        .build()

                    track.write(buffer, 0, buffer.size)
                    track.setLoopPoints(0, buffer.size, -1) // Infinite looping until stopped
                    
                    if (isWaitingMusicDesired) {
                        track.play()
                        waitingMusicTrack = track
                        Log.d(TAG, "Waiting music started successfully")
                    } else {
                        track.release()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start waiting music: ${e.message}")
                }
            }
        }
    }

    private fun stopWaitingMusicInternalLocked() {
        try {
            waitingMusicTrack?.let { track ->
                if (track.playState == android.media.AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                    track.stop()
                }
                track.release()
            }
        } catch (_: Exception) {}
        waitingMusicTrack = null
    }

    private fun stopWaitingMusic() {
        isWaitingMusicDesired = false
        waitingMusicJob?.cancel()
        scope.launch(Dispatchers.IO) {
            synchronized(audioLock) {
                stopWaitingMusicInternalLocked()
                Log.d(TAG, "Waiting music stopped successfully")
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        activeInstance = this
        coinEventRepo = com.pisophone.kiosk.repository.CoinEventRepository(
            com.pisophone.kiosk.db.AppDatabase.getDatabase(this).coinEventDao()
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
        initAudioEngine()

        // Acquire MulticastLock to ensure mDNS discovery functions reliably on all Android versions
        // Removed from onCreate - only acquire when discovering
        
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
        startNsdDiscovery()
        startHeartbeatLoop()
        startTimer()
        registerScreenOffReceiver()
        registerBatteryMonitor()

        scope.launch {
            try {
                HardwareLockManager.syncWithBackend(this@KioskService)
            } catch (e: Exception) {
                Log.d(TAG, "Initial hardware lock sync deferred: ${e.message}")
            }
        }

        scope.launch {
            try {
                val recentEvents = coinEventRepo.getLatestEvents(200)
                val now = System.currentTimeMillis()
                for (event in recentEvents) {
                    if (event.txId.isNotBlank()) {
                        processedCoinTxIds[event.txId] = event.timestamp
                    }
                }
                Log.d(TAG, "Hydrated ${recentEvents.size} transaction IDs from Room DB into cache.")
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
            val txSet = processedCoinTxIds.keys.take(20).toSet()
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
            val now = System.currentTimeMillis()
            savedTxSet.forEach { tx ->
                processedCoinTxIds[tx] = now
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

    private fun calculateHmac(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key.toByteArray(), "HmacSHA256")
        mac.init(secretKey)
        val hmacBytes = mac.doFinal(data.toByteArray())
        return hmacBytes.joinToString("") { "%02x".format(it) }
    }

    private fun generateChallenge(): String {
        val token = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        activeChallenges[token] = now
        val iterator = activeChallenges.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > 60000) {
                iterator.remove()
            }
        }
        return token
    }

    private fun verifyChallengeAndSignature(challenge: String, signature: String): Boolean {
        val issueTime = activeChallenges[challenge] ?: return false
        if (System.currentTimeMillis() - issueTime > 60000) {
            activeChallenges.remove(challenge)
            return false
        }
        val expectedSignature = calculateHmac(challenge, getSecretKey())
        if (signature.equals(expectedSignature, ignoreCase = true)) {
            activeChallenges.remove(challenge)
            return true
        }
        return false
    }

    // ========================================================================
    // MASTER-CONTROLLED TIME CREDITING (DUMB CLIENT ARCHITECTURE)
    // ========================================================================
    @Synchronized
    private fun addTimeFromMaster(seconds: Int, source: String, txId: String? = null, amount: Double = 1.0): Boolean {
        val now = System.currentTimeMillis()

        // Boot startup noise guard: ignore un-authenticated pulses in the first 3 seconds after service boot
        if (now - serviceStartTimeMs < 3000 && appState.value == 0 && txId.isNullOrBlank()) {
            Log.d(TAG, "Discarded boot pulse noise from $source during startup phase.")
            return false
        }
        
        // Clean up expired transaction IDs older than 2 minutes
        val txIterator = processedCoinTxIds.entries.iterator()
        while (txIterator.hasNext()) {
            val entry = txIterator.next()
            if (now - entry.value > 120_000) {
                txIterator.remove()
            }
        }

        // 1. Transaction ID Deduplication
        if (!txId.isNullOrBlank()) {
            if (processedCoinTxIds.containsKey(txId)) {
                Log.d(TAG, "Coin transaction $txId already credited, ignoring duplicate.")
                return true
            }
            processedCoinTxIds[txId] = now
        } else {
            // 2. Hardware / Network Jitter Debounce Lockout (250ms) ONLY if no txId
            if (now - lastCoinCreditedTime < 250) {
                Log.d(TAG, "Duplicate coin burst (<250ms) from $source discarded.")
                return false
            }
        }
        
        lastCoinCreditedTime = now
        val pesoVal = if (amount >= 1.0) amount.toInt() else 1
        Log.d(TAG, "Master credited +$seconds seconds (₱$pesoVal) via $source (txId=${txId ?: "none"})")

        coinsInserted.value += pesoVal
        // Directly add the Master's calculated seconds
        sessionTimeRemaining.value += seconds
        paymentTimeout.value = ARMING_TIMEOUT_SECONDS // Reset payment arming window
        
        val eventTxId = txId ?: UUID.randomUUID().toString()
        scope.launch {
            try {
                coinEventRepo.insertEvent(com.pisophone.kiosk.db.CoinEvent(
                    txId = eventTxId,
                    secondsAdded = seconds,
                    source = "$source (₱$pesoVal)"
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log coin event to audit ledger: ${e.message}")
            }
        }

        if (appState.value == 0) {
            appState.value = 1
        } else if (appState.value == 2) {
            appState.value = 3
        }

        saveState()
        playCoinSound()
        triggerFlashlight()
        Handler(Looper.getMainLooper()).post {
            val addedMins = seconds / 60
            Toast.makeText(this@KioskService, "₱$pesoVal coin accepted! (+${addedMins}m)", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun parseQueryParams(uriString: String): Map<String, String> {
        val queryIndex = uriString.indexOf('?')
        if (queryIndex == -1) return emptyMap()
        val rawQuery = uriString.substring(queryIndex + 1).split(" ")[0].trim()
        val params = mutableMapOf<String, String>()
        for (pair in rawQuery.split("&")) {
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) {
                val key = try { URLDecoder.decode(parts[0], "UTF-8") } catch (_: Exception) { parts[0] }
                val value = try { URLDecoder.decode(parts[1], "UTF-8") } catch (_: Exception) { parts[1] }
                params[key] = value
            }
        }
        return params
    }

    // ========================================================================
    // NATIVE ANDROID NETWORK SERVICE DISCOVERY (mDNS "pisokiosk")
    // ========================================================================

    private var cachedFlashlightCameraId: String? = null
    private var isFlashlightCached = false

    private fun triggerFlashlight() {
        scope.launch(Dispatchers.IO) {
            try {
                val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                if (!isFlashlightCached) {
                    cachedFlashlightCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                        cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    }
                    isFlashlightCached = true
                }
                val cameraId = cachedFlashlightCameraId
                if (cameraId != null) {
                    cameraManager.setTorchMode(cameraId as String, true)
                    kotlinx.coroutines.delay(150)
                    cameraManager.setTorchMode(cameraId as String, false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to trigger flashlight", e)
            }
        }
    }

    private fun startNsdDiscovery() {
        if (nsdManager == null) {
            nsdManager = getSystemService(Context.NSD_SERVICE) as? NsdManager
        }
        stopNsdDiscovery()
        
        // Acquire MulticastLock only during active discovery to save battery
        if (multicastLock == null) {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            multicastLock = wifi?.createMulticastLock("KioskMDNSLock")
            multicastLock?.setReferenceCounted(false)
        }
        if (multicastLock?.isHeld == false) {
            multicastLock?.acquire()
        }

        nsdDiscoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "mDNS Service discovery started for $regType")
                isNsdDiscovering = true
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "mDNS Service discovered: ${service.serviceName} (${service.serviceType})")
                resolveNsdService(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "mDNS Service lost: ${service.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "mDNS Discovery stopped: $serviceType")
                isNsdDiscovering = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "mDNS Discovery start failed: $errorCode")
                stopNsdDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "mDNS Discovery stop failed: $errorCode")
            }
        }

        try {
            nsdManager?.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, nsdDiscoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate NSD discovery: ${e.message}")
        }
    }

    private fun resolveNsdService(service: NsdServiceInfo) {
        try {
            nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "mDNS Service resolve failed: $errorCode")
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val resolvedHost = serviceInfo.host?.hostAddress
                    Log.d(TAG, "mDNS Service resolved: ${serviceInfo.serviceName} at $resolvedHost:${serviceInfo.port}")
                    if (!resolvedHost.isNullOrBlank() && resolvedHost != "127.0.0.1") {
                        onEsp32Discovered(resolvedHost)
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "ResolveService error: ${e.message}")
        }
    }

    private fun stopNsdDiscovery() {
        if (isNsdDiscovering && nsdDiscoveryListener != null) {
            try {
                nsdManager?.stopServiceDiscovery(nsdDiscoveryListener)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping NSD discovery: ${e.message}")
            }
            nsdDiscoveryListener = null
            isNsdDiscovering = false
        }
        if (multicastLock?.isHeld == true) {
            multicastLock?.release()
        }
    }

    fun triggerCandidateDiscovery() {
        startNsdDiscovery()
        scope.launch(Dispatchers.IO) {
            probeCandidateIps()
        }
    }

    private fun probeCandidateIps() {
        val candidates = mutableListOf<String>()
        val configured = KioskSecurity.getConfiguredEsp32Ip(this)
        if (configured.isNotBlank()) candidates.add(configured)
        
        esp32Ip?.let { if (it.isNotBlank() && !candidates.contains(it)) candidates.add(it) }
        
        val local = deviceIp.value
        if (local.isNotBlank() && local != "127.0.0.1" && local.contains(".")) {
            val subnet = local.substringBeforeLast(".")
            val gw = "$subnet.1"
            if (!candidates.contains(gw)) candidates.add(gw)
        }
        
        // Standard AP mode default IP for ESP32
        if (!candidates.contains("192.168.4.1")) candidates.add("192.168.4.1")
        // Android Emulator loopback alias
        if (!candidates.contains("10.0.2.2")) candidates.add("10.0.2.2")

        for (cand in candidates) {
            val success = probeEsp32Connection(cand)
            if (success) {
                Log.i(TAG, "Candidate probe succeeded for ESP32 at $cand")
                break
            }
        }
    }

    fun probeEsp32Connection(ip: String): Boolean {
        val (host, esp32Port) = getEsp32HostAndPort(ip)
        val ts = System.currentTimeMillis().toString()
        val sig = generateSignature(deviceId.value, ts, getSecretKey())
        
        // 1. Try /identify
        try {
            val req = Request.Builder()
                .url("http://$host:${esp32Port}/identify")
                .build()
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string() ?: ""
                resp.close()
                if (body.contains("price") || body.contains("minutes") || body.contains("piso") || body.contains("esp32")) {
                    onEsp32Discovered(host)
                    return true
                }
            } else {
                resp.close()
            }
        } catch (_: Exception) {}

        // 2. Try /heartbeat
        try {
            val (curBat, isChg) = getRealTimeBatteryInfo()
            val req = Request.Builder()
                .url("http://$host:${esp32Port}/heartbeat?device_id=${deviceId.value}&ip=${if (deviceIp.value == "127.0.0.1") "" else deviceIp.value}&time=${sessionTimeRemaining.value}&state=${appState.value}&battery=$curBat&charging=${if (isChg) 1 else 0}&ts=$ts&sig=$sig")
                .build()
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                resp.close()
                onEsp32Discovered(host)
                return true
            }
            resp.close()
        } catch (_: Exception) {}

        return false
    }

    private fun onEsp32Discovered(ip: String) {
        val (ipHost, esp32Port) = getEsp32HostAndPort(ip)
        esp32Ip = ip
        stopNsdDiscovery()
        isEsp32Online.value = true
        lastHeartbeatTime = System.currentTimeMillis()
        saveState()
        Log.d(TAG, "[+] ESP32 Master bound at $ipHost")

        // Fetch master config & register terminal IP
        scope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("http://$ipHost:${esp32Port}/identify")
                    .build()
                val resp = httpClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JSONObject(body)
                    if (json.has("price")) pricePerCoin.value = json.optDouble("price", 5.0)
                    if (json.has("minutes")) minutesPerCoin.value = json.optInt("minutes", 30)
                    if (json.has("device_name")) {
                        val devName = json.optString("device_name", "").trim()
                        if (devName.isNotEmpty()) {
                            KioskSecurity.setDeviceAlias(this@KioskService, devName)
                        }
                    }
                    saveState()
                }
                resp.close()
            } catch (_: Exception) {}

            try {
                val (curBat, isChg) = getRealTimeBatteryInfo()
                val ts = System.currentTimeMillis().toString()
                val sig = generateSignature(deviceId.value, ts, getSecretKey())
                val announceReq = Request.Builder()
                    .url("http://$ipHost:${esp32Port}/heartbeat?device_id=${deviceId.value}&ip=${if (deviceIp.value == "127.0.0.1") "" else deviceIp.value}&time=${sessionTimeRemaining.value}&state=${appState.value}&battery=$curBat&charging=${if (isChg) 1 else 0}&ts=$ts&sig=$sig")
                    .build()
                val resp = httpClient.newCall(announceReq).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    if (body.isNotBlank()) {
                        try {
                            val json = JSONObject(body)
                            if (json.has("device_name")) {
                                val devName = json.optString("device_name", "").trim()
                                if (devName.isNotEmpty()) {
                                    KioskSecurity.setDeviceAlias(this@KioskService, devName)
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
                resp.close()
            } catch (_: Exception) {}
        }
    }

    private fun startHeartbeatLoop() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    // Periodically update IP to ensure it's correct if network connects after boot
                    deviceIp.value = getLocalIpAddress()

                    val targetIp = esp32Ip ?: KioskSecurity.getConfiguredEsp32Ip(this@KioskService).takeIf { it.isNotBlank() }

                    if (!targetIp.isNullOrBlank()) {
                        val (host, esp32Port) = getEsp32HostAndPort(targetIp)
                        val ts = System.currentTimeMillis().toString()
                        val sig = generateSignature(deviceId.value, ts, getSecretKey())
                        
                        val (curBat, isChg) = getRealTimeBatteryInfo()
                        
                        val req = Request.Builder()
                            .url("http://$host:${esp32Port}/heartbeat?device_id=${deviceId.value}&ip=${if (deviceIp.value == "127.0.0.1") "" else deviceIp.value}&time=${sessionTimeRemaining.value}&state=${appState.value}&battery=$curBat&charging=${if (isChg) 1 else 0}&ts=$ts&sig=$sig")
                            .build()
                        try {
                            val response = httpClient.newCall(req).execute()
                            if (response.isSuccessful) {
                                isEsp32Online.value = true
                                lastHeartbeatTime = System.currentTimeMillis()
                                val body = response.body?.string() ?: ""
                                if (body.isNotBlank()) {
                                    try {
                                        val json = JSONObject(body)
                                        if (json.has("mac")) esp32MacAddress.value = json.optString("mac", "")
                                        if (json.optString("status") == "unlicensed") {
                                            if (appState.value != 4) appState.value = 4
                                        } else if (appState.value == 4) {
                                            appState.value = 0
                                            com.pisophone.kiosk.security.HardwareLockManager.sealToCurrentDevice(this@KioskService)
                                        }
                                        if (json.has("device_name")) {
                                            val alias = json.optString("device_name", "").trim()
                                            if (alias.isNotEmpty()) {
                                                val current = KioskSecurity.getDeviceAlias(this@KioskService)
                                                if (current != alias) {
                                                    KioskSecurity.setDeviceAlias(this@KioskService, alias)
                                                    Log.d(TAG, "[+] Synchronized device nickname from Master: $alias")
                                                }
                                            }
                                        }
                                        if (json.has("price")) pricePerCoin.value = json.optDouble("price", 5.0)
                                        if (json.has("minutes")) minutesPerCoin.value = json.optInt("minutes", 30)
                                    } catch (_: Exception) {}
                                }
                            }
                            response.close()
                        } catch (e: Exception) {
                            if (System.currentTimeMillis() - lastHeartbeatTime > HEARTBEAT_TIMEOUT_MS) {
                                isEsp32Online.value = false
                                if (!isNsdDiscovering) {
                                    startNsdDiscovery()
                                }
                            }
                        }
                    } else {
                        probeCandidateIps()
                        if (!isNsdDiscovering) {
                            startNsdDiscovery()
                        }
                    }
                } catch (e: Exception) {
                    // Log loop errors gracefully
                }
                delay(4000) // Poll every 4s for quick state syncing
            }
        }
    }

    // ========================================================================
    // LOCAL HTTP SERVER (PORT 8080)
    // ========================================================================

    private inner class KioskHttpServer : NanoHTTPD(SERVER_PORT) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val params = session.parameters.mapValues { it.value.firstOrNull() ?: "" }
            
            // Public status & heartbeat endpoints (no prior HMAC challenge exchange needed)
            if (uri == "/heartbeat" || uri == "/ping") {
                isEsp32Online.value = true
                lastHeartbeatTime = System.currentTimeMillis()
                val clientIp = session.headers["remote-addr"] ?: session.headers["http-client-ip"]
                if (!clientIp.isNullOrEmpty() && clientIp != "127.0.0.1") {
                    if (esp32Ip != clientIp) {
                        esp32Ip = clientIp
                        saveState()
                    }
                }
                return newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
            }
            if (uri == "/identify" || uri == "/status") {
                isEsp32Online.value = true
                lastHeartbeatTime = System.currentTimeMillis()
                val (curBat, isChg) = getRealTimeBatteryInfo()
                val json = JSONObject().apply {
                    put("device_id", deviceId.value)
                    put("alias", KioskSecurity.getDeviceAlias(applicationContext))
                    put("state", appState.value)
                    put("time_remaining", sessionTimeRemaining.value)
                    put("battery", curBat)
                    put("charging", isChg)
                    put("online", true)
                }
                return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
            }
            if (uri == "/challenge" || uri == "/heartbeat_challenge") {
                return newFixedLengthResponse(Response.Status.OK, "text/plain", generateChallenge())
            }
            if (uri == "/get_time") {
                return newFixedLengthResponse(Response.Status.OK, "text/plain", sessionTimeRemaining.value.toString())
            }
            if (uri == "/state") {
                return newFixedLengthResponse(Response.Status.OK, "text/plain", appState.value.toString())
            }
            if (uri == "/audit") {
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
                    newFixedLengthResponse(Response.Status.OK, "application/json", jsonArray.toString())
                }
            }
            
            // Protected action endpoints (require cryptographic HMAC authentication)
            if (!HardwareLockManager.isHardwareAuthorized(applicationContext)) {
                Log.e(TAG, "Rejecting HTTP action: Hardware lock is active on unauthorized device.")
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Hardware lock active on unauthorized device")
            }

            if (uri == "/coin") {
                val txId = params["tx_id"] ?: params["nonce"] ?: UUID.randomUUID().toString()
                val seconds = params["seconds"]?.toIntOrNull() ?: (minutesPerCoin.value * 60)
                val challenge = params["challenge"]
                val signature = params["signature"] ?: params["sig"]
                val ts = params["ts"] ?: params["timestamp"]

                var isAuthorized = false
                if (challenge != null && signature != null && verifyChallengeAndSignature(challenge, signature)) {
                    isAuthorized = true
                } else if (ts != null && signature != null) {
                    val expected1 = generateSignature(deviceId.value, ts, getSecretKey())
                    val expected2 = calculateHmac("$txId:$ts", getSecretKey())
                    if (signature.equals(expected1, ignoreCase = true) || signature.equals(expected2, ignoreCase = true)) {
                        isAuthorized = true
                    }
                } else if (signature != null) {
                    val expected = calculateHmac(txId, getSecretKey())
                    if (signature.equals(expected, ignoreCase = true)) {
                        isAuthorized = true
                    }
                }

                if (isAuthorized) {
                    val amount = params["amount"]?.toDoubleOrNull() ?: pricePerCoin.value
                    addTimeFromMaster(seconds, "HTTP /coin", txId, amount)
                    return newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
                } else {
                    return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Invalid signature or challenge")
                }
            }

            val challenge = params["challenge"]
            val signature = params["signature"]
            if (challenge == null || signature == null || !verifyChallengeAndSignature(challenge, signature)) {
                return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Invalid challenge")
            }
            
            return when (uri) {
                "/add_time" -> {
                    val minutes = params["minutes"]?.toIntOrNull() ?: 0
                    val secondsParam = params["seconds"]?.toIntOrNull()
                    val seconds = secondsParam ?: (minutes * 60)
                    val amount = params["amount"]?.toDoubleOrNull() ?: (if (minutes > 0) ((minutes.toDouble() / minutesPerCoin.value) * pricePerCoin.value).coerceAtLeast(1.0) else 1.0)
                    val txId = params["tx_id"]
                    if (seconds > 0) {
                        addTimeFromMaster(seconds, "HTTP /add_time", if (!txId.isNullOrBlank()) txId else null, amount)
                    } else if (seconds < 0) {
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
                    newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
                }
                "/config" -> {
                    params["price"]?.toDoubleOrNull()?.let { pricePerCoin.value = it }
                    params["minutes"]?.toIntOrNull()?.let { minutesPerCoin.value = it }
                    params["device_name"]?.let { 
                        val trimmed = it.trim()
                        KioskSecurity.setDeviceAlias(applicationContext, trimmed)
                    }
                    params["admin_pin"]?.let { if (it.isNotBlank()) KioskSecurity.setAdminPin(applicationContext, it) }
                    saveState()
                    val currentName = KioskSecurity.getDeviceAlias(applicationContext).takeIf { it.isNotBlank() } ?: "Terminal"
                    Log.d(TAG, "Master pushed config update: Price=₱${pricePerCoin.value}, Minutes=${minutesPerCoin.value}m, DeviceName=$currentName, Pin=${params["admin_pin"]}")
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this@KioskService, "Config Synced: $currentName", Toast.LENGTH_SHORT).show()
                    }
                    newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
                }
                "/trigger_action" -> {
                    val actionType = params["action"]
                    Handler(Looper.getMainLooper()).post {
                        val runVibrate = {
                            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(1500, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                            } else {
                                @Suppress("DEPRECATION")
                                vibrator?.vibrate(1500)
                            }
                        }
                        val runSound = {
                            val ringtone = android.media.RingtoneManager.getRingtone(applicationContext, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION))
                            ringtone?.play()
                        }
                        val runFlash = {
                            try {
                                val cameraManager = getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
                                val cameraId = cameraManager?.cameraIdList?.firstOrNull()
                                if (cameraId != null) {
                                    cameraManager.setTorchMode(cameraId as String, true)
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        try { cameraManager.setTorchMode(cameraId as String, false) } catch (e: Exception) {}
                                    }, 1500)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to toggle torch: ${e.message}")
                            }
                            Toast.makeText(this@KioskService, "Device Identified: ${KioskSecurity.getDeviceAlias(applicationContext)}", Toast.LENGTH_LONG).show()
                        }
                        
                        when (actionType) {
                            "vibrate" -> runVibrate()
                            "sound" -> runSound()
                            "flash" -> runFlash()
                            "locate" -> {
                                runVibrate()
                                runSound()
                                runFlash()
                            }
                            "factory_reset" -> {
                                KioskSecurity.factoryResetDevice(applicationContext)
                            }
                        }
                    }
                    newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
                }
                "/crash" -> {
                    try {
                        val logDir = this@KioskService.getExternalFilesDir(null) ?: this@KioskService.filesDir
                        val file = File(logDir, "crash.log")
                        if (file.exists()) {
                            newFixedLengthResponse(Response.Status.OK, "text/plain", file.readText())
                        } else {
                            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No crash log found")
                        }
                    } catch (e: Exception) {
                        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.toString())
                    }
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        }
    }

    private fun startServer() {
        try {
            nanoServer = KioskHttpServer()
            nanoServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
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
                    val currentBattery = batteryStatus.value
                    val now = System.currentTimeMillis()
                    if (appState.value != 4) {
                        if (currentBattery.alertState == BatteryAlertState.LOW_BATTERY_UNPLUGGED) {
                            // Annoying low battery reminder every 15 seconds
                            if (now - lastBatteryVoiceReminderMs >= 15000L) {
                                lastBatteryVoiceReminderMs = now
                                playAnnoyingLowBatteryTone()
                                triggerVibration(longArrayOf(0, 200, 100, 200, 100, 400))
                                speakWarning("Warning! Battery is very low at ${currentBattery.level} percent. Please connect the charger immediately to prevent shutdown.")
                            }
                        } else if (currentBattery.alertState == BatteryAlertState.HIGH_BATTERY_PLUGGED) {
                            // Annoying high battery reminder every 20 seconds
                            if (now - lastBatteryVoiceReminderMs >= 20000L) {
                                lastBatteryVoiceReminderMs = now
                                playHighBatteryAttentionTone()
                                triggerVibration(longArrayOf(0, 150, 80, 150))
                                speakWarning("Attention! Battery has reached ${currentBattery.level} percent. Please disconnect the charger now to protect battery health.")
                            }
                        }
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

    private var activeWebSocket: WebSocket? = null

    private fun generateSignature(deviceId: String, ts: String, secret: String): String {
        val payload = "$deviceId:$ts"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        val hash = mac.doFinal(payload.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun closeSession(sendUnarmToEsp: Boolean = false) {
        if (sendUnarmToEsp) {
            try {
                activeWebSocket?.send("DONE")
            } catch (e: Exception) {}
        }
        try {
            activeWebSocket?.close(1000, "Session closed")
        } catch (e: Exception) {}
        activeWebSocket = null
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
        closeSession(sendUnarmToEsp = false)
        var ip = esp32Ip ?: "10.0.2.2"
        if (ip == "127.0.0.1" || ip == "localhost") {
            ip = "10.0.2.2"
        }

        val ts = System.currentTimeMillis().toString()
        val sig = generateSignature(deviceId.value, ts, getSecretKey())
        
        val wsUrl = "ws://$ip:$ESP32_WS_PORT/ws?device_id=${deviceId.value}&ts=$ts&sig=$sig"
        Log.d(TAG, "Connecting to Master WebSocket on Port 81: $wsUrl")

        val request = Request.Builder().url(wsUrl).build()

        activeWebSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isEsp32Online.value = true
                lastHeartbeatTime = System.currentTimeMillis()
                Log.d(TAG, "Port 81 WebSocket connected & slot ARMED successfully")
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this@KioskService, "Coin slot locked (Ready for coin - ${ARMING_TIMEOUT_SECONDS}s)", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Master WebSocket onMessage (Port 81): $text")
                try {
                    val json = JSONObject(text)
                    val event = json.optString("event", "")
                    
                    if (event == "ADD_TIME" || event == "COIN_DETECTED") {
                        if (!HardwareLockManager.isAppAllowedToRun(applicationContext)) {
                            Log.e(TAG, "Hardware Lock or Expired Trial active: Discarding coin event on locked device.")
                            return
                        }
                        val seconds = json.optInt("seconds", minutesPerCoin.value * 60)
                        val amount = json.optDouble("amount", pricePerCoin.value)
                        val txId = json.optString("tx_id", "")
                        Log.d(TAG, "Received $event via WebSocket: seconds=$seconds, amount=₱$amount, tx_id=$txId")
                        addTimeFromMaster(seconds, "WebSocket Port 81", if (txId.isNotBlank()) txId else null, amount)
                        paymentTimeout.value = ARMING_TIMEOUT_SECONDS
                    } else if (event == "TIMEOUT" || event == "CLOSED") {
                        Log.d(TAG, "Received $event event from ESP32 WebSocket")
                        closeSession(sendUnarmToEsp = false)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing WebSocket message: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: 0
                Log.e(TAG, "WebSocket failure (HTTP $code): ${t.message}")
                if (code == 409) {
                    Log.e(TAG, "Slot is BUSY with another session (HTTP 409)")
                    triggerSlotBusy()
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this@KioskService, "Slot is currently busy with another device.", Toast.LENGTH_LONG).show()
                    }
                    if (appState.value == 3) {
                        appState.value = 2
                    } else {
                        appState.value = 0
                    }
                } else {
                    isEsp32Online.value = false
                }
                closeSession(sendUnarmToEsp = false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed (code=$code, reason=$reason)")
                activeWebSocket = null
            }
        })
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
            batteryStatusFlow = batteryStatus,
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
                scope.launch(Dispatchers.IO) {
                    try {
                        val targetIp = esp32Ip ?: KioskSecurity.getConfiguredEsp32Ip(this@KioskService).takeIf { it.isNotBlank() }
                        if (!targetIp.isNullOrBlank()) {
                            val (host, esp32Port) = getEsp32HostAndPort(targetIp)
                            val bodyReq = okhttp3.FormBody.Builder().add("code", code).build()
                            val req = Request.Builder()
                                .url("http://$host:${esp32Port}/activate")
                                .post(bodyReq)
                                .build()
                            httpClient.newCall(req).execute().close()
                        }
                    } catch (e: Exception) {}
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
        unregisterScreenOffReceiver()
        unregisterBatteryMonitor()
        KioskWatchdogReceiver.scheduleWatchdog(applicationContext)
        stopNsdDiscovery()
        multicastLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        stopWaitingMusic()
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) {}
        nanoServer?.stop()
        overlay?.remove()
    }
}
