package com.pisophone.kiosk.overlay.ui

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.system.AndroidKioskSystemController
import com.pisophone.kiosk.system.KioskSystemController
import com.pisophone.kiosk.util.AppLauncher
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
    isEsp32Online: Boolean,
    isSlotBusy: Boolean = false,
    isWaiting: Boolean,
    themeIndex: Int = 0,
    batteryStatus: BatteryStatus = BatteryStatus(),
    isArenaMode: Boolean = false,
    arenaRole: Int = 0,
    arenaStakeMinutes: Int = 15,
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

    val batteryPct = batteryStatus.level

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
    } else if (expanded) {
        Box(
            modifier = Modifier
                .width(if (isLandscape) 320.dp else 300.dp)
                .heightIn(max = maxOverlayHeight)
                .background(if (isArenaMode) Color(0xFF1E1035).copy(alpha = 0.98f) else Surface.copy(alpha = 0.96f), RoundedCornerShape(18.dp))
                .border(if (isArenaMode) 2.dp else 1.5.dp, if (isArenaMode) Color(0xFF8B5CF6) else Outline.copy(alpha = 0.75f), RoundedCornerShape(18.dp))
                .padding(12.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (isArenaMode) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2E1065)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFA78BFA))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "⚔️ 1V1 ARENA ACTIVE",
                                color = Color(0xFFA78BFA),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = (if (arenaRole == 1) "PLAYER 1" else if (arenaRole == 2) "PLAYER 2" else "PARTICIPANT") + " • ${arenaStakeMinutes}m STAKE",
                                color = Color(0xFFFFB703),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                if (batteryStatus.alertState != BatteryAlertState.NONE) {
                    BatteryAlertBanner(batteryStatus = batteryStatus)
                    Spacer(modifier = Modifier.height(6.dp))
                }

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
                            while (isActive) {
                                currentAlias = KioskSecurity.getDeviceAlias(context)
                                delay(2000)
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
                                    onDoneClick()
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

                val scrollState = rememberScrollState()
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(scrollState)
                ) {
                    FloatingPillStatusChips(
                        batteryPct = batteryPct,
                        ramStats = ramStats,
                        outlineColor = Outline
                    )

                    FloatingPillBrightnessControl(
                        currentBrightness = currentBrightness,
                        maxBrightness = maxBrightness,
                        onBrightnessChange = { applyBrightness(it) }
                    )

                    FloatingPillVolumeControl(
                        currentVolume = currentVolume,
                        maxVolume = maxVolume,
                        outlineColor = Outline,
                        onVolumeChange = {
                            currentVolume = it
                            controller.setStreamVolume(it)
                        }
                    )

                    FloatingPillRamCleaner(
                        ramStats = ramStats,
                        onBoostClick = {
                            val freed = controller.optimizeMemory()
                            refreshRam()
                            android.widget.Toast.makeText(context, "⚡ Turbo Boost: Freed ${freed}MB RAM", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    )

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
                            onClick = { onInsertCoinClick() },
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = activeContainerColor, 
                                contentColor = activeContentColor,
                                disabledContainerColor = activeContainerColor,
                                disabledContentColor = activeContainerColor
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
        }
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
