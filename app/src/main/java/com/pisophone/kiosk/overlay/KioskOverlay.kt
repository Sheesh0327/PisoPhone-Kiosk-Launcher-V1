@file:Suppress("DEPRECATION")
package com.pisophone.kiosk.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.pisophone.kiosk.ComposeOverlayView
import com.pisophone.kiosk.model.BatteryStatus
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
    private val onInsertCoinClick: () -> Unit,
    private val onDoneClick: () -> Unit,
    private val onThemeChange: () -> Unit,
    private val onActivateClick: (String) -> Unit = {}
) {
    private val lockScreenOverlay = LockScreenOverlay(context, appStateFlow, paymentTimeoutFlow, coinsInsertedFlow, themeIndexFlow, isEsp32OnlineFlow, esp32MacAddressFlow, isSlotBusyFlow, pricePerCoinFlow, minutesPerCoinFlow, deviceIpFlow, slotNumberFlow, batteryStatusFlow, slotWarningDaysLeftFlow, isSlotExpiredFlow, slotExpiryReasonFlow, onInsertCoinClick, onDoneClick, onThemeChange, onActivateClick)
    private val floatingPillOverlay = FloatingPillOverlay(context, appStateFlow, sessionTimeFlow, paymentTimeoutFlow, coinsInsertedFlow, themeIndexFlow, isEsp32OnlineFlow, isSlotBusyFlow, pricePerCoinFlow, minutesPerCoinFlow, batteryStatusFlow, onInsertCoinClick, onDoneClick)
    
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

    private fun updateWindowFlagsAndDimensions(visible: Boolean) {
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

        if (visible) {
            layoutParams.flags = baseFlags
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
            val activationUpdateVersion by com.pisophone.kiosk.security.KioskActivationManager.activationUpdateVersion.collectAsState()
            
            val isSetupReady = remember(activationUpdateVersion) { 
                com.pisophone.kiosk.security.KioskActivationManager.isAppAllowedToRun(context)
            }
            
            val isVisible = isSetupReady && (appState == 0 || appState == 1)
            val unlockAlpha by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (isVisible) 1f else 0f,
                animationSpec = tween(durationMillis = 350, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                label = "unlockAlpha"
            )
            val unlockScale by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (isVisible) 1f else 1.05f,
                animationSpec = tween(durationMillis = 350, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                label = "unlockScale"
            )

            LaunchedEffect(isVisible) {
                if (isVisible) {
                    updateWindowFlagsAndDimensions(true)
                } else {
                    delay(350)
                    updateWindowFlagsAndDimensions(false)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        alpha = unlockAlpha,
                        scaleX = unlockScale,
                        scaleY = unlockScale
                    )
            ) {
                if (appState == 0 || appState == 4 || (appState == 2 && !isVisible)) {
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
                        slotExpiryReason = slotExpiryReason
                    )
                } else if (appState == 1 || appState == 3 || coinsInserted > 0) {
                    BlockScreen(
                        onInsertCoin = onInsertCoinClick,
                        isWaiting = isVisible,
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
                        slotExpiryReason = slotExpiryReason
                    )
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
            updateWindowFlagsAndDimensions(isVisible)
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

