@file:Suppress("DEPRECATION")
package com.pisophone.kiosk.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material.icons.filled.Token
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Memory
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.app.ActivityManager
import android.content.Intent
import android.widget.Toast
import com.pisophone.kiosk.AppInfo
import com.pisophone.kiosk.KioskService
import com.pisophone.kiosk.model.BatteryAlertState
import com.pisophone.kiosk.model.BatteryStatus
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.pisophone.kiosk.util.AppLauncher
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.security.HardwareLockManager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import com.pisophone.kiosk.ComposeOverlayView
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

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

        val initialVisible = appStateFlow.value == 0 || appStateFlow.value == 1 || appStateFlow.value == 4
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
            
            val isVisible = appState == 0 || appState == 1 || appState == 4
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
            val isVisible = appStateFlow.value == 0 || appStateFlow.value == 1
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
            
            LaunchedEffect(appState) {
                if (appState == 2 || appState == 3) {
                    overlayView.view.visibility = View.VISIBLE
                } else {
                    overlayView.view.visibility = View.GONE
                }
            }

            if (appState == 2 || appState == 3) {
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
            val appState = appStateFlow.value
            overlayView.view.visibility = if (appState == 2 || appState == 3) View.VISIBLE else View.GONE
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

@Composable
fun BlockScreen(
    onInsertCoin: () -> Unit,
    isWaiting: Boolean = false,
    coinsInserted: Int,
    paymentTimeout: Int,
    onDoneClick: () -> Unit,
    isEsp32Online: Boolean,
    isSlotBusy: Boolean = false,
    pricePerCoin: Double = 5.0,
    minutesPerCoin: Int = 30,
    deviceIp: String = "127.0.0.1",
    themeIndex: Int = 0,
    batteryStatus: BatteryStatus = BatteryStatus(),
    onThemeChange: () -> Unit = {},
    buttonText: String = "READY FOR COIN",
    isUnlicensed: Boolean = false,
    macAddress: String = "",
    onActivateClick: (String) -> Unit = {},
    modifier: Modifier = Modifier.fillMaxSize()
) {
    data class OverlayTheme(
        val name: String,
        val background: Color,
        val primary: Color,
        val onPrimary: Color,
        val surface: Color,
        val border: Color,
        val secondary: Color
    )

    val themes = listOf(
        // 0: PisoPhone Obsidian (Official Website Identity)
        OverlayTheme(
            name = "PISOPHONE OBSIDIAN",
            background = Color(0xFF060B14),
            primary = Color(0xFF10B981),
            onPrimary = Color(0xFF020617),
            surface = Color(0xFF0F172A),
            border = Color(0xFF1E293B),
            secondary = Color(0xFF34D399)
        ),
        // 1: Ultraviolet Arcade
        OverlayTheme(
            name = "ULTRA VIOLET",
            background = Color(0xFF0F061E),
            primary = Color(0xFFB026FF),
            onPrimary = Color(0xFFFFFFFF),
            surface = Color(0xFF221140),
            border = Color(0xFFB026FF),
            secondary = Color(0xFFFFB800)
        ),
        // 2: Matrix Emerald
        OverlayTheme(
            name = "MATRIX LIME",
            background = Color(0xFF04120B),
            primary = Color(0xFF00FF88),
            onPrimary = Color(0xFF000000),
            surface = Color(0xFF0C2B1D),
            border = Color(0xFF00FF88),
            secondary = Color(0xFF00F5D4)
        ),
        // 3: Solar Flare
        OverlayTheme(
            name = "SOLAR FLARE",
            background = Color(0xFF140804),
            primary = Color(0xFFFF6600),
            onPrimary = Color(0xFF000000),
            surface = Color(0xFF2A140B),
            border = Color(0xFFFF6600),
            secondary = Color(0xFFFFD600)
        ),
        // 4: Crimson Nova
        OverlayTheme(
            name = "CRIMSON NOVA",
            background = Color(0xFF120509),
            primary = Color(0xFFFF2A5F),
            onPrimary = Color(0xFFFFFFFF),
            surface = Color(0xFF2C111C),
            border = Color(0xFFFF2A5F),
            secondary = Color(0xFFFF6488)
        ),
        // 5: Electric Sunset
        OverlayTheme(
            name = "ELECTRIC SUNSET",
            background = Color(0xFF130410),
            primary = Color(0xFFFF007F),
            onPrimary = Color(0xFFFFFFFF),
            surface = Color(0xFF2D1027),
            border = Color(0xFFFF007F),
            secondary = Color(0xFFFF66B2)
        ),
        // 6: Arctic Frost
        OverlayTheme(
            name = "ARCTIC FROST",
            background = Color(0xFF060D17),
            primary = Color(0xFF38BDF8),
            onPrimary = Color(0xFF000000),
            surface = Color(0xFF16273B),
            border = Color(0xFF38BDF8),
            secondary = Color(0xFF7DD3FC)
        ),
        // 7: Neon Matrix
        OverlayTheme(
            name = "NEON MATRIX",
            background = Color(0xFF040E07),
            primary = Color(0xFF00FF66),
            onPrimary = Color(0xFF000000),
            surface = Color(0xFF0F2A16),
            border = Color(0xFF00FF66),
            secondary = Color(0xFF66FF99)
        )
    )

    val currentTheme = themes[themeIndex % themes.size]
    val Background = currentTheme.background
    val TextPrimary = Color(0xFFFFFFFF)
    val Primary = currentTheme.primary
    val OnPrimary = currentTheme.onPrimary
    val Surface = currentTheme.surface
    val Border = currentTheme.border.copy(alpha = 0.5f)
    val TextSecondary = Color(0xFFA6ADC8)
    val TextTertiary = Color(0xFFE2E8F0)
    val Success = currentTheme.secondary
    val SurfaceVariant = currentTheme.surface.copy(alpha = 0.8f)

    val context = LocalContext.current
    var showPinDialog by remember { mutableStateOf(false) }
    var showSecurityDialog by remember { mutableStateOf(false) }
    var enteredPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }
    var currentSecretKey by remember { mutableStateOf(KioskSecurity.getSharedSecret(context)) }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    var currentTimeStr by remember { mutableStateOf(timeFormat.format(Date())) }

    var licenseInfo by remember { mutableStateOf(HardwareLockManager.getLicenseInfo(context)) }
    var remainingTrialMillis by remember { mutableStateOf(maxOf(0L, licenseInfo.expiresAtMs - System.currentTimeMillis())) }

    LaunchedEffect(Unit) {
        while (true) {
            currentTimeStr = timeFormat.format(Date())
            val info = HardwareLockManager.getLicenseInfo(context)
            licenseInfo = info
            remainingTrialMillis = maxOf(0L, info.expiresAtMs - System.currentTimeMillis())
            delay(1000)
        }
    }

    Box(
        modifier = modifier.background(Background)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
            .systemBarsPadding()
        ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .alpha(0.8f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                currentTimeStr,
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.5).sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Wifi, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(16.dp))
                Icon(Icons.Filled.SignalCellular4Bar, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(16.dp))
                Icon(Icons.Filled.BatteryFull, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(16.dp))
                Text("${batteryStatus.level}%", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Annoying Battery Alert Banner (Loud & Pulsing)
        if (!isUnlicensed && batteryStatus.alertState != BatteryAlertState.NONE) {
            BatteryAlertBanner(batteryStatus = batteryStatus)
        }

        // 7-Day Free Trial Countdown Banner (Lock Screen Only)
        if (!isUnlicensed && !licenseInfo.isPaid && licenseInfo.state == HardwareLockManager.LicenseState.TRIAL_ACTIVE) {
            TrialCountdownBanner(
                remainingMs = remainingTrialMillis
            )
        }

        // Scrollable Area
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val isWide = maxWidth > 600.dp || isLandscape
            
            val mainContent: @Composable (Modifier) -> Unit = { modifier ->
                Column(
                    modifier = modifier,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Lock Icon Container
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .background(Primary.copy(alpha = 0.12f), CircleShape)
                            .border(1.5.dp, Primary.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isWaiting) {
                            Text("$paymentTimeout", color = Primary, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(
                                Icons.Filled.LockOpen,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))

                    // Compute clean device number alias (e.g. "PisoPhone 1" or from IP octet / custom alias)
                    var customAlias by remember { mutableStateOf(KioskSecurity.getDeviceAlias(context)) }
                    LaunchedEffect(Unit) {
                        while(true) {
                            customAlias = KioskSecurity.getDeviceAlias(context)
                            kotlinx.coroutines.delay(500)
                        }
                    }
                    val deviceNumber = remember(deviceIp) {
                        try {
                            val lastOctet = deviceIp.substringAfterLast(".").toIntOrNull()
                            if (lastOctet != null && lastOctet in 100..120) {
                                (lastOctet - 99).toString()
                            } else if (lastOctet != null && lastOctet in 1..254) {
                                lastOctet.toString()
                            } else {
                                "1"
                            }
                        } catch (e: Exception) {
                            "1"
                        }
                    }
                    val mainTitle = if (customAlias.isNotBlank()) customAlias else "PisoPhone $deviceNumber"

                    Text(
                        mainTitle,
                        color = TextPrimary,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )

                    // Device IP & System Badge reflecting ESP32 Web Page config
                    Surface(
                        color = SurfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(bottom = 10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(if (deviceIp != "127.0.0.1") Primary else Color.Gray, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (customAlias.isNotBlank()) "$mainTitle • IP: $deviceIp" else "IP: $deviceIp",
                                color = TextTertiary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Text(
                        if (isWaiting) "Coins inserted: $coinsInserted" else "Insert a coin to unlock all applications for a $minutesPerCoin-minute session.",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)
                    )

                    // Info Card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Surface, RoundedCornerShape(24.dp))
                            .border(1.dp, Border, RoundedCornerShape(24.dp))
                            .padding(24.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column {
                                Text(
                                    "STANDARD RATE",
                                    color = Primary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(String.format(java.util.Locale.US, "%.2f PHP", pricePerCoin), color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Medium)
                                    Text("/ unit", color = TextTertiary, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .background(SurfaceVariant, RoundedCornerShape(12.dp))
                                    .padding(8.dp)
                                ) {
                                Icon(Icons.Filled.Token, contentDescription = null, tint = Primary)
                            }
                        }

                        // Alert Info Box
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Background, RoundedCornerShape(16.dp))
                                .border(1.dp, Border, RoundedCornerShape(16.dp))
                                .padding(16.dp)
                                .padding(bottom = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Info, contentDescription = null, tint = Success, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                if (isWaiting) "Waiting for coins from coinslot..." else "Device will auto-lock when timer expires. Save all work and remove account credentials before end of session.",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        
                        if (isWaiting && coinsInserted > 0) {
                            Button(
                                onClick = onDoneClick,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Success, contentColor = Color.Black),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("DONE (${paymentTimeout}s)", fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 1.sp)
                            }
                        } else if (!isWaiting) {
                            val activeContainerColor = if (isSlotBusy) Color(0xFFDC3545) else if (isEsp32Online) Primary else SurfaceVariant
                            val activeContentColor = if (isSlotBusy) Color.White else if (isEsp32Online) OnPrimary else TextTertiary
                            val activeText = if (isSlotBusy) "COINSLOT BUSY" else if (isEsp32Online) buttonText else "CONNECTING TO COINSLOT..."
                            Button(
                                onClick = onInsertCoin,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = activeContainerColor, 
                                    contentColor = activeContentColor,
                                    disabledContainerColor = activeContainerColor,
                                    disabledContentColor = activeContentColor
                                ),
                                shape = RoundedCornerShape(14.dp),
                                enabled = isEsp32Online && !isSlotBusy
                            ) {
                                if (isSlotBusy) {
                                    Icon(Icons.Filled.Lock, contentDescription = null, tint = Color.White)
                                } else if (isEsp32Online) {
                                    Icon(Icons.Filled.AddCircle, contentDescription = null)
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(activeText, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.5.sp)
                            }
                        } else {
                            Button(
                                onClick = {},
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SurfaceVariant, 
                                    contentColor = TextPrimary,
                                    disabledContainerColor = SurfaceVariant,
                                    disabledContentColor = TextPrimary
                                ),
                                shape = RoundedCornerShape(14.dp),
                                enabled = false
                            ) {
                                CircularProgressIndicator(color = Primary, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("${paymentTimeout}s WAITING...", fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.5.sp)
                            }
                        }
                    }
                }
            }
            
            val bottomSection: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier = modifier) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Surface, RoundedCornerShape(28.dp))
                            .padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(if (isEsp32Online) Success else Color.Red, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        if (isEsp32Online) "HARDWARE CONTROLLER CONNECTED" else "HARDWARE CONTROLLER OFFLINE", 
                                        color = TextPrimary, 
                                        fontSize = 11.sp, 
                                        fontWeight = FontWeight.Bold, 
                                        letterSpacing = 0.5.sp
                                    )
                                    Text(
                                        if (isEsp32Online) "Autonomous Discovery & Interlock Synchronized" else "Searching for ESP32 on network...", 
                                        color = TextTertiary, 
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp)
                            .alpha(0.6f),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onThemeChange) { Icon(Icons.Filled.Palette, contentDescription = "Change Theme", tint = TextPrimary) }
                        Spacer(modifier = Modifier.width(16.dp))
                        IconButton(onClick = { showPinDialog = true }) { Icon(Icons.Filled.AdminPanelSettings, contentDescription = "Security Vault", tint = TextPrimary) }
                    }
                }
            }

            if (isWide) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                        mainContent(Modifier.padding(top = 16.dp))
                    }
                    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                        bottomSection(Modifier.fillMaxWidth())
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    mainContent(Modifier.padding(horizontal = 24.dp).padding(top = 16.dp))
                    bottomSection(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp))
                }
            }
        }

        if (showPinDialog) {
            val pinFocusRequester = remember { FocusRequester() }
            val keyboardController = LocalSoftwareKeyboardController.current

            LaunchedEffect(Unit) {
                delay(150)
                pinFocusRequester.requestFocus()
                keyboardController?.show()
            }

            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(0.9f).padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Surface)
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text("Admin Authentication", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 20.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Enter Master Admin Password:", color = TextSecondary, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = enteredPin,
                            onValueChange = { if (it.length <= 32) enteredPin = it },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            isError = pinError,
                            textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = Primary,
                                unfocusedBorderColor = Border
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(pinFocusRequester)
                        )
                            if (pinError) {
                                Text("Invalid password. Default is 1234", color = Color(0xFFFF6B6B), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { 
                                    showPinDialog = false
                                    enteredPin = ""
                                    pinError = false
                                }) { Text("Cancel") }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(onClick = {
                                    if (KioskSecurity.verifyAdminPin(context, enteredPin)) {
                                        showPinDialog = false
                                        enteredPin = ""
                                        pinError = false
                                        currentSecretKey = KioskSecurity.getSharedSecret(context)
                                        showSecurityDialog = true
                                    } else {
                                        pinError = true
                                    }
                                }) { Text("Unlock") }
                            }
                        }
                    }
                }
            }

            if (showSecurityDialog) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)).clickable(enabled = false) {},
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.92f).padding(12.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
                    ) {
                        Box(modifier = Modifier.padding(12.dp)) {
                            SecurityVaultView(
                                context = context,
                                onClose = { showSecurityDialog = false }
                            )
                        }
                    }
                }
            }
            
            if (isUnlicensed) {
                var activationCode by remember { mutableStateOf("") }
                Box(
                    modifier = Modifier.fillMaxSize().background(Background).clickable(enabled = false) {},
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(top = 100.dp, start = 32.dp, end = 32.dp).background(Surface, RoundedCornerShape(24.dp)).border(2.dp, Border, RoundedCornerShape(24.dp)).padding(32.dp)
                    ) {
                        Icon(Icons.Filled.Lock, contentDescription = "Locked", tint = Color.Red, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("DEVICE ACTIVATION REQUIRED", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text("This Piso phone machine is unlicensed.", color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Text("Hardware ID (MAC):", color = TextSecondary, fontSize = 12.sp)
                        Text(macAddress.ifEmpty { "UNKNOWN" }, color = Primary, fontSize = 18.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.height(24.dp))
                        
                        OutlinedTextField(
                            value = activationCode,
                            onValueChange = { activationCode = it.trim().uppercase() },
                            placeholder = { Text("e.g. PISO-XXXX-XXXX", color = TextSecondary) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Primary,
                                unfocusedBorderColor = Border,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { onActivateClick(activationCode) },
                            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) {
                            Text("ACTIVATE MACHINE", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TrialCountdownBanner(
    remainingMs: Long,
    modifier: Modifier = Modifier
) {
    val totalSec = maxOf(0L, remainingMs / 1000L)
    val days = (totalSec / (60 * 60 * 24)).toInt()
    val hours = ((totalSec / (60 * 60)) % 24).toInt()
    val minutes = ((totalSec / 60) % 60).toInt()
    val seconds = (totalSec % 60).toInt()

    val bannerGold = Color(0xFFF59E0B)
    val bannerGoldBg = Color(0xFF1C1917)
    val borderGold = Color(0xFF78350F)
    val cyanAccent = Color(0xFF38BDF8)

    Surface(
        color = bannerGoldBg,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, borderGold),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(bannerGold, CircleShape)
                    )
                    Text(
                        "7-DAY FREE TRIAL ACTIVE",
                        color = bannerGold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp
                    )
                }

                Text(
                    "All games unlocked",
                    color = cyanAccent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val timeStr = if (days > 0) {
                    "${days}d ${"%02d".format(hours)}h ${"%02d".format(minutes)}m ${"%02d".format(seconds)}s"
                } else {
                    "${"%02d".format(hours)}h ${"%02d".format(minutes)}m ${"%02d".format(seconds)}s"
                }
                Text(
                    timeStr,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "remaining",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}


@Composable
fun FloatingBall(
    timeRemaining: Int,
    onInsertCoinClick: () -> Unit,
    coinsInserted: Int,
    paymentTimeout: Int,
    onDoneClick: () -> Unit,
    isEsp32Online: Boolean,
    isSlotBusy: Boolean = false,
    isWaiting: Boolean,
    themeIndex: Int = 0,
    batteryStatus: BatteryStatus = BatteryStatus(),
    onRequestFocus: (Boolean) -> Unit = {},
    onBrightnessChange: (Float) -> Unit = {},
    onDrag: (Float, Float) -> Unit
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    
    // Auto-expand if we enter waiting state
    LaunchedEffect(isWaiting) {
        if (isWaiting) {
            expanded = true
        }
    }
    
    // Admin Access State in Game Space (Unlocked Session)
    var showUnlockedPinDialog by remember { mutableStateOf(false) }
    var showUnlockedSecurityDialog by remember { mutableStateOf(false) }
    var unlockedEnteredPin by remember { mutableStateOf("") }
    var unlockedPinError by remember { mutableStateOf(false) }
    var unlockedSecretKey by remember { mutableStateOf(KioskSecurity.getSharedSecret(context)) }

    // Request window focus whenever admin PIN dialog or Security Vault is open
    LaunchedEffect(showUnlockedPinDialog, showUnlockedSecurityDialog) {
        onRequestFocus(showUnlockedPinDialog || showUnlockedSecurityDialog)
    }
    
    val minutes = timeRemaining / 60
    val seconds = timeRemaining % 60
    val timeStr = String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
    
    val isFlashing = timeRemaining in 1..59
    val timerTextColor by if (isFlashing) {
        rememberUpdatedState(Color(0xFFFF3333))
    } else {
        rememberUpdatedState(Color.White)
    }
    
    val themes = listOf(
        // 0: Cyber Slate
        Triple(Color(0xFF00E5FF), Color(0xFF000000), Color(0xFF1E293B)),
        // 1: Titanium Violet
        Triple(Color(0xFF818CF8), Color(0xFFFFFFFF), Color(0xFF1E1B2E)),
        // 2: Steel Emerald
        Triple(Color(0xFF10B981), Color(0xFF000000), Color(0xFF132820)),
        // 3: Solar Obsidian
        Triple(Color(0xFFF59E0B), Color(0xFF000000), Color(0xFF241C13)),
        // 4: Crimson Nova
        Triple(Color(0xFFFF2A5F), Color(0xFFFFFFFF), Color(0xFF2C111C)),
        // 5: Electric Sunset
        Triple(Color(0xFFFF007F), Color(0xFFFFFFFF), Color(0xFF2D1027)),
        // 6: Arctic Frost
        Triple(Color(0xFF38BDF8), Color(0xFF000000), Color(0xFF16273B)),
        // 7: Neon Matrix
        Triple(Color(0xFF00FF66), Color(0xFF000000), Color(0xFF0F2A16))
    )
    val theme = themes[themeIndex % themes.size]
    val coroutineScope = rememberCoroutineScope()
    
    val Outline = theme.first
    val Surface = theme.third
    val Primary = theme.first
    val OnPrimary = theme.second
    val SurfaceVariant = Color(0xFF1E293B)
    val TextTertiary = Color(0xFFE2E8F0)

    // Hardware Audio Manager
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var currentVolume by remember { mutableFloatStateOf(0f) }
    var isMuted by remember { mutableStateOf(false) }
    var preMuteVolume by remember { mutableFloatStateOf(0f) }

    // System Brightness Manager
    val maxBrightness = remember {
        var maxB = 255
        try {
            val resources = context.resources
            val id = resources.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android")
            if (id != 0) {
                maxB = resources.getInteger(id)
            }
        } catch(e: Exception) {}
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val method = pm.javaClass.getDeclaredMethod("getMaximumScreenBrightnessSetting")
            method.isAccessible = true
            val valPm = method.invoke(pm) as Int
            if (valPm > 0) maxB = valPm
        } catch(e: Exception) {}
        maxB.toFloat()
    }

    var currentBrightness by remember { mutableFloatStateOf(maxBrightness * 0.6f) }

    fun applyBrightness(value: Float) {
        currentBrightness = value.coerceIn(10f, maxBrightness)
        try {
            if (android.provider.Settings.System.canWrite(context)) {
                android.provider.Settings.System.putInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                android.provider.Settings.System.putInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, currentBrightness.toInt())
            } else {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Toast.makeText(context, "Please allow 'Modify system settings' for Game Space brightness control", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            // Fallback gracefully
        }
        onBrightnessChange(currentBrightness / maxBrightness)
    }

    // Hardware Memory / RAM Manager
    val activityManager = remember { context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager }
    var ramStats by remember { mutableStateOf(Pair(1800L, 4000L)) } // Used MB, Total MB
    var isBoosting by remember { mutableStateOf(false) }

    fun refreshRam() {
        try {
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            val totalMb = memInfo.totalMem / (1024 * 1024)
            val availMb = memInfo.availMem / (1024 * 1024)
            val usedMb = (totalMb - availMb).coerceAtLeast(0)
            ramStats = Pair(usedMb, totalMb)
        } catch (e: Exception) {
            // fallback
        }
    }

    // Battery Manager
    val batteryManager = remember { context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager }
    var batteryPct by remember { mutableIntStateOf(100) }

    // Lazy Monitoring: Only poll & query hardware metrics when user expands the HUD
    LaunchedEffect(expanded) {
        if (expanded) {
            // Query initial values once opened
            try {
                val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                currentVolume = curVol
                preMuteVolume = curVol
                isMuted = curVol == 0f
            } catch (e: Exception) {}

            try {
                val curBright = android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
                currentBrightness = curBright.toFloat().coerceIn(10f, maxBrightness)
            } catch (e: Exception) {
                currentBrightness = maxBrightness * 0.6f
            }

            try {
                batteryPct = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 88
            } catch (e: Exception) {}

            refreshRam()
        }
    }

    val config = LocalConfiguration.current
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
    val maxOverlayHeight = (config.screenHeightDp.dp - 24.dp).coerceAtLeast(240.dp)

    val dragModifier = Modifier.pointerInput(Unit) {
        detectDragGestures { change, dragAmount ->
            change.consume()
            onDrag(dragAmount.x, dragAmount.y)
        }
    }

    if (expanded) {
        Box(
            modifier = Modifier
                .width(if (isLandscape) 320.dp else 300.dp)
                .heightIn(max = maxOverlayHeight)
                .background(Surface.copy(alpha = 0.96f), RoundedCornerShape(18.dp))
                .border(1.5.dp, Outline.copy(alpha = 0.75f), RoundedCornerShape(18.dp))
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Annoying Battery Alert Banner in HUD
                if (batteryStatus.alertState != BatteryAlertState.NONE) {
                    BatteryAlertBanner(batteryStatus = batteryStatus)
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // Game Space Header & Drag Handle (User can drag HUD from here)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(dragModifier)
                        .background(Color(0xFF0F172A).copy(alpha = 0.85f), RoundedCornerShape(10.dp))
                        .border(1.dp, Outline.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Drag Indicator dots
                        Column(
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                Box(modifier = Modifier.size(3.dp).background(Outline, CircleShape))
                                Box(modifier = Modifier.size(3.dp).background(Outline, CircleShape))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                Box(modifier = Modifier.size(3.dp).background(Outline, CircleShape))
                                Box(modifier = Modifier.size(3.dp).background(Outline, CircleShape))
                            }
                        }

                        var currentAlias by remember { mutableStateOf(KioskSecurity.getDeviceAlias(context)) }
                        LaunchedEffect(Unit) {
                            while(true) {
                                currentAlias = KioskSecurity.getDeviceAlias(context)
                                kotlinx.coroutines.delay(2000)
                            }
                        }
                        Text(
                            text = if (currentAlias.isNotBlank()) currentAlias.uppercase() else "GAME SPACE",
                            color = Outline,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Secret 5-second hold gesture on the timer display text
                        Box(
                            modifier = Modifier
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onPress = {
                                            val startTime = System.currentTimeMillis()
                                            val job = coroutineScope.launch {
                                                while (isActive) {
                                                    val elapsed = System.currentTimeMillis() - startTime
                                                    if (elapsed >= 5000L) {
                                                        unlockedEnteredPin = ""
                                                        unlockedPinError = false
                                                        showUnlockedPinDialog = true
                                                        try {
                                                            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(150, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                                                            } else {
                                                                @Suppress("DEPRECATION")
                                                                vibrator?.vibrate(150)
                                                            }
                                                        } catch (_: Exception) {}
                                                        break
                                                    }
                                                    delay(50L)
                                                }
                                            }
                                            tryAwaitRelease()
                                            job.cancel()
                                        }
                                    )
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = timeStr,
                                color = timerTextColor,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 18.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(
                            onClick = { 
                                if (isWaiting) {
                                    onDoneClick() // cancel or complete waiting session immediately
                                }
                                expanded = false 
                            }, 
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Scrollable Game Space Body (Brightness, Volume, RAM Booster, Coin Slot)
                val scrollState = rememberScrollState()
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(scrollState)
                ) {
                    // Quick Status HUD Chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Battery Chip
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.BatteryFull, contentDescription = null, tint = Outline, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("$batteryPct%", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // RAM Usage Chip
                        val ramPct = if (ramStats.second > 0) ((ramStats.first.toFloat() / ramStats.second) * 100).toInt() else 45
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Memory, contentDescription = null, tint = Color(0xFF00FF88), modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("$ramPct% RAM", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Turbo Status Chip
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                                .border(1.dp, Outline.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Bolt, contentDescription = null, tint = Color(0xFFFFD600), modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("TURBO", color = Color(0xFFFFD600), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }

                    // 1. SCREEN BRIGHTNESS CONTROL (Minimalist row slider)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0B132B).copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val brightnessPct = ((currentBrightness / maxBrightness) * 100).toInt()
                        Icon(
                            if (brightnessPct > 60) Icons.Filled.BrightnessHigh else if (brightnessPct > 25) Icons.Filled.BrightnessMedium else Icons.Filled.BrightnessLow,
                            contentDescription = null,
                            tint = Color(0xFFFFB800),
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Slider(
                            value = currentBrightness,
                            onValueChange = { applyBrightness(it) },
                            valueRange = 10f..maxBrightness,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFFFFB800),
                                activeTrackColor = Color(0xFFFFB800),
                                inactiveTrackColor = Color(0xFF334155)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(24.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "$brightnessPct%", 
                            color = Color(0xFFFFB800), 
                            fontSize = 11.sp, 
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // 2. SOUND / VOLUME CONTROL (Minimalist row slider)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0B132B).copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val volumePct = ((currentVolume / maxVolume) * 100).toInt()
                        Icon(
                            if (currentVolume == 0f) Icons.AutoMirrored.Filled.VolumeMute else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Volume Icon",
                            tint = Outline,
                            modifier = Modifier
                                .size(15.dp)
                                .clickable {
                                    if (currentVolume > 0) {
                                        preMuteVolume = currentVolume
                                        currentVolume = 0f
                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                                        isMuted = true
                                    } else {
                                        val restored = if (preMuteVolume > 0) preMuteVolume else (maxVolume * 0.5f)
                                        currentVolume = restored
                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restored.toInt(), 0)
                                        isMuted = false
                                    }
                                }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Slider(
                            value = currentVolume,
                            onValueChange = {
                                currentVolume = it
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, it.toInt(), 0)
                                isMuted = it == 0f
                            },
                            valueRange = 0f..maxVolume.toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = Outline,
                                activeTrackColor = Outline,
                                inactiveTrackColor = Color(0xFF334155)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(24.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "$volumePct%", 
                            color = Outline, 
                            fontSize = 11.sp, 
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // 3. RAM CLEANER / TURBO OPTIMIZER (Compact pill)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0B132B).copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CleaningServices, contentDescription = null, tint = Color(0xFF00FF88), modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("RAM", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                "${String.format(Locale.US, "%.1f", ramStats.first / 1024.0)} / ${String.format(Locale.US, "%.1f", ramStats.second / 1024.0)} GB",
                                color = Color(0xFFA6ADC8),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Button(
                            onClick = {
                                isBoosting = true
                                try {
                                    val beforeUsed = ramStats.first
                                    val runningApps = activityManager.runningAppProcesses ?: emptyList()
                                    for (proc in runningApps) {
                                        if (proc.processName != context.packageName) {
                                            activityManager.killBackgroundProcesses(proc.processName)
                                        }
                                    }
                                    System.gc()
                                    Runtime.getRuntime().gc()
                                    refreshRam()
                                    val freed = (beforeUsed - ramStats.first).coerceAtLeast(160L)
                                    Toast.makeText(context, "⚡ Turbo Boost: Freed ${freed}MB RAM", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "⚡ RAM Cleaned & Boosted for Gaming", Toast.LENGTH_SHORT).show()
                                }
                                isBoosting = false
                            },
                            modifier = Modifier.height(26.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00FF88),
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Filled.RocketLaunch, contentDescription = null, modifier = Modifier.size(11.dp), tint = Color.Black)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("BOOST", fontWeight = FontWeight.ExtraBold, fontSize = 10.sp)
                        }
                    }

                    // Navigation Actions (Home)
                    Button(
                        onClick = { 
                            expanded = false
                            AppLauncher.launchHome(context)
                        },
                        modifier = Modifier.fillMaxWidth().height(36.dp).padding(vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B), contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Filled.Home, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("HOME", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    }

                    // 4. ARCADE COIN SLOT / ADD TIME SECTION (INSIDE SCROLLABLE AREA)
                    if (isWaiting) {
                        if (coinsInserted > 0) {
                            Button(
                                onClick = {
                                    expanded = false
                                    onDoneClick()
                                },
                                modifier = Modifier.fillMaxWidth().height(40.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB2F2BB), contentColor = Color(0xFF00501E)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("DONE (${paymentTimeout}s) • $coinsInserted COIN(S)", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        } else {
                            Button(
                                onClick = {},
                                modifier = Modifier.fillMaxWidth().height(40.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SurfaceVariant, 
                                    contentColor = Color.White,
                                    disabledContainerColor = SurfaceVariant,
                                    disabledContentColor = Color.White
                                ),
                                enabled = false,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("${paymentTimeout}s WAITING FOR COIN...", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                    } else {
                        val activeContainerColor = if (isSlotBusy) Color(0xFFDC3545) else if (isEsp32Online) Primary else SurfaceVariant
                        val activeContentColor = if (isSlotBusy) Color.White else if (isEsp32Online) OnPrimary else TextTertiary
                        val activeText = if (isSlotBusy) "COINSLOT BUSY" else if (isEsp32Online) "ADD TIME (DROP COIN)" else "ESP32 OFFLINE"
                        Button(
                            onClick = {
                                onInsertCoinClick()
                            },
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = activeContainerColor,
                                contentColor = activeContentColor,
                                disabledContainerColor = activeContainerColor,
                                disabledContentColor = activeContentColor
                            ),
                            shape = RoundedCornerShape(10.dp),
                            enabled = isEsp32Online && !isSlotBusy
                        ) {
                            if (isSlotBusy) {
                                Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                            } else if (isEsp32Online) {
                                Icon(Icons.Filled.AddCircle, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(activeText, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
            }

            // Inline Admin PIN Card inside HUD Overlay (Safe for WindowManager overlays)
            if (showUnlockedPinDialog) {
                val unlockedPinFocusRequester = remember { FocusRequester() }
                val keyboardController = LocalSoftwareKeyboardController.current

                LaunchedEffect(Unit) {
                    delay(150)
                    unlockedPinFocusRequester.requestFocus()
                    keyboardController?.show()
                }

                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color(0xFF0F172A).copy(alpha = 0.97f), RoundedCornerShape(18.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.AdminPanelSettings, contentDescription = null, tint = Outline, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Admin Authentication", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Enter Master Admin Password:", color = Color(0xFFA6ADC8), fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = unlockedEnteredPin,
                            onValueChange = { 
                                if (it.length <= 32) {
                                    unlockedEnteredPin = it
                                    unlockedPinError = false
                                }
                            },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            placeholder = { Text("Password", color = Color.Gray) },
                            singleLine = true,
                            isError = unlockedPinError,
                            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF6366F1),
                                unfocusedBorderColor = Color(0xFF475569),
                                focusedContainerColor = Color(0xFF1E293B),
                                unfocusedContainerColor = Color(0xFF1E293B)
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(unlockedPinFocusRequester)
                        )
                        if (unlockedPinError) {
                            Text("Invalid password. Default is 1234", color = Color(0xFFFF6B6B), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = {
                                showUnlockedPinDialog = false
                                unlockedEnteredPin = ""
                                unlockedPinError = false
                            }) {
                                Text("Cancel", color = Color(0xFFA6ADC8), fontSize = 12.sp)
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Button(
                                onClick = {
                                    if (KioskSecurity.verifyAdminPin(context, unlockedEnteredPin)) {
                                        showUnlockedPinDialog = false
                                        unlockedEnteredPin = ""
                                        unlockedPinError = false
                                        unlockedSecretKey = KioskSecurity.getSharedSecret(context)
                                        showUnlockedSecurityDialog = true
                                    } else {
                                        unlockedPinError = true
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary)
                            ) {
                                Text("Unlock", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Inline Security & Vault Card inside HUD Overlay (Safe for WindowManager overlays)
            if (showUnlockedSecurityDialog) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color(0xFF0F172A).copy(alpha = 0.98f), RoundedCornerShape(18.dp))
                        .padding(12.dp)
                ) {
                    SecurityVaultView(
                        context = context,
                        onClose = { showUnlockedSecurityDialog = false }
                    )
                }
            }
        }
    } else {
        // Minimalist Compact Floating Timer Pill (with secret 5-second hold for Admin)
        val hasBatteryAlert = batteryStatus.alertState != BatteryAlertState.NONE
        val isLowBattery = batteryStatus.alertState == BatteryAlertState.LOW_BATTERY_UNPLUGGED

        val pillBorderColor = when {
            isLowBattery -> Color(0xFFFF2222)
            hasBatteryAlert -> Color(0xFFFFB800)
            else -> Outline.copy(alpha = 0.75f)
        }

        Box(
            modifier = Modifier
                .height(30.dp)
                .wrapContentWidth()
                .then(dragModifier)
                .background(Surface.copy(alpha = 0.85f), CircleShape)
                .border(if (hasBatteryAlert) 2.dp else 1.dp, pillBorderColor, CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { expanded = true },
                        onPress = {
                            val startTime = System.currentTimeMillis()
                            val job = coroutineScope.launch {
                                while (isActive) {
                                    val elapsed = System.currentTimeMillis() - startTime
                                    if (elapsed >= 5000L) {
                                        expanded = true
                                        unlockedEnteredPin = ""
                                        unlockedPinError = false
                                        showUnlockedPinDialog = true
                                        try {
                                            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(150, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                                            } else {
                                                @Suppress("DEPRECATION")
                                                vibrator?.vibrate(150)
                                            }
                                        } catch (_: Exception) {}
                                        break
                                    }
                                    delay(50L)
                                }
                            }
                            tryAwaitRelease()
                            job.cancel()
                        }
                    )
                }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (hasBatteryAlert) {
                    Icon(
                        if (isLowBattery) Icons.Filled.BatteryFull else Icons.Filled.Bolt,
                        contentDescription = "Battery Alert",
                        tint = if (isLowBattery) Color(0xFFFF3333) else Color(0xFFFFB800),
                        modifier = Modifier.size(13.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(if (isEsp32Online) Outline else Color(0xFFFF5252), CircleShape)
                    )
                }
                Text(
                    text = timeStr,
                    color = timerTextColor,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun HelpInfoButton(
    title: String,
    description: String,
    onShowHelp: (String, String) -> Unit
) {
    IconButton(
        onClick = { onShowHelp(title, description) },
        modifier = Modifier.size(24.dp)
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(Color(0xFF334155)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "?",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                fontWeight = FontWeight.Black
            )
        }
    }
}

@Composable
fun SecurityVaultView(
    context: Context,
    onClose: () -> Unit
) {
    var activeHelpDialog by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Auto-Clear Cache state
    var autoClearEnabled by remember { mutableStateOf(KioskSecurity.isAutoClearOnSleepEnabled(context)) }
    var sleepTimeoutMins by remember { mutableIntStateOf(KioskSecurity.getSleepClearTimeoutMinutes(context)) }

    // Hidden Apps State
    var hiddenSet by remember { mutableStateOf(KioskSecurity.getHiddenApps(context)) }
    var showAppPicker by remember { mutableStateOf(false) }
    var appSearchQuery by remember { mutableStateOf("") }

    // Query installed apps dynamically
    val allInstalledApps = remember(context) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        resolveInfos.mapNotNull { resolveInfo ->
            val pkgName = resolveInfo.activityInfo.packageName
            if (pkgName == context.packageName) null
            else AppInfo(
                name = resolveInfo.loadLabel(pm).toString(),
                packageName = pkgName,
                icon = resolveInfo.activityInfo.loadIcon(pm),
                bitmap = try { resolveInfo.activityInfo.loadIcon(pm).toBitmap().asImageBitmap() } catch (_: Exception) { null }
            )
        }.sortedBy { it.name }
    }

    val hiddenAppsList = remember(allInstalledApps, hiddenSet) {
        allInstalledApps.filter { hiddenSet.contains(it.packageName) }
    }

    val filteredAllApps = remember(allInstalledApps, appSearchQuery) {
        if (appSearchQuery.isBlank()) allInstalledApps
        else allInstalledApps.filter { 
            it.name.contains(appSearchQuery, ignoreCase = true) || 
            it.packageName.contains(appSearchQuery, ignoreCase = true) 
        }
    }

    // Input text field colors for dark theme vault - crisp bright white text
    val vaultTextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedBorderColor = Color(0xFF6366F1),
        unfocusedBorderColor = Color(0xFF475569),
        focusedContainerColor = Color(0xFF1E293B),
        unfocusedContainerColor = Color(0xFF1E293B),
        focusedPlaceholderColor = Color.Gray,
        unfocusedPlaceholderColor = Color.Gray
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(4.dp)
        ) {
            // Vault Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = Color(0xFF6366F1), modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Security Vault & Admin Console", fontWeight = FontWeight.ExtraBold, color = Color.White, fontSize = 15.sp)
                }
                IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // AUTOMATED PISOPHONE naming: Synced automatically with ESP32 slot assignment (PisoPhone 1, PisoPhone 2, etc.)

            // ADMIN SYSTEM BYPASS & QUICK SHORTCUTS SECTION
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Admin System Bypass & Quick Tools", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(4.dp))
                    HelpInfoButton(
                        title = "Admin System Bypass & Quick Tools",
                        description = "Temporarily bypasses the kiosk lock screen overlay so administrators can configure Wi-Fi credentials, pair Bluetooth, or change Android system settings. Grants 15 minutes of unlocked maintenance time.",
                        onShowHelp = { t, d -> activeHelpDialog = Pair(t, d) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Wi-Fi Settings Quick Action
                Button(
                    onClick = {
                        AppLauncher.launchWifiSettings(context)
                        onClose()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7), contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).height(38.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Wi-Fi Settings", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                // Android Settings Quick Action
                Button(
                    onClick = {
                        AppLauncher.launchSettings(context)
                        onClose()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF475569), contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).height(38.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("System Settings", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 15-Min Admin Bypass Mode
                Button(
                    onClick = {
                        KioskService.triggerAdminBypass(context, 900)
                        onClose()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981), contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).height(36.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Filled.LockOpen, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("15m Bypass Mode", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                // Lock Terminal Now
                Button(
                    onClick = {
                        KioskService.triggerLockSession(context)
                        onClose()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626), contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).height(36.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Lock Terminal", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color(0xFF334155))

            // AUTO-CLEAR CACHE ON SLEEP SECTION
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text("Auto-Clear Cache On Sleep", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(4.dp))
                    HelpInfoButton(
                        title = "Auto-Clear Cache On Sleep",
                        description = "Automatically purges temporary app cache, browser cookies, and saved player session data when the device screen is turned off for the configured idle period.",
                        onShowHelp = { t, d -> activeHelpDialog = Pair(t, d) }
                    )
                }
                Switch(
                    checked = autoClearEnabled,
                    onCheckedChange = { isChecked ->
                        autoClearEnabled = isChecked
                        KioskSecurity.setAutoClearOnSleepEnabled(context, isChecked)
                        Toast.makeText(context, if (isChecked) "Auto-clear on sleep ENABLED" else "Auto-clear on sleep DISABLED", Toast.LENGTH_SHORT).show()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF10B981),
                        uncheckedThumbColor = Color.Gray,
                        uncheckedTrackColor = Color(0xFF334155)
                    )
                )
            }

            if (autoClearEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sleep Duration Before Clear:", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.width(4.dp))
                    HelpInfoButton(
                        title = "Sleep Duration Before Clear",
                        description = "Amount of time the screen must remain turned off before triggering automatic session reset and cache purge.",
                        onShowHelp = { t, d -> activeHelpDialog = Pair(t, d) }
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                
                val timeoutOptions = listOf(1, 3, 5, 10, 15, 30)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    timeoutOptions.forEach { mins ->
                        val isSelected = sleepTimeoutMins == mins
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(0xFF6366F1) else Color(0xFF1E293B))
                                .border(1.dp, if (isSelected) Color(0xFF818CF8) else Color(0xFF334155), RoundedCornerShape(8.dp))
                                .clickable {
                                    sleepTimeoutMins = mins
                                    KioskSecurity.setSleepClearTimeoutMinutes(context, mins)
                                    Toast.makeText(context, "Sleep clear timeout set to ${mins}m", Toast.LENGTH_SHORT).show()
                                }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${mins}m",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        KioskSecurity.clearAppCacheAndData(context)
                        Toast.makeText(context, "App cache & web session data cleared!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155), contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(32.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Filled.CleaningServices, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Clear Cache Now (Manual)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color(0xFF334155))

            // BATTERY HEALTH & CHARGER VOICE REMINDERS
            var batteryAlertsEnabled by remember { mutableStateOf(KioskSecurity.isBatteryAlertsEnabled(context)) }
            var lowBatteryThresh by remember { mutableIntStateOf(KioskSecurity.getLowBatteryThreshold(context)) }
            var highBatteryThresh by remember { mutableIntStateOf(KioskSecurity.getHighBatteryThreshold(context)) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text("Battery Health & TTS Reminders", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(4.dp))
                    HelpInfoButton(
                        title = "Battery Health & TTS Reminders",
                        description = "Monitors device battery status. Uses Text-to-Speech (TTS) voice announcements and alert tones to remind staff when the battery is low (plug charger) or full (disconnect charger) to prevent battery swelling.",
                        onShowHelp = { t, d -> activeHelpDialog = Pair(t, d) }
                    )
                }
                Switch(
                    checked = batteryAlertsEnabled,
                    onCheckedChange = { isChecked ->
                        batteryAlertsEnabled = isChecked
                        KioskSecurity.setBatteryAlertsEnabled(context, isChecked)
                        Toast.makeText(context, if (isChecked) "Battery TTS Reminders ENABLED" else "Battery TTS Reminders DISABLED", Toast.LENGTH_SHORT).show()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF10B981),
                        uncheckedThumbColor = Color.Gray,
                        uncheckedTrackColor = Color(0xFF334155)
                    )
                )
            }

            if (batteryAlertsEnabled) {
                Spacer(modifier = Modifier.height(10.dp))
                
                // Low Battery Plug-in Reminder Threshold
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Low Battery Warning Threshold:", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.width(4.dp))
                    HelpInfoButton(
                        title = "Low Battery Warning Threshold",
                        description = "Triggers voice reminders and alert tones when battery falls to or below this percentage.",
                        onShowHelp = { t, d -> activeHelpDialog = Pair(t, d) }
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                val lowOptions = listOf(10, 15, 20, 25, 30)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    lowOptions.forEach { pct ->
                        val isSelected = lowBatteryThresh == pct
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(0xFFEF4444) else Color(0xFF1E293B))
                                .border(1.dp, if (isSelected) Color(0xFFF87171) else Color(0xFF334155), RoundedCornerShape(8.dp))
                                .clickable {
                                    lowBatteryThresh = pct
                                    KioskSecurity.setLowBatteryThreshold(context, pct)
                                    Toast.makeText(context, "Low battery reminder threshold: ${pct}%", Toast.LENGTH_SHORT).show()
                                }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${pct}%",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // High Battery Disconnect Reminder Threshold
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Full Battery Warning Threshold:", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.width(4.dp))
                    HelpInfoButton(
                        title = "Full Battery Warning Threshold",
                        description = "Triggers voice reminders to unplug the charger when battery reaches this percentage to preserve lithium battery lifespan.",
                        onShowHelp = { t, d -> activeHelpDialog = Pair(t, d) }
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                val highOptions = listOf(70, 75, 80, 85, 90, 95)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    highOptions.forEach { pct ->
                        val isSelected = highBatteryThresh == pct
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(0xFFF59E0B) else Color(0xFF1E293B))
                                .border(1.dp, if (isSelected) Color(0xFFFBBF24) else Color(0xFF334155), RoundedCornerShape(8.dp))
                                .clickable {
                                    highBatteryThresh = pct
                                    KioskSecurity.setHighBatteryThreshold(context, pct)
                                    Toast.makeText(context, "High battery disconnect threshold: ${pct}%", Toast.LENGTH_SHORT).show()
                                }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${pct}%",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Test TTS Voice Button
                Button(
                    onClick = {
                        KioskService.triggerTestTts(context, "Arcade OS voice system online and functional.")
                        Toast.makeText(context, "Testing TTS Audio Output...", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1), contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(34.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Test Voice Engine (TTS)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color(0xFF334155))

            // HIDDEN APPS ADMIN VAULT SECTION
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text("Hidden Apps (Admin Access)", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(4.dp))
                    HelpInfoButton(
                        title = "Hidden Apps (Admin Restricted Access)",
                        description = "Applications marked as hidden are completely concealed from the customer game launcher. Only technicians in this Security Vault can launch or manage them. Launching any hidden app automatically unlocks a maintenance session so the app can be used without lock screen obstruction.",
                        onShowHelp = { t, d -> activeHelpDialog = Pair(t, d) }
                    )
                }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { showAppPicker = !showAppPicker },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (showAppPicker) Color(0xFF475569) else Color(0xFF10B981),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Icon(
                    if (showAppPicker) Icons.Filled.Close else Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (showAppPicker) "Done" else "Manage Apps", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // IF SHOW APP PICKER IS TRUE: SHOW FILTER & SELECTION LIST
        if (showAppPicker) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("Select Apps to Hide from Launcher:", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    
                    // Search bar for app picker with white text
                    OutlinedTextField(
                        value = appSearchQuery,
                        onValueChange = { appSearchQuery = it },
                        placeholder = { Text("Search installed apps...", color = Color.Gray) },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                        colors = vaultTextFieldColors,
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        filteredAllApps.forEach { app ->
                            val isHidden = hiddenSet.contains(app.packageName)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        KioskSecurity.toggleAppHidden(context, app.packageName)
                                        hiddenSet = KioskSecurity.getHiddenApps(context)
                                    }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (app.bitmap != null) {
                                    Image(
                                        bitmap = app.bitmap,
                                        contentDescription = app.name,
                                        modifier = Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))
                                    )
                                } else {
                                    Icon(Icons.Filled.Apps, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(24.dp))
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(app.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    Text(app.packageName, color = Color.Gray, fontSize = 10.sp)
                                }
                                Checkbox(
                                    checked = isHidden,
                                    onCheckedChange = {
                                        KioskSecurity.toggleAppHidden(context, app.packageName)
                                        hiddenSet = KioskSecurity.getHiddenApps(context)
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF10B981), checkmarkColor = Color.Black)
                                )
                            }
                            HorizontalDivider(color = Color(0xFF334155).copy(alpha = 0.5f))
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // HIDDEN APPS LAUNCH LIST (ONLY ACCESSIBLE VIA ADMIN VAULT)
        if (hiddenAppsList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E293B), RoundedCornerShape(12.dp))
                    .padding(14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("No apps hidden currently. Tap 'Manage Apps' above to hide apps.", color = Color(0xFFA6ADC8), fontSize = 11.sp)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E293B), RoundedCornerShape(12.dp))
                    .padding(8.dp)
            ) {
                hiddenAppsList.forEach { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            if (app.bitmap != null) {
                                Image(
                                    bitmap = app.bitmap,
                                    contentDescription = app.name,
                                    modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp))
                                )
                            } else {
                                Icon(Icons.Filled.Apps, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(28.dp))
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(app.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(app.packageName, color = Color(0xFFA6ADC8), fontSize = 10.sp)
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // LAUNCH BUTTON (WITH AUTO ADMIN BYPASS)
                            Button(
                                onClick = {
                                    val success = AppLauncher.launchApp(context, app.packageName, bypassKiosk = true)
                                    if (success) {
                                        onClose()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1), contentColor = Color.White),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Icon(Icons.Filled.RocketLaunch, contentDescription = "Launch", modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("LAUNCH", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            // UNHIDE BUTTON
                            IconButton(
                                onClick = {
                                    KioskSecurity.toggleAppHidden(context, app.packageName)
                                    hiddenSet = KioskSecurity.getHiddenApps(context)
                                },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Unhide", tint = Color(0xFFFF5252), modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                    HorizontalDivider(color = Color(0xFF334155).copy(alpha = 0.5f))
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Done / Close button
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155), contentColor = Color.White),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Done", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    // Modal In-View Dialog for Help descriptions (100% crash-safe in overlay window)
    if (activeHelpDialog != null) {
        val (helpTitle, helpDesc) = activeHelpDialog!!
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f))
                .clickable { activeHelpDialog = null },
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .padding(16.dp)
                    .clickable(enabled = false) {},
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = BorderStroke(1.dp, Color(0xFF6366F1).copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = null,
                            tint = Color(0xFF6366F1),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = helpTitle,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = helpDesc,
                        color = Color(0xFFCBD5E1),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(
                            onClick = { activeHelpDialog = null },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1), contentColor = Color.White),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Got it", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
}

@Composable
fun BatteryAlertBanner(
    batteryStatus: BatteryStatus,
    modifier: Modifier = Modifier
) {
    if (batteryStatus.alertState == BatteryAlertState.NONE) return

    val isLowBattery = batteryStatus.alertState == BatteryAlertState.LOW_BATTERY_UNPLUGGED

    val bgColor = if (isLowBattery) Color(0xFF350B0B).copy(alpha = 0.97f) else Color(0xFF332002).copy(alpha = 0.97f)
    val borderColor = if (isLowBattery) Color(0xFFFF2222) else Color(0xFFFFB800)
    val titleColor = if (isLowBattery) Color(0xFFFF4D4D) else Color(0xFFFFD13B)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = BorderStroke(2.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (isLowBattery) Color(0xFFFF2222).copy(alpha = 0.25f) else Color(0xFFFFB800).copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                if (isLowBattery) {
                    Icon(
                        Icons.Filled.BatteryFull,
                        contentDescription = "Low Battery",
                        tint = Color(0xFFFF3333),
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    Icon(
                        Icons.Filled.Bolt,
                        contentDescription = "Disconnect Charger",
                        tint = Color(0xFFFFB800),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isLowBattery) "⚠️ LOW BATTERY (${batteryStatus.level}%)" else "⚡ UNPLUG CHARGER (${batteryStatus.level}%)",
                    color = titleColor,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 12.sp,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isLowBattery) 
                        "Battery is below the threshold! Connect charger now to avoid shutdown." 
                    else 
                        "Battery reached the threshold! Please disconnect charger cable to preserve battery health.",
                    color = Color(0xFFF1F5F9),
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
