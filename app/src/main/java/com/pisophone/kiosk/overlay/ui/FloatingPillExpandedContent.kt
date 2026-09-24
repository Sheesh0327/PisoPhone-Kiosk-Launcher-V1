package com.pisophone.kiosk.overlay.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.model.BatteryAlertState
import com.pisophone.kiosk.model.BatteryStatus
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.system.KioskSystemController
import com.pisophone.kiosk.util.AppLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun FloatingPillExpandedContent(
    context: Context,
    controller: KioskSystemController,
    coroutineScope: CoroutineScope,
    isLandscape: Boolean,
    maxOverlayHeight: androidx.compose.ui.unit.Dp,
    surfaceColor: Color,
    outlineColor: Color,
    primaryColor: Color,
    onPrimaryColor: Color,
    surfaceVariantColor: Color,
    textTertiaryColor: Color,
    isArenaMode: Boolean,
    arenaRole: Int,
    arenaStakeMinutes: Int,
    batteryStatus: BatteryStatus,
    timeStr: String,
    timerTextColor: Color,
    isWaiting: Boolean,
    coinsInserted: Int,
    paymentTimeout: Int,
    isEsp32Online: Boolean,
    isSlotBusy: Boolean,
    currentBrightness: Float,
    maxBrightness: Float,
    currentVolume: Int,
    maxVolume: Int,
    ramStats: Pair<Long, Long>,
    onBrightnessChange: (Float) -> Unit,
    onVolumeChange: (Int) -> Unit,
    onRefreshRam: () -> Unit,
    dragModifier: Modifier,
    onDoneClick: () -> Unit,
    onCancelClick: () -> Unit,
    onInsertCoinClick: () -> Unit,
    onTriggerSecurity: () -> Unit,
    onCloseExpanded: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(if (isLandscape) 320.dp else 300.dp)
            .heightIn(max = maxOverlayHeight)
            .background(if (isArenaMode) Color(0xFF1E1035).copy(alpha = 0.98f) else surfaceColor.copy(alpha = 0.96f), RoundedCornerShape(18.dp))
            .border(if (isArenaMode) 2.dp else 1.5.dp, if (isArenaMode) Color(0xFF8B5CF6) else outlineColor.copy(alpha = 0.75f), RoundedCornerShape(18.dp))
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
                    .border(1.dp, outlineColor.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Box(modifier = Modifier.size(3.dp).background(outlineColor, CircleShape))
                            Box(modifier = Modifier.size(3.dp).background(outlineColor, CircleShape))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Box(modifier = Modifier.size(3.dp).background(outlineColor, CircleShape))
                            Box(modifier = Modifier.size(3.dp).background(outlineColor, CircleShape))
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
                        color = outlineColor,
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
                                                    onTriggerSecurity()
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
                                if (coinsInserted > 0) {
                                    onDoneClick()
                                } else {
                                    onCancelClick()
                                }
                            }
                            onCloseExpanded()
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
                    batteryPct = batteryStatus.level,
                    ramStats = ramStats,
                    outlineColor = outlineColor
                )

                FloatingPillBrightnessControl(
                    currentBrightness = currentBrightness,
                    maxBrightness = maxBrightness,
                    onBrightnessChange = onBrightnessChange
                )

                FloatingPillVolumeControl(
                    currentVolume = currentVolume,
                    maxVolume = maxVolume,
                    outlineColor = outlineColor,
                    onVolumeChange = onVolumeChange
                )

                FloatingPillRamCleaner(
                    ramStats = ramStats,
                    onBoostClick = {
                        val freed = controller.optimizeMemory()
                        onRefreshRam()
                        android.widget.Toast.makeText(context, "⚡ Turbo Boost: Freed ${freed}MB RAM", android.widget.Toast.LENGTH_SHORT).show()
                    }
                )

                Button(
                    onClick = { 
                        onCloseExpanded()
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
                                onCloseExpanded()
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
                            onClick = {
                                onCloseExpanded()
                                onCancelClick()
                            },
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444), contentColor = Color.White),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("CANCEL (${paymentTimeout}s)", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                } else {
                    val activeContainerColor = if (isSlotBusy) Color(0xFFDC3545) else if (isEsp32Online) primaryColor else surfaceVariantColor
                    val activeContentColor = if (isSlotBusy) Color.White else if (isEsp32Online) onPrimaryColor else textTertiaryColor
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
}
