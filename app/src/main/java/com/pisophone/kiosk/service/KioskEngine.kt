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
import com.pisophone.kiosk.network.Esp32Responses
import com.pisophone.kiosk.overlay.KioskOverlayCoordinator
import com.pisophone.kiosk.repository.CoinEventRepository
import com.pisophone.kiosk.repository.PaymentRepository
import com.pisophone.kiosk.repository.PaymentResult
import com.pisophone.kiosk.security.AdminMaintenanceMode
import com.pisophone.kiosk.security.KioskActivationManager
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.server.KioskHttpServer
import com.pisophone.kiosk.system.KioskSystemMonitor
import com.pisophone.kiosk.system.KioskSystemMonitorDelegate
import com.pisophone.kiosk.util.CoinSpeech
import com.pisophone.kiosk.util.DiagnosticsLog
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

/**
 * Core business orchestrator for the PisoPhone Kiosk.
 * Manages device sessions, coin processing, hardware networking, audio, and health monitoring.
 */
class KioskEngine(
    private val context: Context,
    val stateManager: KioskStateManager,
) {
    companion object {
        private const val TAG = "KioskEngine"
        private const val ARMING_TIMEOUT_SECONDS = 20
        private const val SERVER_PORT = 8080

        /** Explicit marker for admin-originated credits/deductions (never a coin). */
        const val ADMIN_TX_PREFIX = "tx-adj-"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val coinEventRepo: CoinEventRepository = CoinEventRepository(
        AppDatabase.getDatabase(context).coinEventDao(),
    )
    val paymentRepo: PaymentRepository = PaymentRepository(
        db = AppDatabase.getDatabase(context),
        context = context,
        onPaymentApplied = { txId, seconds, amount, snapshot ->
            // Explicit admin flag (tx id prefix) instead of guessing from the amount.
            val isAdminAdjustment = txId.startsWith(ADMIN_TX_PREFIX)
            // Zero-amount credits pushed by the Master (complimentary time) are not coins either.
            val isCoin = !isAdminAdjustment && amount > 0.0
            val pesoAmount = if (amount >= 1.0) amount.toInt() else 0
            val currentState = stateManager.appState.value
            // 1. Commit is finalized. Publish committed session state through serialized handler:
            val targetState: Int? = if (isCoin) {
                SessionRules.afterCoinCredit(currentState)
            } else {
                SessionRules.afterNonCoinCredit(currentState)
            }
            if (isCoin) {
                // Publish the arming window BEFORE appState flips to 1 so a supervisor tick in
                // between never sees state 1 with paymentTimeout == 0 and closes the session.
                stateManager.paymentTimeout.value = ARMING_TIMEOUT_SECONDS
            }
            val applied = stateManager.applySessionUpdate(snapshot, targetState)
            if (applied) {
                if (isCoin) {
                    if (SessionRules.isArmed(stateManager.appState.value)) {
                        stateManager.coinsInserted.value += pesoAmount
                    } else if (stateManager.appState.value == SessionState.UNLOCKED.code) {
                        Log.d(TAG, "Coin credited directly to active session: +${seconds}s (₱$pesoAmount)")
                    }
                }
                stateManager.saveState()
            }

            scope.launch(Dispatchers.IO) {
                try {
                    coinEventRepo.insertEvent(
                        CoinEvent(
                            txId = txId,
                            secondsAdded = seconds,
                            source = when {
                                isAdminAdjustment -> "Admin Quick Adjust"
                                !isCoin -> "Master Time Credit"
                                else -> "Piso Coin (₱$pesoAmount)"
                            },
                        ),
                    )
                    coinEventRepo.deleteOldEvents(500)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to log coin event to audit ledger: ${e.message}")
                }
            }

            // 2. Play sound / update UI feedback ONLY for newly applied payment (separate from state publication)
            audioManager.playCoinSound()
            HardwareFeedback.triggerFlashlight(context, 150L)
            val addedMins = seconds / 60
            if (isCoin) speakCoinConfirmation(CoinSpeech.confirmation(pesoAmount, seconds))
            Handler(Looper.getMainLooper()).post {
                if (isAdminAdjustment) {
                    Toast.makeText(context, "+${addedMins}m added by Admin!", Toast.LENGTH_SHORT).show()
                } else if (!isCoin) {
                    Toast.makeText(context, "+${addedMins}m added!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "₱$pesoAmount coin accepted! (+${addedMins}m)", Toast.LENGTH_SHORT).show()
                }
            }
        },
        onSessionStateChanged = { snapshot ->
            val current = stateManager.appState.value
            val targetState = SessionRules.afterDeduct(current, snapshot.remainingSeconds)
            stateManager.applySessionUpdate(snapshot, targetState)
            if (targetState != null) {
                stateManager.saveState()
            }
        },
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
            override fun isSessionActive(): Boolean = SessionRules.isUnlocked(stateManager.appState.value)
        },
    )

    val overlayCoordinator: KioskOverlayCoordinator = KioskOverlayCoordinator(
        context = context,
        scope = scope,
        stateManager = stateManager,
        batteryStatusFlow = systemMonitor.batteryStatus,
        armingTimeoutSeconds = ARMING_TIMEOUT_SECONDS,
        onArmSlot = { armSlot() },
        onFinishPayment = { finishPayment() },
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
        onArmFailedTriggered = { triggerArmFailure(it) },
        getAudioManager = { audioManager },
        onSessionLocked = { cancelArm -> onSessionLocked(cancelArm) },
    )

    private val esp32Manager = Esp32ConnectionManager(
        context = context,
        scope = scope,
        delegate = esp32Coordinator,
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
        isReady = { isInitialized.get() },
        onUnverifiedEsp32Contact = { _ -> esp32Manager.triggerCandidateDiscovery(stateManager.deviceIp.value) },
        onSessionLocked = { cancelArm -> onSessionLocked(cancelArm) },
    )

    private val supervisor = KioskSessionSupervisor(
        context = context,
        scope = scope,
        stateManager = stateManager,
        paymentRepo = paymentRepo,
        onSpeakWarning = { speakWarning(it) },
        onTimeExpired = { audioManager.playTimeUpSound() },
        onFinishPayment = { finishPayment() },
        onCloseSession = { closeSession(it) },
        onCheckBatteryAlerts = { systemMonitor.checkPeriodicBatteryAlerts() },
        onSessionExpired = { cancelArm -> onSessionLocked(cancelArm) },
    )

    private var nanoServer: KioskHttpServer? = null
    private var armFailureJob: Job? = null
    private var engineStartTimeMs = 0L

    fun start() {
        engineStartTimeMs = System.currentTimeMillis()

        scope.launch(Dispatchers.IO) {
            try {
                // 1. Restore state and initialize payment database
                stateManager.restoreState()
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

                // Keep the phone's IP current across DHCP renewals / Wi-Fi reconnects.
                stateManager.startNetworkMonitoring { newIp ->
                    if (newIp != "127.0.0.1") {
                        esp32Manager.triggerCandidateDiscovery(newIp)
                    }
                }

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
                if (SessionRules.isArmed(state)) {
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

    private fun checkHttpLoopbackHealth(): Boolean = try {
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

    /** Repairs the HTTP listener and overlay if unhealthy. Called by the watchdog through the service. */
    fun runHealthRepair() {
        scope.launch(Dispatchers.IO) {
            if (!ensureHttpServerRunning()) Log.w(TAG, "Health repair: HTTP server could not be restarted")
            withContext(Dispatchers.Main) {
                if (!overlayCoordinator.isOverlayHealthy()) {
                    Log.w(TAG, "Health repair: overlay missing/unattached. Rebuilding...")
                    overlayCoordinator.setupOverlay()
                }
            }
        }
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

    /** Shows why the last arm attempt failed on the button for a few seconds. */
    fun triggerArmFailure(failure: Esp32Responses.ArmFailure) {
        stateManager.armFailure.value = failure
        armFailureJob?.cancel()
        armFailureJob = scope.launch {
            delay(5000)
            stateManager.armFailure.value = null
        }
    }

    fun armSlot() {
        esp32Manager.armSlot(ARMING_TIMEOUT_SECONDS)
    }

    fun finishPayment() {
        if (stateManager.coinsInserted.value > 0) audioManager.playDoneSound()
        closeSession(sendUnarmToEsp = true)
        stateManager.appState.value = SessionRules.afterFinishPayment(
            stateManager.appState.value,
            stateManager.coinsInserted.value,
        )
        stateManager.coinsInserted.value = 0
        stateManager.saveState()
    }

    fun speakWarning(text: String) {
        audioManager.speakWarning(text)
    }

    /** Spoken confirmation of a coin, without the flash and vibration of a warning. */
    private fun speakCoinConfirmation(text: String) {
        // Let the coin clink finish first: speech mutes the media stream the clink plays on.
        audioManager.speakAfterSound(text, delayMs = 650L)
    }

    // ------------------------------------------------------------------------
    // Async entry points for UI / receivers (main thread). The perform* functions
    // below do blocking Room work and must never run on the main thread.
    // ------------------------------------------------------------------------

    fun requestAdminBypass(durationSeconds: Int = 900) {
        scope.launch(Dispatchers.IO) { performAdminBypass(durationSeconds) }
    }

    fun requestLockSession() {
        scope.launch(Dispatchers.IO) { performLockSession() }
    }

    fun requestAdminTimeAdjust(secondsDelta: Int) {
        scope.launch(Dispatchers.IO) { performAdminTimeAdjust(secondsDelta) }
    }

    /**
     * Single place for the side effects of a session becoming locked, used by every lock /
     * expiry path (supervisor, health monitor, admin lock/deduct, Master commands, slot lockdown):
     *  - optionally unarm the ESP32 coin slot (when the arm is being cancelled),
     *  - stop customer media playback,
     *  - bring the kiosk launcher (HOME) to the front so the customer app is no longer in use.
     */
    fun onSessionLocked(cancelArm: Boolean) {
        if (cancelArm) {
            closeSession(sendUnarmToEsp = true)
            stateManager.coinsInserted.value = 0
            stateManager.paymentTimeout.value = 0
        }
        stateManager.isArmingInProgress.value = false
        // Locking ends any admin maintenance window (Settings / Wi-Fi config access).
        try {
            AdminMaintenanceMode.end(context)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close admin maintenance window on lock: ${e.message}")
        }
        try {
            audioManager.pauseExternalMedia()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to pause customer media on lock: ${e.message}")
        }
        val startMain = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_HOME)
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        try {
            context.startActivity(startMain)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HOME activity on lock: ${e.message}")
        }
    }

    private fun isArmedState(state: Int): Boolean = SessionRules.isArmed(state)

    fun performAdminBypass(durationSeconds: Int = 900) {
        Log.i(TAG, "Admin bypass granted for $durationSeconds seconds.")
        DiagnosticsLog.add("ADMIN", "bypass for ${durationSeconds}s")
        AdminMaintenanceMode.begin(context, durationSeconds)
        // Repository keeps max(remaining, duration), so an existing paid balance is preserved.
        val updated = paymentRepo.adjustSessionTimeBlocking(durationSeconds)
        // Unlock, but keep an armed coin slot armed (1 -> 3, 3 stays 3).
        val targetState = SessionRules.afterAdminBypass(stateManager.appState.value)
        stateManager.applySessionUpdate(
            deadlineMs = updated.sessionExpiryDeadlineMs,
            remainingSeconds = updated.sessionTimeRemaining,
            revision = updated.revision,
            targetAppState = targetState,
        )
        stateManager.saveState()
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "Admin Bypass Active (${durationSeconds / 60}m Maintenance)", Toast.LENGTH_SHORT).show()
        }
    }

    fun performLockSession() {
        Log.i(TAG, "Lock session requested by admin.")
        DiagnosticsLog.add("ADMIN", "lock session")
        val previousState = stateManager.appState.value
        val resetState = paymentRepo.resetSessionBlocking()
        stateManager.applySessionUpdate(
            deadlineMs = resetState.sessionExpiryDeadlineMs,
            remainingSeconds = resetState.sessionTimeRemaining,
            revision = resetState.revision,
            targetAppState = SessionRules.hardLocked(),
        )
        stateManager.coinsInserted.value = 0
        stateManager.paymentTimeout.value = 0
        stateManager.saveState()
        onSessionLocked(cancelArm = isArmedState(previousState))
    }

    fun performAdminTimeAdjust(secondsDelta: Int) {
        val ts = System.currentTimeMillis()
        if (secondsDelta > 0) {
            val txId = "$ADMIN_TX_PREFIX$ts-${(10000..99999).random()}"
            val res = paymentRepo.creditPaymentBlocking(txId, secondsDelta, 0.0)
            Log.i(TAG, "Admin quick added $secondsDelta seconds: result=$res")
        } else if (secondsDelta < 0) {
            val positiveSeconds = Math.abs(secondsDelta)
            val txId = "${ADMIN_TX_PREFIX}deduct-$ts-${(10000..99999).random()}"
            val previousState = stateManager.appState.value
            val updated = paymentRepo.deductTimeBlocking(positiveSeconds, txId)
            val targetState = SessionRules.afterDeduct(previousState, updated.sessionTimeRemaining)
            val sessionEnded = targetState != null
            stateManager.applySessionUpdate(
                deadlineMs = updated.sessionExpiryDeadlineMs,
                remainingSeconds = updated.sessionTimeRemaining,
                revision = updated.revision,
                targetAppState = targetState,
            )
            stateManager.saveState()
            if (sessionEnded) {
                onSessionLocked(cancelArm = false)
            }
            Log.i(TAG, "Admin quick deducted $positiveSeconds seconds: remaining=${updated.sessionTimeRemaining}s")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "${positiveSeconds / 60}m deducted by Admin!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startHealthMonitor() {
        // Runs on the engine's IO scope; expiry calls the suspend database functions. Only overlay window
        // work hops to Main.
        scope.launch(Dispatchers.IO) {
            var consecutiveUnhealthy = 0
            while (isActive) {
                delay(10_000L)
                try {
                    supervisor.ensureRunning()
                    val appState = stateManager.appState.value
                    if (SessionRules.isUnlocked(appState)) {
                        val deadline = stateManager.sessionExpiryDeadlineMs.value
                        val nowMonotonic = android.os.SystemClock.elapsedRealtime()
                        if (deadline > 0L && nowMonotonic >= deadline) {
                            val expiryResult = paymentRepo.expireSessionIfDue()
                            if (expiryResult.didExpire) {
                                val applied = stateManager.applySessionUpdate(
                                    deadlineMs = expiryResult.sessionState.sessionExpiryDeadlineMs,
                                    remainingSeconds = expiryResult.sessionState.sessionTimeRemaining,
                                    revision = expiryResult.sessionState.revision,
                                    targetAppState = KioskSessionSupervisor.lockedStateFor(appState),
                                )
                                if (applied) {
                                    Log.w(TAG, "Health monitor: Session deadline expired ($deadline <= $nowMonotonic). Forcing lock state.")
                                    stateManager.saveState()
                                    onSessionLocked(cancelArm = false)
                                } else {
                                    Log.d(TAG, "Health monitor: Skipping stale expiration lock because newer revision is active")
                                }
                            } else {
                                stateManager.applySessionUpdate(
                                    expiryResult.sessionState.sessionExpiryDeadlineMs,
                                    expiryResult.sessionState.sessionTimeRemaining,
                                    expiryResult.sessionState.revision,
                                )
                            }
                        }
                    }

                    val currentAppState = stateManager.appState.value
                    if (currentAppState in 0..3) {
                        withContext(Dispatchers.Main) {
                            if (!overlayCoordinator.isOverlayHealthy()) {
                                consecutiveUnhealthy++
                                Log.w(TAG, "Health monitor: Overlay (lock screen or pill) missing/detached (AppState: $currentAppState, strikes=$consecutiveUnhealthy). Repairing...")
                                if (consecutiveUnhealthy >= 2) {
                                    // Re-attaching in place did not help; rebuild from scratch.
                                    overlayCoordinator.remove()
                                    consecutiveUnhealthy = 0
                                }
                                overlayCoordinator.setupOverlay()
                            } else {
                                consecutiveUnhealthy = 0
                            }
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
        stateManager.stopNetworkMonitoring()
        supervisor.stop()
        systemMonitor.shutdown()
        esp32Manager.shutdown()
        audioManager.shutdown()
        nanoServer?.stop()
        overlayCoordinator.remove()
    }
}
