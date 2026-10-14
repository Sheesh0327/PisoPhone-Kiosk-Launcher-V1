@file:Suppress("DEPRECATION")

package com.pisophone.kiosk.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import com.pisophone.kiosk.ComposeOverlayView
import com.pisophone.kiosk.model.BatteryStatus
import com.pisophone.kiosk.network.Esp32Responses
import com.pisophone.kiosk.overlay.ui.FloatingPill
import com.pisophone.kiosk.security.AdminMaintenanceMode
import com.pisophone.kiosk.service.SessionRules
import com.pisophone.kiosk.service.SessionState
import kotlinx.coroutines.flow.StateFlow

private const val DEFAULT_X = 20
private const val DEFAULT_Y = 20
private const val MIN_VISIBLE_PX = 60

class FloatingPillOverlay(
    private val context: Context,
    private val appStateFlow: StateFlow<Int>,
    private val sessionTimeFlow: StateFlow<Int>,
    private val paymentTimeoutFlow: StateFlow<Int>,
    private val coinsInsertedFlow: StateFlow<Int>,
    private val themeIndexFlow: StateFlow<Int>,
    private val isEsp32OnlineFlow: StateFlow<Boolean>,
    private val armFailureFlow: StateFlow<Esp32Responses.ArmFailure?> = kotlinx.coroutines.flow.MutableStateFlow(null),
    private val isArmingInProgressFlow: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false),
    private val pricePerCoinFlow: StateFlow<Double>,
    private val minutesPerCoinFlow: StateFlow<Int>,
    private val batteryStatusFlow: StateFlow<BatteryStatus> = kotlinx.coroutines.flow.MutableStateFlow(BatteryStatus()),
    private val isArenaModeFlow: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false),
    private val arenaPlayerRoleFlow: StateFlow<Int> = kotlinx.coroutines.flow.MutableStateFlow(0),
    private val arenaStakeMinutesFlow: StateFlow<Int> = kotlinx.coroutines.flow.MutableStateFlow(15),
    private val isArenaBannerVisibleFlow: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false),
    private val onDismissArenaBanner: () -> Unit = {},
    private val hiddenForAdminAppFlow: StateFlow<Boolean> = AdminMaintenanceMode.suspendOverlays,
    private val onInsertCoinClick: () -> Unit,
    private val onDoneClick: () -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val positionPrefs = context.createDeviceProtectedStorageContext().getSharedPreferences("pill_position", Context.MODE_PRIVATE)
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
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = savedX()
        y = savedY()
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
    }

    /** Where the owner left the pill last time (kept across restarts), kept on the screen. */
    private fun savedX(): Int = positionPrefs.getInt("x", DEFAULT_X).coerceIn(0, (screenWidthPx() - MIN_VISIBLE_PX).coerceAtLeast(0))

    private fun savedY(): Int = positionPrefs.getInt("y", DEFAULT_Y).coerceIn(0, (screenHeightPx() - MIN_VISIBLE_PX).coerceAtLeast(0))

    private fun screenWidthPx(): Int = context.resources.displayMetrics.widthPixels

    private fun screenHeightPx(): Int = context.resources.displayMetrics.heightPixels

    private fun savePosition() {
        if (layoutParams.gravity != (Gravity.TOP or Gravity.START)) return
        positionPrefs.edit().putInt("x", layoutParams.x).putInt("y", layoutParams.y).apply()
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
            val armFailure by armFailureFlow.collectAsState()
            val isArmingInProgress by isArmingInProgressFlow.collectAsState()
            val batteryStatus by batteryStatusFlow.collectAsState()
            val isArenaMode by isArenaModeFlow.collectAsState()
            val arenaRole by arenaPlayerRoleFlow.collectAsState()
            val arenaStake by arenaStakeMinutesFlow.collectAsState()
            val isArenaBannerVisible by isArenaBannerVisibleFlow.collectAsState()
            val hiddenForAdminApp by hiddenForAdminAppFlow.collectAsState()
            val isVisible = SessionRules.isPillShown(appState, isArenaBannerVisible, hiddenForAdminApp)

            LaunchedEffect(isVisible) {
                if (isVisible) {
                    newOverlay.view.visibility = View.VISIBLE
                } else {
                    newOverlay.view.visibility = View.GONE
                }
            }

            LaunchedEffect(isArenaBannerVisible) {
                if (isArenaBannerVisible) {
                    layoutParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    layoutParams.x = 0
                    layoutParams.y = 40
                } else {
                    layoutParams.gravity = Gravity.TOP or Gravity.START
                    layoutParams.x = savedX()
                    layoutParams.y = savedY()
                }
                if (isViewAdded) {
                    try {
                        windowManager.updateViewLayout(newOverlay.view, layoutParams)
                    } catch (e: Exception) {
                        android.util.Log.e("FloatingPillOverlay", "Failed to update layout for arena banner: ${e.message}")
                    }
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
                    armFailure = armFailure,
                    isArmingInProgress = isArmingInProgress,
                    isWaiting = appState == SessionState.UNLOCKED_ARMED.code,
                    batteryStatus = batteryStatus,
                    isArenaMode = isArenaMode,
                    arenaRole = arenaRole,
                    arenaStakeMinutes = arenaStake,
                    isArenaBannerVisible = isArenaBannerVisible,
                    onDismissArenaBanner = onDismissArenaBanner,
                    onRequestFocus = { focusable -> updateFocusable(focusable) },
                    onRequestFullScreen = { isFullScreen -> updateFullScreen(isFullScreen) },
                    onBrightnessChange = { ratio ->
                        layoutParams.screenBrightness = ratio
                        if (isViewAdded) {
                            try {
                                windowManager.updateViewLayout(newOverlay.view, layoutParams)
                            } catch (ignored: Exception) {
                                // the view was removed in the meantime
                            }
                        }
                    },
                    onDragEnd = { savePosition() },
                    onDrag = { dx, dy ->
                        layoutParams.x += dx.toInt()
                        layoutParams.y += dy.toInt()
                        try {
                            windowManager.updateViewLayout(newOverlay.view, layoutParams)
                        } catch (e: Exception) {
                            android.util.Log.e("FloatingPillOverlay", "Failed to update layout: ${e.message}")
                        }
                    },
                )
            }
        }
        try {
            windowManager.addView(newOverlay.view, layoutParams)
            isViewAdded = true
            newOverlay.view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) {
                    // Identity check: ignore late detaches of a previous view instance.
                    if (overlayView?.view !== v) return
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
            val appState = appStateFlow.value
            val isVisible = SessionRules.isPillShown(appState, isArenaBannerVisibleFlow.value, hiddenForAdminAppFlow.value)
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
