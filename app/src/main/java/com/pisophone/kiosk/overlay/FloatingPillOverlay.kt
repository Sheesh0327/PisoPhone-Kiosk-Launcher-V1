@file:Suppress("DEPRECATION")
package com.pisophone.kiosk.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pisophone.kiosk.ComposeOverlayView
import com.pisophone.kiosk.model.BatteryStatus
import com.pisophone.kiosk.overlay.ui.ArenaModeBanner
import com.pisophone.kiosk.overlay.ui.FloatingPill
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

class FloatingPillOverlay(
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
    private val isArenaModeFlow: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false),
    private val arenaPlayerRoleFlow: StateFlow<Int> = kotlinx.coroutines.flow.MutableStateFlow(0),
    private val arenaStakeMinutesFlow: StateFlow<Int> = kotlinx.coroutines.flow.MutableStateFlow(15),
    private val isArenaBannerVisibleFlow: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false),
    private val onDismissArenaBanner: () -> Unit = {},
    private val onInsertCoinClick: () -> Unit,
    private val onDoneClick: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: ComposeOverlayView? = null
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
        val currentView = overlayView?.view ?: return
        val oldFlags = layoutParams.flags
        if (focusable) {
            layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (oldFlags != layoutParams.flags && isViewAdded) {
            try {
                windowManager.updateViewLayout(currentView, layoutParams)
            } catch (e: Exception) {
                android.util.Log.e("FloatingPillOverlay", "Failed to update focus layout: ${e.message}")
            }
        }
    }

    fun updateFullScreen(isFullScreen: Boolean) {
        val currentView = overlayView?.view ?: return
        if (isFullScreen) {
            layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
            layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
        } else {
            layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT
            layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
        }
        if (isViewAdded) {
            try {
                windowManager.updateViewLayout(currentView, layoutParams)
            } catch (e: Exception) {
                android.util.Log.e("FloatingPillOverlay", "Failed to update full screen layout: ${e.message}")
            }
        }
    }

    fun isAttached(): Boolean {
        val view = overlayView?.view
        return isViewAdded && view != null && view.isAttachedToWindow
    }

    fun show() {
        if (!android.provider.Settings.canDrawOverlays(context)) {
            android.util.Log.w("FloatingPillOverlay", "Overlay permission not granted yet, deferring window attachment")
            return
        }
        val isFullySetup = com.pisophone.kiosk.security.KioskActivationManager.isAppAllowedToRun(context)
        if (!isFullySetup) {
            android.util.Log.d("FloatingPillOverlay", "Device not activated or fully setup. Floating pill overlay deferred.")
            return
        }
        val activeView = overlayView?.view
        if (isViewAdded && activeView != null && activeView.isAttachedToWindow) {
            return
        }

        dispose()

        val newOverlay = ComposeOverlayView(context)
        overlayView = newOverlay

        newOverlay.setContent {
            val appState by appStateFlow.collectAsState()
            val sessionTime by sessionTimeFlow.collectAsState()
            val paymentTimeout by paymentTimeoutFlow.collectAsState()
            val coinsInserted by coinsInsertedFlow.collectAsState()
            val isEsp32Online by isEsp32OnlineFlow.collectAsState()
            val isSlotBusy by isSlotBusyFlow.collectAsState()
            val themeIndex by themeIndexFlow.collectAsState()
            val batteryStatus by batteryStatusFlow.collectAsState()
            val isArenaMode by isArenaModeFlow.collectAsState()
            val arenaRole by arenaPlayerRoleFlow.collectAsState()
            val arenaStake by arenaStakeMinutesFlow.collectAsState()
            val activationUpdateVersion by com.pisophone.kiosk.security.KioskActivationManager.activationUpdateVersion.collectAsState()
            
            val isSetupReady = remember(activationUpdateVersion) { 
                com.pisophone.kiosk.security.KioskActivationManager.isAppAllowedToRun(context)
            }
            val isVisible = isSetupReady && (appState == 2 || appState == 3)
            
            LaunchedEffect(isVisible) {
                if (isVisible) {
                    newOverlay.view.visibility = View.VISIBLE
                } else {
                    newOverlay.view.visibility = View.GONE
                }
            }

            if (isVisible) {
                FloatingPill(
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
                    isArenaMode = isArenaMode,
                    arenaRole = arenaRole,
                    arenaStakeMinutes = arenaStake,
                    onRequestFocus = { focusable -> updateFocusable(focusable) },
                    onRequestFullScreen = { isFullScreen -> updateFullScreen(isFullScreen) },
                    onBrightnessChange = { ratio ->
                        layoutParams.screenBrightness = ratio
                        if (isViewAdded) {
                            try {
                                windowManager.updateViewLayout(newOverlay.view, layoutParams)
                            } catch (e: Exception) {}
                        }
                    },
                    onDrag = { dx, dy ->
                        layoutParams.x += dx.toInt()
                        layoutParams.y += dy.toInt()
                        try {
                            windowManager.updateViewLayout(newOverlay.view, layoutParams)
                        } catch (e: Exception) {
                            android.util.Log.e("FloatingPillOverlay", "Failed to update layout: ${e.message}")
                        }
                    }
                )
            }
        }
        try {
            windowManager.addView(newOverlay.view, layoutParams)
            isViewAdded = true
            newOverlay.view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) {
                    android.util.Log.w("FloatingPillOverlay", "Floating pill overlay view detached from window.")
                    dispose()
                }
            })
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
                }
            }

            newOverlay.start()
        } catch (e: Exception) {
            isViewAdded = false
            android.util.Log.e("FloatingPillOverlay", "Failed to add floating view: ${e.message}")
            dispose()
        }
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
                android.util.Log.e("FloatingPillOverlay", "Error stopping floating view: ${e.message}")
            }

            if (wasAdded || currentView.view.isAttachedToWindow) {
                try {
                    windowManager.removeView(currentView.view)
                } catch (e: Exception) {
                    android.util.Log.e("FloatingPillOverlay", "Error removing floating view from WindowManager: ${e.message}")
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
            val appState = appStateFlow.value
            val isVisible = isFullySetup && (appState == 2 || appState == 3)
            currentView.view.visibility = if (isVisible) View.VISIBLE else View.GONE
            windowManager.updateViewLayout(currentView.view, layoutParams)
            currentView.view.requestLayout()
            currentView.view.invalidate()
        } catch (e: Exception) {
            android.util.Log.e("FloatingPillOverlay", "onScreenWake error: ${e.message}")
        }
    }

    fun onScreenSleep() {
        val currentView = overlayView ?: return
        if (!isViewAdded) return
        try {
            currentView.onPause()
        } catch (e: Exception) {
            android.util.Log.e("FloatingPillOverlay", "onScreenSleep error: ${e.message}")
        }
    }
}
