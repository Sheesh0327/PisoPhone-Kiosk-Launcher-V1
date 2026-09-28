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
import com.pisophone.kiosk.repository.PaymentRepository
import com.pisophone.kiosk.repository.PaymentResult
import com.pisophone.kiosk.security.KioskActivationManager
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.system.KioskSystemMonitor
import com.pisophone.kiosk.system.KioskSystemMonitorDelegate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

enum class InitializationState {
    UNINITIALIZED,
    INITIALIZING,
    READY,
    FAILED
}

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
        private const val MAX_INIT_ATTEMPTS = 5
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val initializationState = MutableStateFlow(InitializationState.UNINITIALIZED)
    val isEngineReady: Boolean get() = initializationState.value == InitializationState.READY

    private val coinEventRepo: CoinEventRepository = CoinEventRepository(
        AppDatabase.getDatabase(context).coinEventDao()
    )
    val paymentRepo: PaymentRepository = PaymentRepository(
        db = AppDatabase.getDatabase(context),
        context = context
    )

    private val isInitialized = java.util.concurrent.atomic.AtomicBoolean(false)

    @androidx.annotation.VisibleForTesting
    fun setInitializedForTesting(initialized: Boolean) {
        isInitialized.set(initialized)
    }

    private val audioManager = KioskAudioManager(context, scope)

    private val creditNotifier = KioskCreditNotifier(
        context = context,
        scope = scope,
        stateManager = stateManager,
        coinEventRepo = coinEventRepo,
        audioManager = audioManager,
        armingTimeoutSeconds = ARMING_TIMEOUT_SECONDS
    )

    fun creditPayment(
        txId: String,
        seconds: Int,
        amount: Double,
        operationKind: String = "COIN",
        coinAmount: Int = amount.toInt(),
        pricePerCoin: Double = 0.0,
        boxInstallationEpoch: Long = 0L,
        phonePairingEpoch: Long = 0L,
        remainingMs: Long = 15000L
    ): PaymentResult {
        if (!isInitialized.get()) {
            Log.w(TAG, "Rejecting payment credit: KioskEngine initialization in progress")
            return PaymentResult.FAILED
        }
        val outcome = paymentRepo.creditPaymentBlocking(
            txId = txId,
            seconds = seconds,
            amount = amount,
            operationKind = operationKind,
            coinAmount = coinAmount,
            pricePerCoin = pricePerCoin,
            boxInstallationEpoch = boxInstallationEpoch,
            phonePairingEpoch = phonePairingEpoch
        )
        if (outcome.result == PaymentResult.APPLIED && outcome.snapshot != null) {
            creditNotifier.publishCommittedCreditSnapshot(txId, seconds, amount, operationKind, outcome.snapshot, remainingMs)
        }
        return outcome.result
    }

    fun deductPayment(
        seconds: Int,
        txId: String? = null,
        operationKind: String = "MANUAL_DEDUCTION",
        boxInstallationEpoch: Long = 0L,
        phonePairingEpoch: Long = 0L
    ): PaymentResult {
        if (!isInitialized.get()) {
            Log.w(TAG, "Rejecting payment deduction: KioskEngine initialization in progress")
            return PaymentResult.FAILED
        }
        val effectiveTxId = txId?.trim().takeIf { !it.isNullOrBlank() } ?: "deduct_${System.currentTimeMillis()}"
        val outcome = paymentRepo.deductPaymentBlocking(
            txId = effectiveTxId,
            seconds = seconds,
            operationKind = operationKind,
            boxInstallationEpoch = boxInstallationEpoch,
            phonePairingEpoch = phonePairingEpoch
        )
        if (outcome.result == PaymentResult.APPLIED && outcome.snapshot != null) {
            creditNotifier.publishCommittedDeductSnapshot(seconds, outcome.snapshot)
        }
        return outcome.result
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
        onFinishPayment = { finishPayment() },
        onCancelPayment = { cancelPayment() }
    )

    internal val esp32Coordinator = KioskEsp32Coordinator(
        context = context,
        stateManager = stateManager,
        paymentRepo = paymentRepo,
        armingTimeoutSeconds = ARMING_TIMEOUT_SECONDS,
        getSecretKey = { KioskSecurity.getSharedSecret(context) },
        getRealTimeBatteryInfo = { systemMonitor.getRealTimeBatteryInfo() },
        onCreditPayment = ::creditPayment,
        onSlotBusyTriggered = { triggerSlotBusy() },
        getAudioManager = { audioManager }
    )

    private val esp32Manager = Esp32ConnectionManager(
        context = context,
        scope = scope,
        delegate = esp32Coordinator
    )

    val serverCoordinator: com.pisophone.kiosk.server.KioskServerCoordinator = com.pisophone.kiosk.server.KioskServerCoordinator(
        delegate = object : com.pisophone.kiosk.server.KioskServerDelegate {
            override fun isInitialized(): Boolean = isInitialized.get()
            override fun getDeviceId(): String = stateManager.deviceId.value.ifBlank { KioskSecurity.getHardwareId(context) }
            override fun getSecretKey(): String = KioskSecurity.getSharedSecret(context)
            override fun onCreditPayment(
                txId: String,
                seconds: Int,
                amount: Double,
                operationKind: String,
                coinAmount: Int,
                pricePerCoin: Double,
                boxInstallationEpoch: Long,
                phonePairingEpoch: Long
            ): PaymentResult {
                return creditPayment(
                    txId = txId,
                    seconds = seconds,
                    amount = amount,
                    operationKind = operationKind,
                    coinAmount = coinAmount,
                    pricePerCoin = pricePerCoin,
                    boxInstallationEpoch = boxInstallationEpoch,
                    phonePairingEpoch = phonePairingEpoch,
                    remainingMs = 0L // HTTP credit does not start or reset the 15-second countdown
                )
            }

            override fun onDeductPayment(
                seconds: Int,
                txId: String?,
                operationKind: String,
                boxInstallationEpoch: Long,
                phonePairingEpoch: Long
            ): PaymentResult {
                return deductPayment(
                    seconds = seconds,
                    txId = txId,
                    operationKind = operationKind,
                    boxInstallationEpoch = boxInstallationEpoch,
                    phonePairingEpoch = phonePairingEpoch
                )
            }

            override fun onConfigSynced(
                price: Double?,
                minutes: Int?,
                alias: String?,
                adminPin: String?,
                slotNum: Int?
            ) {
                esp32Coordinator.onConfigSynced(price, minutes, alias, adminPin, slotNum)
            }

            override fun onTriggerAction(action: String, params: Map<String, String>): Boolean {
                return when (action) {
                    "slot_lockdown" -> {
                        val reason = params["reason"] ?: "Device activation required."
                        val slotNum = params["slot"]?.toIntOrNull() ?: params["slot_num"]?.toIntOrNull() ?: 0
                        val expiresAt = params["expires_at"]?.toLongOrNull() ?: 0L
                        esp32Coordinator.onSlotLockdown(reason, slotNum, expiresAt)
                        true
                    }
                    "slot_restored" -> {
                        val slotNum = params["slot"]?.toIntOrNull() ?: params["slot_num"]?.toIntOrNull() ?: 0
                        esp32Coordinator.onSlotRestored(slotNum)
                        true
                    }
                    "arena_mode_activate_p1" -> {
                        val stake = params["stake"]?.toIntOrNull() ?: 5
                        esp32Coordinator.onArenaModeSynced(true, 1, stake)
                        true
                    }
                    "arena_mode_activate_p2" -> {
                        val stake = params["stake"]?.toIntOrNull() ?: 5
                        esp32Coordinator.onArenaModeSynced(true, 2, stake)
                        true
                    }
                    "arena_mode_deactivate" -> {
                        esp32Coordinator.onArenaModeSynced(false, 0, 0)
                        true
                    }
                    else -> false
                }
            }
        },
        defaultPort = 8080
    )

    private val supervisor: KioskSessionSupervisor = KioskSessionSupervisor(
        context = context,
        scope = scope,
        stateManager = stateManager,
        paymentRepo = paymentRepo,
        onSpeakWarning = { speakWarning(it) },
        onFinishPayment = { finishPayment() },
        onCancelPayment = { cancelPayment() },
        onCloseSession = { closeSession(it) },
        onCheckBatteryAlerts = { systemMonitor.checkPeriodicBatteryAlerts() },
        onPeriodicHealthCheck = { healthMonitor.performPeriodicCheck() }
    )

    private val healthMonitor: KioskEngineHealthMonitor = KioskEngineHealthMonitor(
        scope = scope,
        stateManager = stateManager,
        paymentRepo = paymentRepo,
        overlayCoordinator = overlayCoordinator,
        supervisor = supervisor,
        isEngineReady = { isEngineReady },
        onRetryInitialization = { retryInitializationIfNeeded() }
    )

    private var slotBusyJob: Job? = null
    private var initJob: Job? = null
    private var engineStartTimeMs = 0L

    fun retryInitializationIfNeeded() {
        if (initializationState.value == InitializationState.FAILED || initializationState.value == InitializationState.UNINITIALIZED) {
            triggerInitialization()
        }
    }

    fun triggerInitialization() {
        if (initializationState.value == InitializationState.INITIALIZING || initializationState.value == InitializationState.READY) {
            return
        }
        initJob?.cancel()
        initJob = scope.launch(Dispatchers.IO) {
            var attempt = 0
            var success = false

            while (attempt < MAX_INIT_ATTEMPTS && !success && isActive) {
                attempt++
                initializationState.value = InitializationState.INITIALIZING
                Log.i(TAG, "Starting engine initialization (attempt $attempt of $MAX_INIT_ATTEMPTS)...")
                try {
                    stateManager.restoreState()
                    paymentRepo.migrateAndInitialize(context)

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

                    esp32Manager.sendDirectPairingRequest()
                    esp32Manager.startHeartbeatLoop { stateManager.deviceIp.value }

                    supervisor.start()
                    healthMonitor.start()

                    serverCoordinator.startServer(8080)

                    systemMonitor.registerScreenOffReceiver()
                    systemMonitor.registerBatteryMonitor()

                    isInitialized.set(true)
                    initializationState.value = InitializationState.READY
                    success = true
                    Log.i(TAG, "Engine initialization completed successfully on attempt $attempt.")
                } catch (e: Exception) {
                    Log.e(TAG, "Engine initialization attempt $attempt failed: ${e.message}", e)
                    if (attempt >= MAX_INIT_ATTEMPTS) {
                        initializationState.value = InitializationState.FAILED
                        Log.e(TAG, "Engine initialization failed permanently after $MAX_INIT_ATTEMPTS attempts.")
                    } else {
                        val backoffMs = (1000L * (1 shl (attempt - 1))).coerceAtMost(10000L)
                        delay(backoffMs)
                    }
                }
            }
        }
    }

    fun start() {
        engineStartTimeMs = System.currentTimeMillis()
        triggerInitialization()

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
        if (txId.isNullOrBlank()) {
            Log.w(TAG, "Missing transaction ID for coin credit from $source")
            return false
        }
        val result = creditPayment(txId, seconds, amount)
        return result == PaymentResult.APPLIED || result == PaymentResult.ALREADY_APPLIED
    }

    fun triggerDirectPairing(targetIp: String? = null, targetMac: String? = null) {
        esp32Manager.sendDirectPairingRequest(targetIp, targetMac)
    }

    fun unpairEsp32(onResult: ((Boolean, String?) -> Unit)? = null) {
        esp32Manager.unpair(onResult)
    }

    fun updateEsp32StaticIp(newIp: String): Boolean {
        val valid = KioskSecurity.setConfiguredEsp32Ip(context, newIp)
        if (valid) {
            val cleanIp = newIp.trim()
            stateManager.esp32Ip = cleanIp
            esp32Manager.setEsp32Ip(cleanIp)
            triggerDirectPairing(targetIp = cleanIp)
        }
        return valid
    }

    fun closeSession(sendUnarmToEsp: Boolean = false, command: String = "DONE") {
        esp32Manager.closeSession(sendUnarmToEsp, command)
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
        closeSession(sendUnarmToEsp = true, command = "DONE")
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

    fun cancelPayment() {
        closeSession(sendUnarmToEsp = true, command = "CANCEL")
        if (stateManager.appState.value == 3) {
            stateManager.appState.value = 2
        } else {
            stateManager.appState.value = 0
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

    fun ensureHttpServerRunning() {
        serverCoordinator.startServer(8080)
    }

    fun isHttpServerHealthy(): Boolean = serverCoordinator.isServerRunning()

    fun stop() {
        serverCoordinator.stopServer()
        scope.cancel()
        supervisor.stop()
        systemMonitor.shutdown()
        esp32Manager.shutdown()
        audioManager.shutdown()
        overlayCoordinator.remove()
    }
}
