package com.pisophone.kiosk.overlay.ui

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
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
    onRequestFocus: (Boolean) -> Unit = {},
    onRequestFullScreen: (Boolean) -> Unit = {},
    onBrightnessChange: (Float) -> Unit = {},
    onDrag: (Float, Float) -> Unit
) {
    val context = LocalContext.current
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

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    val activityManager = remember { context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager }
    var ramStats by remember { mutableStateOf(Pair(0L, 0L)) }

    fun refreshRam() {
        try {
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            val totalMb = memoryInfo.totalMem / (1024 * 1024)
            val availMb = memoryInfo.availMem / (1024 * 1024)
            val usedMb = totalMb - availMb
            ramStats = Pair(usedMb, totalMb)
        } catch (_: Exception) {}
    }

    val maxBrightness = 255f
    var currentBrightness by remember {
        val sysVal = try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS).toFloat()
        } catch (_: Exception) { 150f }
        mutableFloatStateOf(sysVal)
    }

    fun applyBrightness(value: Float) {
        currentBrightness = value
        onBrightnessChange(value / maxBrightness)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.System.canWrite(context)) {
            try {
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value.toInt())
            } catch (_: Exception) {}
        }
    }

    val batteryManager = remember { context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager }
    var batteryPct by remember { mutableIntStateOf(88) }

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(3000)
            try {
                batteryPct = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 88
            } catch (_: Exception) {}
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
                .background(Surface.copy(alpha = 0.96f), RoundedCornerShape(18.dp))
                .border(1.5.dp, Outline.copy(alpha = 0.75f), RoundedCornerShape(18.dp))
                .padding(12.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
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
                                                        try {
                                                            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(150, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
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
                        audioManager = audioManager,
                        maxVolume = maxVolume,
                        outlineColor = Outline
                    )

                    FloatingPillRamCleaner(
                        context = context,
                        activityManager = activityManager,
                        ramStats = ramStats,
                        onRefreshRam = { refreshRam() }
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
                                        showUnlockedPinDialog = true
                                        try {
                                            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(150, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
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
