package com.pisophone.kiosk.overlay.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.model.BatteryAlertState
import com.pisophone.kiosk.model.BatteryStatus
import com.pisophone.kiosk.system.AndroidKioskSystemController
import com.pisophone.kiosk.system.KioskSystemController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun FloatingPill(
    timeRemaining: Int,
    onInsertCoinClick: () -> Unit,
    coinsInserted: Int,
    paymentTimeout: Int,
    onDoneClick: () -> Unit,
    onCancelClick: () -> Unit = {},
    isEsp32Online: Boolean,
    isSlotBusy: Boolean = false,
    isWaiting: Boolean,
    themeIndex: Int = 0,
    batteryStatus: BatteryStatus = BatteryStatus(),
    isArenaMode: Boolean = false,
    arenaRole: Int = 0,
    arenaStakeMinutes: Int = 15,
    isArenaBannerVisible: Boolean = false,
    onDismissArenaBanner: () -> Unit = {},
    systemController: KioskSystemController? = null,
    onRequestFocus: (Boolean) -> Unit = {},
    onRequestFullScreen: (Boolean) -> Unit = {},
    onBrightnessChange: (Float) -> Unit = {},
    onDrag: (Float, Float) -> Unit
) {
    val context = LocalContext.current
    val controller = systemController ?: remember(context) { AndroidKioskSystemController(context) }
    var expanded by remember { mutableStateOf(false) }
    
    LaunchedEffect(isWaiting) {
        if (isWaiting) {
            expanded = true
        }
    }

    LaunchedEffect(isArenaBannerVisible) {
        if (isArenaBannerVisible) {
            delay(3800L)
            onDismissArenaBanner()
        }
    }
    
    val coroutineScope = rememberCoroutineScope()
    
    var showUnlockedPinDialog by remember { mutableStateOf(false) }
    var showUnlockedSecurityDialog by remember { mutableStateOf(false) }

    LaunchedEffect(showUnlockedPinDialog, showUnlockedSecurityDialog) {
        val needActive = showUnlockedPinDialog || showUnlockedSecurityDialog
        onRequestFocus(needActive)
        onRequestFullScreen(needActive)
    }
    
    val minutes = timeRemaining / 60
    val seconds = timeRemaining % 60
    val timeStr = String.format(Locale.US, "%02d:%02d", minutes, seconds)
    
    val isFlashing = timeRemaining in 1..59
    val timerTextColor by if (isFlashing) {
        rememberUpdatedState(Color(0xFFFF3333))
    } else {
        rememberUpdatedState(Color.White)
    }

    val themes = listOf(
        Pair(Color(0xFF0F172A), Color(0xFF10B981)),
        Pair(Color(0xFF221140), Color(0xFFB026FF)),
        Pair(Color(0xFF0C2B1D), Color(0xFF00FF88)),
        Pair(Color(0xFF2A140B), Color(0xFFFF6600)),
        Pair(Color(0xFF2C111C), Color(0xFFFF2A5F)),
        Pair(Color(0xFF2D1027), Color(0xFFFF007F)),
        Pair(Color(0xFF16273B), Color(0xFF38BDF8)),
        Pair(Color(0xFF0F2A16), Color(0xFF00FF66))
    )
    val currentTheme = themes[themeIndex % themes.size]
    val Surface = currentTheme.first
    val Outline = currentTheme.second
    val Primary = Outline
    val OnPrimary = Color.Black
    val SurfaceVariant = Surface.copy(alpha = 0.8f)
    val TextTertiary = Color(0xFFA6ADC8)

    var currentVolume by remember { mutableIntStateOf(controller.getStreamVolume()) }
    val maxVolume = remember { controller.getStreamMaxVolume() }

    var ramStats by remember { mutableStateOf(controller.getMemoryStats()) }
    fun refreshRam() {
        ramStats = controller.getMemoryStats()
    }

    val maxBrightness = AndroidKioskSystemController.MAX_BRIGHTNESS
    var currentBrightness by remember {
        mutableFloatStateOf(controller.getScreenBrightness())
    }

    fun applyBrightness(value: Float) {
        currentBrightness = value
        onBrightnessChange(value / maxBrightness)
        controller.setScreenBrightness(value)
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(5000)
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

    val isSecurityActive = showUnlockedPinDialog || showUnlockedSecurityDialog

    if (isSecurityActive) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f))
                .clickable(enabled = false) {},
            contentAlignment = Alignment.Center
        ) {
            if (showUnlockedPinDialog) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .widthIn(max = 420.dp)
                        .padding(16.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
                ) {
                    Box(modifier = Modifier.padding(16.dp)) {
                        FloatingPillAdminAuthCard(
                            context = context,
                            outlineColor = Outline,
                            primaryColor = Primary,
                            onPrimaryColor = OnPrimary,
                            onDismiss = { showUnlockedPinDialog = false },
                            onUnlockSuccess = {
                                showUnlockedPinDialog = false
                                showUnlockedSecurityDialog = true
                            }
                        )
                    }
                }
            } else if (showUnlockedSecurityDialog) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.95f)
                        .fillMaxHeight(0.92f)
                        .padding(12.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
                ) {
                    Box(modifier = Modifier.padding(12.dp)) {
                        SecurityVaultView(
                            context = context,
                            onClose = { showUnlockedSecurityDialog = false }
                        )
                    }
                }
            }
        }
    } else if (isArenaBannerVisible) {
        Box(
            modifier = Modifier
                .width(if (isLandscape) 340.dp else 300.dp)
                .then(dragModifier)
        ) {
            ArenaModeBanner(
                visible = true,
                playerRole = arenaRole,
                stakeMinutes = arenaStakeMinutes,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDismissArenaBanner() }
            )
        }
    } else if (expanded) {
        FloatingPillExpandedContent(
            context = context,
            controller = controller,
            coroutineScope = coroutineScope,
            isLandscape = isLandscape,
            maxOverlayHeight = maxOverlayHeight,
            surfaceColor = Surface,
            outlineColor = Outline,
            primaryColor = Primary,
            onPrimaryColor = OnPrimary,
            surfaceVariantColor = SurfaceVariant,
            textTertiaryColor = TextTertiary,
            isArenaMode = isArenaMode,
            arenaRole = arenaRole,
            arenaStakeMinutes = arenaStakeMinutes,
            batteryStatus = batteryStatus,
            timeStr = timeStr,
            timerTextColor = timerTextColor,
            isWaiting = isWaiting,
            coinsInserted = coinsInserted,
            paymentTimeout = paymentTimeout,
            isEsp32Online = isEsp32Online,
            isSlotBusy = isSlotBusy,
            currentBrightness = currentBrightness,
            maxBrightness = maxBrightness,
            currentVolume = currentVolume,
            maxVolume = maxVolume,
            ramStats = ramStats,
            onBrightnessChange = { applyBrightness(it) },
            onVolumeChange = {
                currentVolume = it
                controller.setStreamVolume(it)
            },
            onRefreshRam = { refreshRam() },
            dragModifier = dragModifier,
            onDoneClick = onDoneClick,
            onCancelClick = onCancelClick,
            onInsertCoinClick = onInsertCoinClick,
            onTriggerSecurity = { showUnlockedPinDialog = true },
            onCloseExpanded = { expanded = false }
        )
    } else {
        val hasBatteryAlert = batteryStatus.alertState != BatteryAlertState.NONE
        val isLowBattery = batteryStatus.alertState == BatteryAlertState.LOW_BATTERY_UNPLUGGED

        val pillBorderColor = when {
            isLowBattery -> Color(0xFFFF2222)
            hasBatteryAlert -> Color(0xFFFFB800)
            isArenaMode -> Color(0xFF8B5CF6)
            else -> Outline.copy(alpha = 0.75f)
        }

        val pillBgColor = when {
            isArenaMode -> Color(0xFF1E1035).copy(alpha = 0.95f)
            else -> Surface.copy(alpha = 0.85f)
        }

        Box(
            modifier = Modifier
                .height(30.dp)
                .wrapContentWidth()
                .then(dragModifier)
                .background(pillBgColor, CircleShape)
                .border(if (isArenaMode || hasBatteryAlert) 2.dp else 1.dp, pillBorderColor, CircleShape)
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
                                        showUnlockedPinDialog = true
                                        controller.triggerHapticFeedback()
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
                if (isArenaMode) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF8B5CF6),
                        modifier = Modifier.padding(end = 2.dp)
                    ) {
                        Text(
                            text = if (arenaRole == 1) "⚔️ P1" else if (arenaRole == 2) "⚔️ P2" else "⚔️ 1v1",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                } else if (hasBatteryAlert) {
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
