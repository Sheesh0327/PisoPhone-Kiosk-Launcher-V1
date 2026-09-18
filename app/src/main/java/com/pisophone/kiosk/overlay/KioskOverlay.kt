@file:Suppress("DEPRECATION")
package com.pisophone.kiosk.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.pisophone.kiosk.ComposeOverlayView
import com.pisophone.kiosk.model.BatteryStatus
import com.pisophone.kiosk.overlay.ui.ArenaModeBanner
import com.pisophone.kiosk.overlay.ui.BlockScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

class KioskOverlay(
    private val context: Context,
    private val appStateFlow: StateFlow<Int>,
    private val sessionTimeFlow: StateFlow<Int>,
    private val paymentTimeoutFlow: StateFlow<Int>,
    private val coinsInsertedFlow: StateFlow<Int>,
    private val themeIndexFlow: StateFlow<Int>,
    private val isEsp32OnlineFlow: StateFlow<Boolean>,
    private val esp32MacAddressFlow: StateFlow<String> = kotlinx.coroutines.flow.MutableStateFlow(""),
    private val isSlotBusyFlow: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false),
    private val pricePerCoinFlow: StateFlow<Double>,
    private val minutesPerCoinFlow: StateFlow<Int>,
    private val deviceIpFlow: StateFlow<String> = kotlinx.coroutines.flow.MutableStateFlow("127.0.0.1"),
    private val slotNumberFlow: StateFlow<Int> = kotlinx.coroutines.flow.MutableStateFlow(1),
    private val batteryStatusFlow: StateFlow<BatteryStatus> = kotlinx.coroutines.flow.MutableStateFlow(BatteryStatus()),
    private val slotWarningDaysLeftFlow: StateFlow<Int?> = kotlinx.coroutines.flow.MutableStateFlow(null),
    private val isSlotExpiredFlow: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false),
    private val slotExpiryReasonFlow: StateFlow<String> = kotlinx.coroutines.flow.MutableStateFlow(""),
    private val isArenaModeFlow: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false),
    private val arenaPlayerRoleFlow: StateFlow<Int> = kotlinx.coroutines.flow.MutableStateFlow(0),
    private val arenaStakeMinutesFlow: StateFlow<Int> = kotlinx.coroutines.flow.MutableStateFlow(15),
    private val isArenaBannerVisibleFlow: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false),
    private val onDismissArenaBanner: () -> Unit = {},
    private val onInsertCoinClick: () -> Unit,
    private val onDoneClick: () -> Unit,
    private val onThemeChange: () -> Unit,
    private val onActivateClick: (String) -> Unit = {}
) {
    private val lockScreenOverlay = LockScreenOverlay(
        context = context,
        appStateFlow = appStateFlow,
        paymentTimeoutFlow = paymentTimeoutFlow,
        coinsInsertedFlow = coinsInsertedFlow,
        themeIndexFlow = themeIndexFlow,
        isEsp32OnlineFlow = isEsp32OnlineFlow,
        esp32MacAddressFlow = esp32MacAddressFlow,
        isSlotBusyFlow = isSlotBusyFlow,
        pricePerCoinFlow = pricePerCoinFlow,
        minutesPerCoinFlow = minutesPerCoinFlow,
        deviceIpFlow = deviceIpFlow,
        slotNumberFlow = slotNumberFlow,
        batteryStatusFlow = batteryStatusFlow,
        slotWarningDaysLeftFlow = slotWarningDaysLeftFlow,
        isSlotExpiredFlow = isSlotExpiredFlow,
        slotExpiryReasonFlow = slotExpiryReasonFlow,
        isArenaModeFlow = isArenaModeFlow,
        arenaPlayerRoleFlow = arenaPlayerRoleFlow,
        arenaStakeMinutesFlow = arenaStakeMinutesFlow,
        isArenaBannerVisibleFlow = isArenaBannerVisibleFlow,
        onDismissArenaBanner = onDismissArenaBanner,
        onInsertCoinClick = onInsertCoinClick,
        onDoneClick = onDoneClick,
        onThemeChange = onThemeChange,
        onActivateClick = onActivateClick
    )
    private val floatingPillOverlay = FloatingPillOverlay(
        context = context,
        appStateFlow = appStateFlow,
        sessionTimeFlow = sessionTimeFlow,
        paymentTimeoutFlow = paymentTimeoutFlow,
        coinsInsertedFlow = coinsInsertedFlow,
        themeIndexFlow = themeIndexFlow,
        isEsp32OnlineFlow = isEsp32OnlineFlow,
        isSlotBusyFlow = isSlotBusyFlow,
        pricePerCoinFlow = pricePerCoinFlow,
        minutesPerCoinFlow = minutesPerCoinFlow,
        batteryStatusFlow = batteryStatusFlow,
        isArenaModeFlow = isArenaModeFlow,
        arenaPlayerRoleFlow = arenaPlayerRoleFlow,
        arenaStakeMinutesFlow = arenaStakeMinutesFlow,
        onInsertCoinClick = onInsertCoinClick,
        onDoneClick = onDoneClick
    )
    
    fun show(): Boolean {
        val lockShown = lockScreenOverlay.show()
        floatingPillOverlay.show()
        return lockShown
    }

    fun isAttached(): Boolean {
        return lockScreenOverlay.isAttached()
    }
    
    fun remove() {
        lockScreenOverlay.remove()
        floatingPillOverlay.remove()
    }

    fun onScreenWake() {
        lockScreenOverlay.onScreenWake()
        floatingPillOverlay.onScreenWake()
    }

    fun onScreenSleep() {
        lockScreenOverlay.onScreenSleep()
        floatingPillOverlay.onScreenSleep()
    }
}

class LockScreenOverlay(
    private val context: Context,
    private val appStateFlow: StateFlow<Int>,
    private val paymentTimeoutFlow: StateFlow<Int>,
    private val coinsInsertedFlow: StateFlow<Int>,
    private val themeIndexFlow: StateFlow<Int>,
    private val isEsp32OnlineFlow: StateFlow<Boolean>,
    private val esp32MacAddressFlow: StateFlow<String> = kotlinx.coroutines.flow.MutableStateFlow(""),
    private val isSlotBusyFlow: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false),
    private val pricePerCoinFlow: StateFlow<Double>,
    private val minutesPerCoinFlow: StateFlow<Int>,
    private val deviceIpFlow: StateFlow<String> = kotlinx.coroutines.flow.MutableStateFlow("127.0.0.1"),
    private val slotNumberFlow: StateFlow<Int> = kotlinx.coroutines.flow.MutableStateFlow(1),
    private val batteryStatusFlow: StateFlow<BatteryStatus> = kotlinx.coroutines.flow.MutableStateFlow(BatteryStatus()),
    private val slotWarningDaysLeftFlow: StateFlow<Int?> = kotlinx.coroutines.flow.MutableStateFlow(null),
    private val isSlotExpiredFlow: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false),
    private val slotExpiryReasonFlow: StateFlow<String> = kotlinx.coroutines.flow.MutableStateFlow(""),
    private val isArenaModeFlow: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false),
    private val arenaPlayerRoleFlow: StateFlow<Int> = kotlinx.coroutines.flow.MutableStateFlow(0),
    private val arenaStakeMinutesFlow: StateFlow<Int> = kotlinx.coroutines.flow.MutableStateFlow(15),
    private val isArenaBannerVisibleFlow: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false),
    private val onDismissArenaBanner: () -> Unit = {},
    private val onInsertCoinClick: () -> Unit,
    private val onDoneClick: () -> Unit,
    private val onThemeChange: () -> Unit,
    private val onActivateClick: (String) -> Unit = {}
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: ComposeOverlayView? = null
    private var isViewAdded = false
    
    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_FULLSCREEN or
        WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.FILL
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun updateWindowFlagsAndDimensions(isLocked: Boolean, isBannerOnly: Boolean) {
        val currentView = overlayView?.view ?: return
        if (!isViewAdded) return
        val baseFlags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or 
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or 
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        
        layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
        layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT

        if (isLocked) {
            layoutParams.flags = baseFlags
            currentView.alpha = 1f
        } else if (isBannerOnly) {
            layoutParams.flags = baseFlags or 
                                 WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or 
                                 WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            currentView.alpha = 1f
        } else {
            layoutParams.flags = baseFlags or 
                                 WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or 
                                 WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            currentView.alpha = 0f
        }
        try {
            windowManager.updateViewLayout(currentView, layoutParams)
        } catch (e: Exception) {
            android.util.Log.e("LockScreenOverlay", "Failed to update layoutParams: ${e.message}")
        }
    }

    fun isAttached(): Boolean {
        val view = overlayView?.view
        return isViewAdded && view != null && view.isAttachedToWindow
    }

    fun show(): Boolean {
        if (!android.provider.Settings.canDrawOverlays(context)) {
            android.util.Log.w("LockScreenOverlay", "Overlay permission not granted yet, deferring window attachment")
            return false
        }
        val activeView = overlayView?.view
        if (isViewAdded && activeView != null && activeView.isAttachedToWindow) {
            return true
        }

        dispose()

        val isFullySetup = com.pisophone.kiosk.security.KioskActivationManager.isAppAllowedToRun(context)
        if (!isFullySetup) {
            android.util.Log.d("LockScreenOverlay", "Device not activated or fully setup. Lock screen overlay deferred.")
            return false
        }

        val newOverlay = ComposeOverlayView(context)
        overlayView = newOverlay

        val initialVisible = isFullySetup && (appStateFlow.value == 0 || appStateFlow.value == 1)
        val initialBanner = isFullySetup && isArenaBannerVisibleFlow.value
        val baseFlags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or 
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or 
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        
        layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
        layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
        if (initialVisible) {
            layoutParams.flags = baseFlags
            newOverlay.view.alpha = 1f
        } else if (initialBanner) {
            layoutParams.flags = baseFlags or 
                                 WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or 
                                 WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            newOverlay.view.alpha = 1f
        } else {
            layoutParams.flags = baseFlags or 
                                 WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or 
                                 WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            newOverlay.view.alpha = 0f
        }

        newOverlay.setContent {
            val appState by appStateFlow.collectAsState()
            val paymentTimeout by paymentTimeoutFlow.collectAsState()
            val coinsInserted by coinsInsertedFlow.collectAsState()
            val isEsp32Online by isEsp32OnlineFlow.collectAsState()
            val isSlotBusy by isSlotBusyFlow.collectAsState()
            val themeIndex by themeIndexFlow.collectAsState()
            val pricePerCoin by pricePerCoinFlow.collectAsState()
            val minutesPerCoin by minutesPerCoinFlow.collectAsState()
            val deviceIp by deviceIpFlow.collectAsState()
            val slotNumber by slotNumberFlow.collectAsState()
            val batteryStatus by batteryStatusFlow.collectAsState()
            val slotWarningDaysLeft by slotWarningDaysLeftFlow.collectAsState()
            val isSlotExpired by isSlotExpiredFlow.collectAsState()
            val slotExpiryReason by slotExpiryReasonFlow.collectAsState()
            val isArenaMode by isArenaModeFlow.collectAsState()
            val arenaPlayerRole by arenaPlayerRoleFlow.collectAsState()
            val arenaStakeMinutes by arenaStakeMinutesFlow.collectAsState()
            val isArenaBannerVisible by isArenaBannerVisibleFlow.collectAsState()
            val activationUpdateVersion by com.pisophone.kiosk.security.KioskActivationManager.activationUpdateVersion.collectAsState()
            
            val isSetupReady = remember(activationUpdateVersion) { 
                com.pisophone.kiosk.security.KioskActivationManager.isAppAllowedToRun(context)
            }
            
            val isLockedVisible = isSetupReady && (appState == 0 || appState == 1)
            val isBannerOnlyVisible = isSetupReady && isArenaBannerVisible && !isLockedVisible

            val unlockAlpha by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (isLockedVisible) 1f else 0f,
                animationSpec = tween(durationMillis = 350, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                label = "unlockAlpha"
            )
            val unlockScale by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (isLockedVisible) 1f else 1.05f,
                animationSpec = tween(durationMillis = 350, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                label = "unlockScale"
            )

            LaunchedEffect(isLockedVisible, isBannerOnlyVisible) {
                if (isLockedVisible) {
                    updateWindowFlagsAndDimensions(isLocked = true, isBannerOnly = false)
                } else if (isBannerOnlyVisible) {
                    updateWindowFlagsAndDimensions(isLocked = false, isBannerOnly = true)
                } else {
                    delay(350)
                    updateWindowFlagsAndDimensions(isLocked = false, isBannerOnly = false)
                }
            }

            LaunchedEffect(isArenaBannerVisible) {
                if (isArenaBannerVisible) {
                    delay(3800L)
                    onDismissArenaBanner()
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                if (isLockedVisible || unlockAlpha > 0.01f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                alpha = unlockAlpha,
                                scaleX = unlockScale,
                                scaleY = unlockScale
                            )
                    ) {
                        if (appState == 0 || appState == 4 || (appState == 2 && !isLockedVisible)) {
                            BlockScreen(
                                onInsertCoin = onInsertCoinClick,
                                isWaiting = false,
                                coinsInserted = 0,
                                paymentTimeout = 0,
                                onDoneClick = {},
                                isEsp32Online = isEsp32Online,
                                isSlotBusy = isSlotBusy,
                                pricePerCoin = pricePerCoin,
                                minutesPerCoin = minutesPerCoin,
                                deviceIp = deviceIp,
                                slotNumber = slotNumber,
                                themeIndex = themeIndex,
                                batteryStatus = batteryStatus,
                                onThemeChange = onThemeChange,
                                slotWarningDaysLeft = slotWarningDaysLeft,
                                isSlotExpired = isSlotExpired,
                                slotExpiryReason = slotExpiryReason,
                                isArenaMode = isArenaMode,
                                arenaRole = arenaPlayerRole,
                                arenaStakeMinutes = arenaStakeMinutes
                            )
                        } else if (appState == 1 || appState == 3 || coinsInserted > 0) {
                            BlockScreen(
                                onInsertCoin = onInsertCoinClick,
                                isWaiting = isLockedVisible,
                                coinsInserted = coinsInserted,
                                paymentTimeout = paymentTimeout,
                                onDoneClick = onDoneClick,
                                isEsp32Online = isEsp32Online,
                                isSlotBusy = isSlotBusy,
                                pricePerCoin = pricePerCoin,
                                minutesPerCoin = minutesPerCoin,
                                deviceIp = deviceIp,
                                slotNumber = slotNumber,
                                themeIndex = themeIndex,
                                batteryStatus = batteryStatus,
                                onThemeChange = onThemeChange,
                                slotWarningDaysLeft = slotWarningDaysLeft,
                                isSlotExpired = isSlotExpired,
                                slotExpiryReason = slotExpiryReason,
                                isArenaMode = isArenaMode,
                                arenaRole = arenaPlayerRole,
                                arenaStakeMinutes = arenaStakeMinutes
                            )
                        }
                    }
                }

                if (isArenaBannerVisible) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 28.dp),
                        contentAlignment = androidx.compose.ui.Alignment.TopCenter
                    ) {
                        ArenaModeBanner(
                            visible = isArenaBannerVisible,
                            playerRole = arenaPlayerRole,
                            stakeMinutes = arenaStakeMinutes
                        )
                    }
                }
            }
        }
        try {
            windowManager.addView(newOverlay.view, layoutParams)
            isViewAdded = true
            newOverlay.view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) {
                    android.util.Log.w("LockScreenOverlay", "Lock screen overlay detached from window automatically.")
                    dispose()
                }
            })
            newOverlay.view.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
            newOverlay.view.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
                if (hasFocus) {
                    newOverlay.view.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
                } else {
                    com.pisophone.kiosk.security.KioskSecurity.collapseStatusBar(context)
                }
            }

            newOverlay.start()
        } catch (e: Exception) {
            isViewAdded = false
            android.util.Log.e("LockScreenOverlay", "Failed to add overlay view: ${e.message}")
            dispose()
        }
        return isViewAdded
    }
    
    fun remove() {
        dispose()
    }

    @Synchronized
    private fun dispose() {
        val currentView = overlayView
        overlayView = null
        val wasAdded = isViewAdded
        isViewAdded = false

        if (currentView != null) {
            try {
                currentView.stop()
                currentView.destroy()
            } catch (e: Exception) {
                android.util.Log.e("LockScreenOverlay", "Error stopping overlay view: ${e.message}")
            }

            if (wasAdded || currentView.view.isAttachedToWindow) {
                try {
                    windowManager.removeView(currentView.view)
                } catch (e: Exception) {
                    android.util.Log.e("LockScreenOverlay", "Error removing overlay view from WindowManager: ${e.message}")
                }
            }
        }
    }

    fun onScreenWake() {
        val currentView = overlayView ?: return
        if (!isViewAdded) return
        try {
            currentView.onResume()
            val isFullySetup = com.pisophone.kiosk.security.KioskActivationManager.isAppAllowedToRun(context)
            val isVisible = isFullySetup && (appStateFlow.value == 0 || appStateFlow.value == 1)
            val isBannerOnly = isFullySetup && isArenaBannerVisibleFlow.value && !isVisible
            updateWindowFlagsAndDimensions(isLocked = isVisible, isBannerOnly = isBannerOnly)
            currentView.view.requestLayout()
            currentView.view.invalidate()
        } catch (e: Exception) {
            android.util.Log.e("LockScreenOverlay", "onScreenWake error: ${e.message}")
        }
    }

    fun onScreenSleep() {
        val currentView = overlayView ?: return
        if (!isViewAdded) return
        try {
            currentView.onPause()
        } catch (e: Exception) {
            android.util.Log.e("LockScreenOverlay", "onScreenSleep error: ${e.message}")
        }
    }
}

