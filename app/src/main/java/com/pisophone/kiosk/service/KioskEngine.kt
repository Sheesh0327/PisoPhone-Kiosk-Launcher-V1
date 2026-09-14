package com.pisophone.kiosk.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.pisophone.kiosk.audio.KioskAudioManager
import com.pisophone.kiosk.db.AppDatabase
import com.pisophone.kiosk.network.Esp32ConnectionManager
import com.pisophone.kiosk.overlay.KioskOverlayCoordinator
import com.pisophone.kiosk.repository.CoinEventRepository
import com.pisophone.kiosk.security.KioskActivationManager
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.server.KioskHttpServer
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

/**
 * Core business orchestrator for the PisoPhone Kiosk.
 * Manages device sessions, coin processing, hardware networking, audio, and health monitoring.
 */
class KioskEngine(
    private val context: Context,
    val stateManager: KioskStateManager
) {
    companion object {
        private const val TAG = "KioskEngine"
        private const val ARMING_TIMEOUT_SECONDS = 15
        private const val SERVER_PORT = 8080
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val coinEventRepo: CoinEventRepository = CoinEventRepository(
        AppDatabase.getDatabase(context).coinEventDao()
    )

    private val audioManager = KioskAudioManager(context, scope)

    private val coinProcessor = CoinProcessor(
        context = context,
        scope = scope,
        coinEventRepo = coinEventRepo,
        onCreditsApplied = { seconds, pesoAmount ->
            val nowMonotonic = android.os.SystemClock.elapsedRealtime()
            val currentDeadline = stateManager.sessionExpiryDeadlineMs.value
            val newDeadline = if (currentDeadline > nowMonotonic) {
                currentDeadline + (seconds * 1000L)
            } else {
                nowMonotonic + (seconds * 1000L)
            }
            stateManager.sessionExpiryDeadlineMs.value = newDeadline
            stateManager.sessionTimeRemaining.value = ((newDeadline - nowMonotonic) / 1000L).toInt()
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
            audioManager.playCoinSound()
            HardwareFeedback.triggerFlashlight(context, 150L)
        }
    )

    private val systemMonitor: KioskSystemMonitor = KioskSystemMonitor(
        context = context,
        scope = scope,
        delegate = object : KioskSystemMonitorDelegate {
            override fun onScreenSleep() { overlayCoordinator.onScreenSleep() }
            override fun onScreenWake() { overlayCoordinator.onScreenWake() }
            override fun getAudioManager(): KioskAudioManager = audioManager
        }
    )

    val overlayCoordinator: KioskOverlayCoordinator = KioskOverlayCoordinator(
        context = context,
        scope = scope,
        stateManager = stateManager,
        batteryStatusFlow = systemMonitor.batteryStatus,
        armingTimeoutSeconds = ARMING_TIMEOUT_SECONDS,
        onArmSlot = { armSlot() },
        onFinishPayment = { finishPayment() }
    )

    private val esp32Coordinator = KioskEsp32Coordinator(
        context = context,
        stateManager = stateManager,
        armingTimeoutSeconds = ARMING_TIMEOUT_SECONDS,
        getSecretKey = { KioskSecurity.getSharedSecret(context) },
        getRealTimeBatteryInfo = { systemMonitor.getRealTimeBatteryInfo() },
        onAddCoinTime = { seconds, source, txId, amount ->
            addTimeFromMaster(seconds, source, txId, amount)
        },
        onSlotBusyTriggered = { triggerSlotBusy() }
    )

    private val esp32Manager = Esp32ConnectionManager(
        context = context,
        scope = scope,
        delegate = esp32Coordinator
    )

    private val serverCoordinator = KioskServerCoordinator(
        context = context,
        stateManager = stateManager,
        coinEventRepo = coinEventRepo,
        getSecretKey = { KioskSecurity.getSharedSecret(context) },
        getRealTimeBatteryInfo = { systemMonitor.getRealTimeBatteryInfo() },
        getAudioManager = { audioManager },
        onAddCoinTime = { seconds, source, txId, amount ->
            addTimeFromMaster(seconds, source, txId, amount)
        }
    )

    private val supervisor = KioskSessionSupervisor(
        context = context,
        scope = scope,
        stateManager = stateManager,
        onSpeakWarning = { speakWarning(it) },
        onFinishPayment = { finishPayment() },
        onCloseSession = { closeSession(it) },
        onCheckBatteryAlerts = { systemMonitor.checkPeriodicBatteryAlerts() }
    )

    private var nanoServer: KioskHttpServer? = null
    private var slotBusyJob: Job? = null
    private var engineStartTimeMs = 0L

    fun start() {
        engineStartTimeMs = System.currentTimeMillis()

        audioManager.initAudioEngine()

        if (!stateManager.esp32Ip.isNullOrBlank()) {
            esp32Manager.setEsp32Ip(stateManager.esp32Ip)
        }

        if (!KioskActivationManager.isPairingCompleted(context) && !KioskSecurity.isProvisioned(context)) {
            KioskActivationManager.startSetupWindow(context)
        }

        overlayCoordinator.setupOverlay()

        try {
            nanoServer = KioskHttpServer(context, SERVER_PORT, serverCoordinator)
            nanoServer?.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.d(TAG, "NanoHTTPD Server listening on port $SERVER_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NanoHTTPD server: ${e.message}")
        }

        esp32Manager.triggerCandidateDiscovery(stateManager.deviceIp.value)
        esp32Manager.startHeartbeatLoop { stateManager.deviceIp.value }

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
                    audioManager.startWaitingMusic()
                } else {
                    audioManager.stopWaitingMusic()
                }
            }
        }
    }

    @Synchronized
    fun addTimeFromMaster(seconds: Int, source: String, txId: String?, amount: Double = 1.0): Boolean {
        val now = System.currentTimeMillis()
        val isStartup = (now - engineStartTimeMs < 3000 && stateManager.appState.value == 0)
        return coinProcessor.processCoinCredit(
            seconds = seconds,
            source = source,
            txId = txId,
            amount = amount,
            isStartupPhase = isStartup
        )
    }

    fun triggerCandidateDiscovery() {
        esp32Manager.triggerCandidateDiscovery(stateManager.deviceIp.value)
    }

    fun probeEsp32Connection(ip: String): Boolean {
        return esp32Manager.probeEsp32Connection(ip)
    }

    fun closeSession(sendUnarmToEsp: Boolean = false) {
        esp32Manager.closeSession(sendUnarmToEsp)
    }

    fun triggerSlotBusy() {
        stateManager.isSlotBusy.value = true
        slotBusyJob?.cancel()
        slotBusyJob = scope.launch {
            delay(5000)
            stateManager.isSlotBusy.value = false
        }
    }

    fun armSlot() {
        esp32Manager.armSlot(ARMING_TIMEOUT_SECONDS)
    }

    fun finishPayment() {
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

    fun speakWarning(text: String) {
        audioManager.speakWarning(text)
    }

    fun performAdminBypass(durationSeconds: Int = 900) {
        if (!KioskActivationManager.isAppAllowedToRun(context)) {
            Log.w(TAG, "Admin bypass rejected: Device is not provisioned.")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "⚠️ Bypass Unavailable: Device requires provisioning.", Toast.LENGTH_LONG).show()
            }
            return
        }
        Log.i(TAG, "Admin bypass granted for $durationSeconds seconds.")
        val nowMonotonic = android.os.SystemClock.elapsedRealtime()
        stateManager.appState.value = 2
        stateManager.sessionExpiryDeadlineMs.value = nowMonotonic + (durationSeconds * 1000L)
        stateManager.sessionTimeRemaining.value = durationSeconds
        stateManager.saveState()
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "Admin Bypass Active (${durationSeconds / 60}m Maintenance)", Toast.LENGTH_SHORT).show()
        }
    }

    fun performLockSession() {
        Log.i(TAG, "Lock session requested by admin.")
        stateManager.appState.value = 0
        stateManager.sessionTimeRemaining.value = 0
        stateManager.sessionExpiryDeadlineMs.value = 0L
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
                        val nowMonotonic = android.os.SystemClock.elapsedRealtime()
                        if (deadline > 0L && nowMonotonic >= deadline) {
                            Log.w(TAG, "Health monitor: Session deadline expired ($deadline <= $nowMonotonic). Forcing lock state.")
                            stateManager.appState.value = 0
                            stateManager.sessionTimeRemaining.value = 0
                            stateManager.sessionExpiryDeadlineMs.value = 0L
                            stateManager.saveState()
                        }
                    }

                    val currentAppState = stateManager.appState.value
                    if (currentAppState == 0 || currentAppState == 1) {
                        if (!overlayCoordinator.isOverlayHealthy()) {
                            Log.w(TAG, "Health monitor: Overlay missing or detached while locked/armed (AppState: $currentAppState). Rebuilding...")
                            overlayCoordinator.remove()
                            overlayCoordinator.setupOverlay()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Health monitor check failed: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        scope.cancel()
        supervisor.stop()
        systemMonitor.shutdown()
        esp32Manager.shutdown()
        audioManager.shutdown()
        nanoServer?.stop()
        overlayCoordinator.remove()
    }
}
