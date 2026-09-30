package com.pisophone.kiosk.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.pisophone.kiosk.audio.KioskAudioManager
import com.pisophone.kiosk.db.AppDatabase
import com.pisophone.kiosk.db.CoinEvent
import com.pisophone.kiosk.network.Esp32ConnectionManager
import com.pisophone.kiosk.overlay.KioskOverlayCoordinator
import com.pisophone.kiosk.repository.CoinEventRepository
import com.pisophone.kiosk.repository.PaymentRepository
import com.pisophone.kiosk.repository.PaymentResult
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
        private const val ARMING_TIMEOUT_SECONDS = 20
        private const val SERVER_PORT = 8080
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val coinEventRepo: CoinEventRepository = CoinEventRepository(
        AppDatabase.getDatabase(context).coinEventDao()
    )
    val paymentRepo: PaymentRepository = PaymentRepository(
        db = AppDatabase.getDatabase(context),
        context = context,
        onPaymentApplied = { txId, seconds, amount, snapshot ->
            val pesoAmount = if (amount >= 1.0) amount.toInt() else 1
            // 1. Commit is finalized. Publish committed session state through serialized handler:
            val targetState = if (stateManager.appState.value == 0) 1 else null
            val applied = stateManager.applySessionUpdate(snapshot, targetState)
            if (applied) {
                stateManager.paymentTimeout.value = ARMING_TIMEOUT_SECONDS

                if (stateManager.appState.value == 1 || stateManager.appState.value == 3) {
                    stateManager.coinsInserted.value += pesoAmount
                } else if (stateManager.appState.value == 2) {
                    Log.d(TAG, "Coin credited directly to active session: +${seconds}s (₱$pesoAmount)")
                }
                stateManager.saveState()
            }

            scope.launch(Dispatchers.IO) {
                try {
                    coinEventRepo.insertEvent(
                        CoinEvent(
                            txId = txId,
                            secondsAdded = seconds,
                            source = "Piso Coin (₱$pesoAmount)"
                        )
                    )
                    coinEventRepo.deleteOldEvents(500)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to log coin event to audit ledger: ${e.message}")
                }
            }

            // 2. Play sound / update UI feedback ONLY for newly applied payment (separate from state publication)
            audioManager.playCoinSound()
            HardwareFeedback.triggerFlashlight(context, 150L)
            Handler(Looper.getMainLooper()).post {
                val addedMins = seconds / 60
                Toast.makeText(context, "₱$pesoAmount coin accepted! (+${addedMins}m)", Toast.LENGTH_SHORT).show()
            }
        },
        onSessionStateChanged = { snapshot ->
            stateManager.applySessionUpdate(snapshot)
        }
    )

    private val isInitialized = java.util.concurrent.atomic.AtomicBoolean(false)

    private val audioManager = KioskAudioManager(context, scope)

    fun creditPayment(txId: String, seconds: Int, amount: Double): PaymentResult {
        if (!isInitialized.get()) {
            Log.w(TAG, "Rejecting payment credit: KioskEngine initialization in progress")
            return PaymentResult.FAILED
        }
        return paymentRepo.creditPaymentBlocking(txId, seconds, amount)
    }

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
        paymentRepo = paymentRepo,
        armingTimeoutSeconds = ARMING_TIMEOUT_SECONDS,
        getSecretKey = { KioskSecurity.getSharedSecret(context) },
        getRealTimeBatteryInfo = { systemMonitor.getRealTimeBatteryInfo() },
        onCreditPayment = { txId, seconds, amount ->
            creditPayment(txId, seconds, amount)
        },
        onSlotBusyTriggered = { triggerSlotBusy() },
        getAudioManager = { audioManager }
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
        paymentRepo = paymentRepo,
        getSecretKey = { KioskSecurity.getSharedSecret(context) },
        getRealTimeBatteryInfo = { systemMonitor.getRealTimeBatteryInfo() },
        getAudioManager = { audioManager },
        onCreditPayment = { txId, seconds, amount ->
            creditPayment(txId, seconds, amount)
        },
        isReady = { isInitialized.get() }
    )

    private val supervisor = KioskSessionSupervisor(
        context = context,
        scope = scope,
        stateManager = stateManager,
        paymentRepo = paymentRepo,
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

        scope.launch(Dispatchers.IO) {
            try {
                // 1. Restore state and initialize payment database
                stateManager.restoreState()
                paymentRepo.migrateAndInitialize(context)
                isInitialized.set(true)
                Log.i(TAG, "Initialization complete. Payment endpoints are now available.")

                audioManager.initAudioEngine()

                if (!stateManager.esp32Ip.isNullOrBlank()) {
                    esp32Manager.setEsp32Ip(stateManager.esp32Ip)
                }

                if (!KioskActivationManager.isPairingCompleted(context) && !KioskSecurity.isProvisioned(context)) {
                    KioskActivationManager.startSetupWindow(context)
                }

                Handler(Looper.getMainLooper()).post {
                    overlayCoordinator.setupOverlay()
                }

                ensureHttpServerRunning()

                esp32Manager.triggerCandidateDiscovery(stateManager.deviceIp.value)
                esp32Manager.startHeartbeatLoop { stateManager.deviceIp.value }

                supervisor.start()
                startHealthMonitor()

                systemMonitor.registerScreenOffReceiver()
                systemMonitor.registerBatteryMonitor()
            } catch (e: Exception) {
                Log.e(TAG, "Engine start initialization failed: ${e.message}", e)
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
    fun ensureHttpServerRunning(): Boolean {
        if (nanoServer?.isAlive == true) {
            val healthy = checkHttpLoopbackHealth()
            if (healthy) return true
            Log.w(TAG, "NanoHTTPD is alive but loopback health probe failed. Rebuilding listener...")
        }
        try {
            nanoServer?.stop()
        } catch (_: Exception) {}
        return try {
            val server = KioskHttpServer(context, SERVER_PORT, serverCoordinator)
            server.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            nanoServer = server
            Log.i(TAG, "NanoHTTPD HTTP server successfully started/repaired on port $SERVER_PORT")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start/repair NanoHTTPD server: ${e.message}")
            false
        }
    }

    private fun checkHttpLoopbackHealth(): Boolean {
        return try {
            val url = java.net.URL("http://127.0.0.1:$SERVER_PORT/ping")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 1500
            connection.readTimeout = 1500
            connection.requestMethod = "GET"
            try {
                connection.responseCode == 200
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun addTimeFromMaster(seconds: Int, source: String, txId: String?, amount: Double = 1.0): Boolean {
        if (txId.isNullOrBlank()) {
            Log.w(TAG, "Missing transaction ID for coin credit from $source")
            return false
        }
        val result = creditPayment(txId, seconds, amount)
        return result == PaymentResult.APPLIED || result == PaymentResult.ALREADY_APPLIED
    }

    fun triggerCandidateDiscovery() {
        esp32Manager.triggerCandidateDiscovery(stateManager.deviceIp.value)
    }

    fun probeEsp32Connection(ip: String): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            scope.launch(Dispatchers.IO) {
                esp32Manager.probeEsp32Connection(ip)
            }
            return false
        }
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
        val updated = paymentRepo.adjustSessionTimeBlocking(durationSeconds)
        stateManager.applySessionUpdate(
            deadlineMs = updated.sessionExpiryDeadlineMs,
            remainingSeconds = updated.sessionTimeRemaining,
            revision = updated.revision,
            targetAppState = 2
        )
        stateManager.saveState()
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "Admin Bypass Active (${durationSeconds / 60}m Maintenance)", Toast.LENGTH_SHORT).show()
        }
    }

    fun performLockSession() {
        Log.i(TAG, "Lock session requested by admin.")
        val resetState = paymentRepo.resetSessionBlocking()
        stateManager.applySessionUpdate(
            deadlineMs = resetState.sessionExpiryDeadlineMs,
            remainingSeconds = resetState.sessionTimeRemaining,
            revision = resetState.revision,
            targetAppState = 0
        )
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
                            val expiryResult = paymentRepo.expireSessionIfDueBlocking()
                            if (expiryResult.didExpire) {
                                val applied = stateManager.applySessionUpdate(
                                    deadlineMs = expiryResult.sessionState.sessionExpiryDeadlineMs,
                                    remainingSeconds = expiryResult.sessionState.sessionTimeRemaining,
                                    revision = expiryResult.sessionState.revision,
                                    targetAppState = 0
                                )
                                if (applied) {
                                    Log.w(TAG, "Health monitor: Session deadline expired ($deadline <= $nowMonotonic). Forcing lock state.")
                                    stateManager.saveState()
                                } else {
                                    Log.d(TAG, "Health monitor: Skipping stale expiration lock because newer revision is active")
                                }
                            } else {
                                stateManager.applySessionUpdate(
                                    expiryResult.sessionState.sessionExpiryDeadlineMs,
                                    expiryResult.sessionState.sessionTimeRemaining,
                                    expiryResult.sessionState.revision
                                )
                            }
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
