package com.pisophone.kiosk.overlay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.pisophone.kiosk.model.BatteryStatus
import com.pisophone.kiosk.security.AdminMaintenanceMode
import com.pisophone.kiosk.security.KioskActivationManager
import com.pisophone.kiosk.service.AccountController
import com.pisophone.kiosk.service.KioskStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Coordinates the display and lifecycle of the Kiosk screen overlay.
 * Separates UI window attachment and activation event handling from service orchestration.
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

    /**
     * True while an admin works in Play Store / Settings / the installer: those apps refuse input (and Play Store warns about a
     * "screen overlay") while any other app has a window over them, even a transparent one. So the overlay windows are removed
     * entirely, not just hidden, and nothing (health monitor, activation) puts them back until the admin returns.
     */
    private var suspendedForAdminApp = false

    init {
        scope.launch {
            // An admin-only app in front, or the QR card scanner (whose camera the full-screen lock window would cover).
            combine(AdminMaintenanceMode.suspendOverlays, AccountController.scanning) { admin, scanning -> admin || scanning }
                .collect { suspend ->
                    Handler(Looper.getMainLooper()).post { setSuspendedForAdminApp(suspend) }
                }
        }
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

    private fun setSuspendedForAdminApp(suspend: Boolean) {
        if (suspend == suspendedForAdminApp) return
        suspendedForAdminApp = suspend
        if (suspend) {
            Log.i(TAG, "Admin app in front: removing the overlay windows.")
            overlay?.remove()
        } else {
            Log.i(TAG, "Back on the kiosk: restoring the overlay windows.")
            setupOverlay()
        }
    }

    /** Not being on screen while an admin app is in front is the intended state, not a fault to repair. */
    fun isOverlayHealthy(): Boolean = suspendedForAdminApp || (overlay != null && overlay?.isAttached() == true)

    fun setupOverlay() {
        scope.launch(Dispatchers.Main) {
            if (suspendedForAdminApp) return@launch
            if (overlay != null && overlay?.isAttached() == true) return@launch

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
                        armFailureFlow = stateManager.armFailure,
                        isArmingInProgressFlow = stateManager.isArmingInProgress,
                        pricePerCoinFlow = stateManager.pricePerCoin,
                        minutesPerCoinFlow = stateManager.minutesPerCoin,
                        deviceIpFlow = stateManager.deviceIp,
                        slotNumberFlow = stateManager.slotNumber,
                        batteryStatusFlow = batteryStatusFlow,
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
            if (!attached) {
                Log.w(TAG, "Failed to attach overlay window. Resetting overlay reference for retry.")
                overlay?.remove()
                overlay = null
            }
        }
    }

    fun show() {
        scope.launch(Dispatchers.Main) {
            if (suspendedForAdminApp) return@launch
            overlay?.show()
        }
    }

    fun remove() {
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
