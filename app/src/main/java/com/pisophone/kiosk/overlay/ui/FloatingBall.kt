package com.pisophone.kiosk.overlay.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    val timeStr = String.format(Locale.US, "%02d:%02d", minutes, seconds)
    
    val isFlashing = timeRemaining in 1..59
    val timerTextColor by if (isFlashing) {
        rememberUpdatedState(Color(0xFFFF3333))
    } else {
        rememberUpdatedState(Color.White)
    }
    
    val coroutineScope = rememberCoroutineScope()
    
    val Primary = Color(0xFF00E5FF)
    val OnPrimary = Color(0xFF000000)
    val Outline = Color(0xFF00E5FF)
    val Surface = Color(0xFF0F172A)
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
                // Battery Alert Banner in HUD
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
