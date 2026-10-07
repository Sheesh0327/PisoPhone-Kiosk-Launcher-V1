package com.pisophone.kiosk.overlay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.pisophone.kiosk.model.BatteryStatus
import com.pisophone.kiosk.security.KioskActivationManager
import com.pisophone.kiosk.service.KioskStateManager
import com.pisophone.kiosk.service.SessionRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Coordinates the display and lifecycle of the Kiosk screen overlay.
 * Separates UI window attachment and activation event handling from service orchestration.
 *
 * Without permission to draw over other apps (a phone set up by QR code), the lock screen is shown as
 * [LockScreenActivity] instead ("activity mode"): it is started whenever the session state says the phone is locked.
 */
class KioskOverlayCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val stateManager: KioskStateManager,
    private val batteryStatusFlow: StateFlow<BatteryStatus>,
    private val armingTimeoutSeconds: Int,
    private val onArmSlot: () -> Unit,
    private val onFinishPayment: () -> Unit,
) {
    companion object {
        private const val TAG = "KioskOverlayCoordinator"
    }

    private var overlay: KioskOverlay? = null

    /** Set while the lock screen is shown as [LockScreenActivity] (no overlay permission). */
    private var activityModeJob: Job? = null

    init {
        scope.launch {
            KioskActivationManager.activationUpdateVersion.collect {
                Handler(Looper.getMainLooper()).post {
                    if (overlay == null) {
                        setupOverlay()
                    }
                }
            }
        }
    }

    fun isOverlayHealthy(): Boolean {
        if (activityModeJob != null) return !LockScreenActivity.shouldShowNow() || LockScreenActivity.isAlive
        return overlay != null && overlay?.isAttached() == true
    }

    fun setupOverlay() {
        scope.launch(Dispatchers.Main) {
            if (overlay != null && overlay?.isAttached() == true) return@launch
            if (activityModeJob != null && overlay != null) {
                if (LockScreenActivity.shouldShowNow() && !LockScreenActivity.isAlive) LockScreenActivity.launch(context)
                return@launch
            }

            if (overlay == null) {
                try {
                    overlay = KioskOverlay(
                        context = context,
                        appStateFlow = stateManager.appState,
                        sessionTimeFlow = stateManager.sessionTimeRemaining,
                        paymentTimeoutFlow = stateManager.paymentTimeout,
                        coinsInsertedFlow = stateManager.coinsInserted,
                        themeIndexFlow = stateManager.themeIndex,
                        isEsp32OnlineFlow = stateManager.isEsp32Online,
                        esp32MacAddressFlow = stateManager.esp32MacAddress,
                        isSlotBusyFlow = stateManager.isSlotBusy,
                        isArmingInProgressFlow = stateManager.isArmingInProgress,
                        pricePerCoinFlow = stateManager.pricePerCoin,
                        minutesPerCoinFlow = stateManager.minutesPerCoin,
                        deviceIpFlow = stateManager.deviceIp,
                        slotNumberFlow = stateManager.slotNumber,
                        batteryStatusFlow = batteryStatusFlow,
                        slotWarningDaysLeftFlow = stateManager.slotWarningDaysLeft,
                        isSlotExpiredFlow = stateManager.isSlotExpired,
                        slotExpiryReasonFlow = stateManager.slotExpiryMessage,
                        isArenaModeFlow = stateManager.isArenaMode,
                        arenaPlayerRoleFlow = stateManager.arenaPlayerRole,
                        arenaStakeMinutesFlow = stateManager.arenaStakeMinutes,
                        isArenaBannerVisibleFlow = stateManager.isArenaBannerVisible,
                        onDismissArenaBanner = { stateManager.dismissArenaBanner() },
                        onInsertCoinClick = {
                            if (stateManager.isSlotExpired.value || KioskActivationManager.isSlotLockedDown(context)) {
                                Log.w(TAG, "Coin insertion blocked: Device not activated on ESP32.")
                                Handler(Looper.getMainLooper()).post {
                                    Toast.makeText(
                                        context.applicationContext,
                                        "Device not activated. Please activate this device in the ESP32 Kiosk Manager.",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                                return@KioskOverlay
                            }
                            if (stateManager.isArmingInProgress.value) return@KioskOverlay
                            stateManager.isArmingInProgress.value = true
                            onArmSlot()
                        },
                        onDoneClick = { onFinishPayment() },
                        onThemeChange = {
                            stateManager.themeIndex.value = (stateManager.themeIndex.value + 1) % 3
                        },
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error constructing KioskOverlay: ${e.message}", e)
                    overlay = null
                    return@launch
                }
            }

            val attached = overlay?.show() ?: false
            if (!attached && LockScreenActivity.shouldUse(context)) {
                startActivityMode()
                return@launch
            }
            if (!attached) {
                Log.w(TAG, "Failed to attach overlay window. Resetting overlay reference for retry.")
                overlay?.remove()
                overlay = null
            }
        }
    }

    /** Shows the lock screen as [LockScreenActivity] from now on, every time the session state locks the phone. */
    private fun startActivityMode() {
        val current = overlay ?: return
        LockScreenActivity.host = current
        if (activityModeJob != null) return
        Log.i(TAG, "No permission to draw over other apps: the lock screen is shown as a full-screen activity (device owner).")
        activityModeJob = scope.launch(Dispatchers.Main) {
            stateManager.appState.collect { state ->
                if (SessionRules.isLockScreenShown(state)) LockScreenActivity.launch(context)
            }
        }
    }

    fun show() {
        scope.launch(Dispatchers.Main) {
            if (activityModeJob != null) {
                if (LockScreenActivity.shouldShowNow()) LockScreenActivity.launch(context)
            } else {
                overlay?.show()
            }
        }
    }

    fun remove() {
        activityModeJob?.cancel()
        activityModeJob = null
        overlay?.remove()
        overlay = null
    }

    fun onScreenSleep() {
        overlay?.onScreenSleep()
    }

    fun onScreenWake() {
        overlay?.onScreenWake()
    }
}
