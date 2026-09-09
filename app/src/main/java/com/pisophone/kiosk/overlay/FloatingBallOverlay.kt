@file:Suppress("DEPRECATION")
package com.pisophone.kiosk.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.*
import com.pisophone.kiosk.ComposeOverlayView
import com.pisophone.kiosk.model.BatteryStatus
import com.pisophone.kiosk.overlay.ui.FloatingBall
import kotlinx.coroutines.flow.StateFlow

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
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or 
        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
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
        val isFullySetup = com.pisophone.kiosk.security.HardwareLockManager.isAppAllowedToRun(context)
        if (!isFullySetup) {
            android.util.Log.d("FloatingBallOverlay", "Device not activated or fully setup. Floating ball overlay deferred.")
            return
        }
        if (isViewAdded) {
            if (overlayView.view.isAttachedToWindow) {
                return
            } else {
                remove()
            }
        }

        overlayView.setContent {
            val appState by appStateFlow.collectAsState()
            val sessionTime by sessionTimeFlow.collectAsState()
            val paymentTimeout by paymentTimeoutFlow.collectAsState()
            val coinsInserted by coinsInsertedFlow.collectAsState()
            val isEsp32Online by isEsp32OnlineFlow.collectAsState()
            val isSlotBusy by isSlotBusyFlow.collectAsState()
            val themeIndex by themeIndexFlow.collectAsState()
            val batteryStatus by batteryStatusFlow.collectAsState()
            val securityUpdateVersion by com.pisophone.kiosk.security.HardwareLockManager.securityUpdateVersion.collectAsState()
            
            val isSetupReady = remember(securityUpdateVersion) { 
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
            overlayView.view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) {
                    android.util.Log.w("FloatingBallOverlay", "Floating ball overlay detached from window automatically.")
                    isViewAdded = false
                }
            })
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
            val isFullySetup = com.pisophone.kiosk.security.HardwareLockManager.isAppAllowedToRun(context)
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
