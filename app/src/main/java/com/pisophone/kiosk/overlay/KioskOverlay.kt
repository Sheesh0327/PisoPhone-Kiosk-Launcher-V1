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
import com.pisophone.kiosk.overlay.ui.FloatingBall
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
    private val batteryStatusFlow: StateFlow<BatteryStatus> = kotlinx.coroutines.flow.MutableStateFlow(BatteryStatus()),
    private val onInsertCoinClick: () -> Unit,
    private val onDoneClick: () -> Unit,
    private val onThemeChange: () -> Unit,
    private val onActivateClick: (String) -> Unit = {}
) {
    private val lockScreenOverlay = LockScreenOverlay(context, appStateFlow, paymentTimeoutFlow, coinsInsertedFlow, themeIndexFlow, isEsp32OnlineFlow, esp32MacAddressFlow, isSlotBusyFlow, pricePerCoinFlow, minutesPerCoinFlow, deviceIpFlow, batteryStatusFlow, onInsertCoinClick, onDoneClick, onThemeChange, onActivateClick)
    private val floatingBallOverlay = FloatingBallOverlay(context, appStateFlow, sessionTimeFlow, paymentTimeoutFlow, coinsInsertedFlow, themeIndexFlow, isEsp32OnlineFlow, isSlotBusyFlow, pricePerCoinFlow, minutesPerCoinFlow, batteryStatusFlow, onInsertCoinClick, onDoneClick)
    
    fun show() {
        lockScreenOverlay.show()
        floatingBallOverlay.show()
    }
    
    fun remove() {
        lockScreenOverlay.remove()
        floatingBallOverlay.remove()
    }

    fun onScreenWake() {
        lockScreenOverlay.onScreenWake()
        floatingBallOverlay.onScreenWake()
    }

    fun onScreenSleep() {
        lockScreenOverlay.onScreenSleep()
        floatingBallOverlay.onScreenSleep()
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
    private val batteryStatusFlow: StateFlow<BatteryStatus> = kotlinx.coroutines.flow.MutableStateFlow(BatteryStatus()),
    private val onInsertCoinClick: () -> Unit,
    private val onDoneClick: () -> Unit,
    private val onThemeChange: () -> Unit,
    private val onActivateClick: (String) -> Unit = {}
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val overlayView = ComposeOverlayView(context)
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
        if (!isViewAdded) return
        val baseFlags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        if (visible) {
            layoutParams.flags = baseFlags
            layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
            layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
        } else {
            layoutParams.flags = baseFlags or 
                                 WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or 
                                 WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            layoutParams.width = 0
            layoutParams.height = 0
        }
        try {
            windowManager.updateViewLayout(overlayView.view, layoutParams)
        } catch (e: Exception) {
            android.util.Log.e("LockScreenOverlay", "Failed to update layoutParams: ${e.message}")
        }
    }

    fun show() {
        if (!android.provider.Settings.canDrawOverlays(context)) {
            android.util.Log.w("LockScreenOverlay", "Overlay permission not granted yet, deferring window attachment")
            return
        }
        if (isViewAdded) return

        val isTutorialComplete = com.pisophone.kiosk.security.HardwareLockManager.isTutorialCompleted(context)
        val isAppAllowed = com.pisophone.kiosk.security.HardwareLockManager.isAppAllowedToRun(context)
        val isFullySetup = isTutorialComplete && isAppAllowed
        val initialVisible = isFullySetup && (appStateFlow.value == 0 || appStateFlow.value == 1)
        val baseFlags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        if (initialVisible) {
            layoutParams.flags = baseFlags
            layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
            layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
        } else {
            layoutParams.flags = baseFlags or 
                                 WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or 
                                 WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            layoutParams.width = 0
            layoutParams.height = 0
        }

        overlayView.setContent {
            val appState by appStateFlow.collectAsState()
            val paymentTimeout by paymentTimeoutFlow.collectAsState()
            val coinsInserted by coinsInsertedFlow.collectAsState()
            val isEsp32Online by isEsp32OnlineFlow.collectAsState()
            val isSlotBusy by isSlotBusyFlow.collectAsState()
            val themeIndex by themeIndexFlow.collectAsState()
            val pricePerCoin by pricePerCoinFlow.collectAsState()
            val minutesPerCoin by minutesPerCoinFlow.collectAsState()
            val deviceIp by deviceIpFlow.collectAsState()
            val batteryStatus by batteryStatusFlow.collectAsState()
            val esp32MacAddress by esp32MacAddressFlow.collectAsState()
            val licenseUpdateVersion by com.pisophone.kiosk.security.HardwareLockManager.licenseUpdateVersion.collectAsState()
            
            val isSetupReady = remember(licenseUpdateVersion) { 
                com.pisophone.kiosk.security.HardwareLockManager.isTutorialCompleted(context) &&
                com.pisophone.kiosk.security.HardwareLockManager.isAppAllowedToRun(context)
            }
            
            val isVisible = isSetupReady && (appState == 0 || appState == 1)
            var renderLockScreen by remember { mutableStateOf(isVisible) }
            val unlockAlpha by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (isVisible) 1f else 0f,
                animationSpec = tween(durationMillis = 400, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                label = "unlockAlpha"
            )
            val unlockScale by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (isVisible) 1f else 1.06f,
                animationSpec = tween(durationMillis = 400, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                label = "unlockScale"
            )

            LaunchedEffect(isVisible) {
                if (isVisible) {
                    updateWindowFlagsAndDimensions(true)
                    renderLockScreen = true
                    overlayView.view.visibility = View.VISIBLE
                } else {
                    // Allow exit animation to play smoothly before hiding window
                    delay(400)
                    renderLockScreen = false
                    overlayView.view.visibility = View.GONE
                    updateWindowFlagsAndDimensions(false)
                }
            }

            if (renderLockScreen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            alpha = unlockAlpha,
                            scaleX = unlockScale,
                            scaleY = unlockScale
                        )
                ) {
                    if (appState == 0) {
                        BlockScreen(onInsertCoinClick, isWaiting = false, 0, 0, {}, isEsp32Online, isSlotBusy = isSlotBusy, pricePerCoin = pricePerCoin, minutesPerCoin = minutesPerCoin, deviceIp = deviceIp, themeIndex = themeIndex, batteryStatus = batteryStatus, onThemeChange = onThemeChange)
                    } else if (appState == 4) {
                        BlockScreen(onInsertCoinClick, isWaiting = false, 0, 0, {}, isEsp32Online, isSlotBusy = isSlotBusy, pricePerCoin = pricePerCoin, minutesPerCoin = minutesPerCoin, deviceIp = deviceIp, themeIndex = themeIndex, batteryStatus = batteryStatus, onThemeChange = onThemeChange, isUnlicensed = true, macAddress = esp32MacAddress, onActivateClick = onActivateClick)
                    } else if (appState == 1 || (!isVisible && coinsInserted > 0)) {
                        BlockScreen(onInsertCoinClick, isWaiting = isVisible, coinsInserted, paymentTimeout, onDoneClick, isEsp32Online, isSlotBusy = isSlotBusy, pricePerCoin = pricePerCoin, minutesPerCoin = minutesPerCoin, deviceIp = deviceIp, themeIndex = themeIndex, batteryStatus = batteryStatus, onThemeChange = onThemeChange)
                    }
                }
            }
        }
        try {
            windowManager.addView(overlayView.view, layoutParams)
            isViewAdded = true
            overlayView.view.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
            overlayView.view.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
                if (hasFocus) {
                    overlayView.view.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
                }
            }

            overlayView.start()
        } catch (e: Exception) {
            android.util.Log.e("LockScreenOverlay", "Failed to add overlay view: ${e.message}")
        }
    }
    
    fun remove() {
        if (!isViewAdded) return
        overlayView.stop()
        overlayView.destroy()
        try {
            windowManager.removeView(overlayView.view)
        } catch (e: Exception) {
            android.util.Log.e("LockScreenOverlay", "Failed to remove overlay view: ${e.message}")
        } finally {
            isViewAdded = false
        }
    }

    fun onScreenWake() {
        if (!isViewAdded) return
        try {
            overlayView.onResume()
            val isFullySetup = com.pisophone.kiosk.security.HardwareLockManager.isTutorialCompleted(context) &&
                               com.pisophone.kiosk.security.HardwareLockManager.isAppAllowedToRun(context)
            val isVisible = isFullySetup && (appStateFlow.value == 0 || appStateFlow.value == 1)
            updateWindowFlagsAndDimensions(isVisible)
            overlayView.view.visibility = if (isVisible) View.VISIBLE else View.GONE
            overlayView.view.requestLayout()
            overlayView.view.invalidate()
        } catch (e: Exception) {
            android.util.Log.e("LockScreenOverlay", "onScreenWake error: ${e.message}")
        }
    }

    fun onScreenSleep() {
        if (!isViewAdded) return
        try {
            overlayView.onPause()
        } catch (e: Exception) {
            android.util.Log.e("LockScreenOverlay", "onScreenSleep error: ${e.message}")
        }
    }
}

class FloatingBallOverlay(
    private val context: Context,
    private val appStateFlow: StateFlow<Int>,
    private val sessionTimeFlow: StateFlow<Int>,
    private val paymentTimeoutFlow: StateFlow<Int>,
    private val coinsInsertedFlow: StateFlow<Int>,
    private val themeIndexFlow: StateFlow<Int>,
    private val isEsp32OnlineFlow: StateFlow<Boolean>,
    private val isSlotBusyFlow: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false),
    private val pricePerCoinFlow: StateFlow<Double>,
    private val minutesPerCoinFlow: StateFlow<Int>,
    private val batteryStatusFlow: StateFlow<BatteryStatus> = kotlinx.coroutines.flow.MutableStateFlow(BatteryStatus()),
    private val onInsertCoinClick: () -> Unit,
    private val onDoneClick: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val overlayView = ComposeOverlayView(context)
    private var isViewAdded = false
    
    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 20
        y = 20
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
    }

    fun updateFocusable(focusable: Boolean) {
        val oldFlags = layoutParams.flags
        if (focusable) {
            layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (oldFlags != layoutParams.flags && isViewAdded) {
            try {
                windowManager.updateViewLayout(overlayView.view, layoutParams)
            } catch (e: Exception) {
                android.util.Log.e("FloatingBallOverlay", "Failed to update focus layout: ${e.message}")
            }
        }
    }

    fun show() {
        if (!android.provider.Settings.canDrawOverlays(context)) {
            android.util.Log.w("FloatingBallOverlay", "Overlay permission not granted yet, deferring window attachment")
            return
        }
        if (isViewAdded) return

        overlayView.setContent {
            val appState by appStateFlow.collectAsState()
            val sessionTime by sessionTimeFlow.collectAsState()
            val paymentTimeout by paymentTimeoutFlow.collectAsState()
            val coinsInserted by coinsInsertedFlow.collectAsState()
            val isEsp32Online by isEsp32OnlineFlow.collectAsState()
            val isSlotBusy by isSlotBusyFlow.collectAsState()
            val themeIndex by themeIndexFlow.collectAsState()
            val batteryStatus by batteryStatusFlow.collectAsState()
            val licenseUpdateVersion by com.pisophone.kiosk.security.HardwareLockManager.licenseUpdateVersion.collectAsState()
            
            val isSetupReady = remember(licenseUpdateVersion) { 
                com.pisophone.kiosk.security.HardwareLockManager.isTutorialCompleted(context) &&
                com.pisophone.kiosk.security.HardwareLockManager.isAppAllowedToRun(context)
            }
            val isVisible = isSetupReady && (appState == 2 || appState == 3)
            
            LaunchedEffect(isVisible) {
                if (isVisible) {
                    overlayView.view.visibility = View.VISIBLE
                } else {
                    overlayView.view.visibility = View.GONE
                }
            }

            if (isVisible) {
                FloatingBall(
                    timeRemaining = sessionTime,
                    onInsertCoinClick = onInsertCoinClick,
                    coinsInserted = coinsInserted,
                    paymentTimeout = paymentTimeout,
                    onDoneClick = onDoneClick,
                    isEsp32Online = isEsp32Online,
                    isSlotBusy = isSlotBusy,
                    isWaiting = appState == 3,
                    themeIndex = themeIndex,
                    batteryStatus = batteryStatus,
                    onRequestFocus = { focusable -> updateFocusable(focusable) },
                    onBrightnessChange = { ratio ->
                        layoutParams.screenBrightness = ratio
                        if (isViewAdded) {
                            try {
                                windowManager.updateViewLayout(overlayView.view, layoutParams)
                            } catch (e: Exception) {}
                        }
                    },
                    onDrag = { dx, dy ->
                        layoutParams.x += dx.toInt()
                        layoutParams.y += dy.toInt()
                        try {
                            windowManager.updateViewLayout(overlayView.view, layoutParams)
                        } catch (e: Exception) {
                            android.util.Log.e("FloatingBallOverlay", "Failed to update layout: ${e.message}")
                        }
                    }
                )
            }
        }
        try {
            windowManager.addView(overlayView.view, layoutParams)
            isViewAdded = true
            overlayView.view.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
                if (hasFocus) {
                    overlayView.view.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
                }
            }

            overlayView.start()
        } catch (e: Exception) {
            android.util.Log.e("FloatingBallOverlay", "Failed to add floating view: ${e.message}")
        }
    }
    
    fun remove() {
        if (!isViewAdded) return
        overlayView.stop()
        overlayView.destroy()
        try {
            windowManager.removeView(overlayView.view)
        } catch (e: Exception) {
            android.util.Log.e("FloatingBallOverlay", "Failed to remove floating view: ${e.message}")
        } finally {
            isViewAdded = false
        }
    }

    fun onScreenWake() {
        if (!isViewAdded) return
        try {
            overlayView.onResume()
            val isFullySetup = com.pisophone.kiosk.security.HardwareLockManager.isTutorialCompleted(context) &&
                               com.pisophone.kiosk.security.HardwareLockManager.isAppAllowedToRun(context)
            val appState = appStateFlow.value
            val isVisible = isFullySetup && (appState == 2 || appState == 3)
            overlayView.view.visibility = if (isVisible) View.VISIBLE else View.GONE
            windowManager.updateViewLayout(overlayView.view, layoutParams)
            overlayView.view.requestLayout()
            overlayView.view.invalidate()
        } catch (e: Exception) {
            android.util.Log.e("FloatingBallOverlay", "onScreenWake error: ${e.message}")
        }
    }

    fun onScreenSleep() {
        if (!isViewAdded) return
        try {
            overlayView.onPause()
        } catch (e: Exception) {
            android.util.Log.e("FloatingBallOverlay", "onScreenSleep error: ${e.message}")
        }
    }
}
