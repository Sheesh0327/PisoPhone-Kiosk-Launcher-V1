package com.pisophone.kiosk.overlay.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.KioskService
import com.pisophone.kiosk.security.KioskSecurity

/**
 * Settings section for:
 * 1. Auto-Clear Cache On Sleep
 * 2. Battery Health Protection Range & TTS Voice Reminders
 *
 * Both sections are collapsed by default to prevent accidental slider/toggle adjustments while scrolling.
 * Tapping the title header expands the respective settings.
 */
@Composable
fun VaultSleepAndBatterySection(
    context: Context,
    onShowHelp: (String, String) -> Unit
) {
    var isAutoClearExpanded by remember { mutableStateOf(false) }
    var isBatteryExpanded by remember { mutableStateOf(false) }

    var autoClearEnabled by remember { mutableStateOf(KioskSecurity.isAutoClearOnSleepEnabled(context)) }
    var sleepTimeoutMins by remember { mutableIntStateOf(KioskSecurity.getSleepClearTimeoutMinutes(context)) }

    var batteryAlertsEnabled by remember { mutableStateOf(KioskSecurity.isBatteryAlertsEnabled(context)) }
    var lowBatteryThresh by remember { mutableIntStateOf(KioskSecurity.getLowBatteryThreshold(context)) }
    var highBatteryThresh by remember { mutableIntStateOf(KioskSecurity.getHighBatteryThreshold(context)) }

    // ==========================================
    // 1. AUTO-CLEAR CACHE ON SLEEP (COLLAPSIBLE)
    // ==========================================
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B).copy(alpha = 0.6f)),
        border = BorderStroke(1.dp, if (isAutoClearExpanded) Color(0xFF6366F1).copy(alpha = 0.5f) else Color(0xFF334155).copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Clickable Title Header (collapsed by default, click to expand/collapse)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { isAutoClearExpanded = !isAutoClearExpanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF6366F1).copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.CleaningServices,
                            contentDescription = null,
                            tint = Color(0xFF818CF8),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Auto-Clear Cache On Sleep",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            HelpInfoButton(
                                title = "Auto-Clear Cache On Sleep",
                                description = "Automatically purges temporary app cache, browser cookies, and saved player session data when the device screen is turned off for the configured idle period.",
                                onShowHelp = onShowHelp
                            )
                        }
                        Text(
                            text = if (autoClearEnabled) "Active · Clears after ${sleepTimeoutMins}m sleep" else "Disabled · Tap to configure",
                            color = if (autoClearEnabled) Color(0xFF34D399) else Color(0xFF94A3B8),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Icon(
                    imageVector = if (isAutoClearExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (isAutoClearExpanded) "Collapse" else "Expand",
                    tint = Color(0xFF94A3B8),
                    modifier = Modifier.size(20.dp)
                )
            }

            // Expandable Content
            AnimatedVisibility(
                visible = isAutoClearExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                ) {
                    HorizontalDivider(color = Color(0xFF334155).copy(alpha = 0.7f), modifier = Modifier.padding(bottom = 10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Enable Auto-Clear On Sleep",
                            color = Color(0xFFE2E8F0),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
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
                            ),
                            modifier = Modifier.height(28.dp)
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
                                onShowHelp = onShowHelp
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
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(10.dp))

    // ==========================================
    // 2. BATTERY HEALTH & TTS REMINDERS (COLLAPSIBLE)
    // ==========================================
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B).copy(alpha = 0.6f)),
        border = BorderStroke(1.dp, if (isBatteryExpanded) Color(0xFF10B981).copy(alpha = 0.5f) else Color(0xFF334155).copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Clickable Title Header (collapsed by default, click to expand/collapse)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { isBatteryExpanded = !isBatteryExpanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF10B981).copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.BatteryChargingFull,
                            contentDescription = null,
                            tint = Color(0xFF34D399),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Battery Health & TTS Reminders",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            HelpInfoButton(
                                title = "Battery Health & TTS Reminders",
                                description = "Monitors device battery status. Uses Text-to-Speech (TTS) voice announcements and alert tones to remind staff when the battery is low (plug charger) or full (disconnect charger) to prevent battery swelling.",
                                onShowHelp = onShowHelp
                            )
                        }
                        Text(
                            text = if (batteryAlertsEnabled) "Active · Protection range $lowBatteryThresh% - $highBatteryThresh%" else "Disabled · Tap to configure",
                            color = if (batteryAlertsEnabled) Color(0xFF34D399) else Color(0xFF94A3B8),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Icon(
                    imageVector = if (isBatteryExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (isBatteryExpanded) "Collapse" else "Expand",
                    tint = Color(0xFF94A3B8),
                    modifier = Modifier.size(20.dp)
                )
            }

            // Expandable Content
            AnimatedVisibility(
                visible = isBatteryExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                ) {
                    HorizontalDivider(color = Color(0xFF334155).copy(alpha = 0.7f), modifier = Modifier.padding(bottom = 10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Enable Battery Voice Alerts",
                            color = Color(0xFFE2E8F0),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
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
                            ),
                            modifier = Modifier.height(28.dp)
                        )
                    }

                    if (batteryAlertsEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Battery Protection Range:", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.width(4.dp))
                            HelpInfoButton(
                                title = "Battery Protection Range",
                                description = "Triggers voice reminders to plug in when battery hits the lower threshold, and to unplug when it reaches the upper threshold. Protects lithium battery lifespan.",
                                onShowHelp = onShowHelp
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))

                        var sliderPosition by remember { mutableStateOf(lowBatteryThresh.toFloat()..highBatteryThresh.toFloat()) }

                        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                            RangeSlider(
                                value = sliderPosition,
                                onValueChange = { range ->
                                    sliderPosition = range
                                },
                                onValueChangeFinished = {
                                    val newLow = sliderPosition.start.toInt()
                                    val newHigh = sliderPosition.endInclusive.toInt()
                                    lowBatteryThresh = newLow
                                    highBatteryThresh = newHigh
                                    KioskSecurity.setLowBatteryThreshold(context, newLow)
                                    KioskSecurity.setHighBatteryThreshold(context, newHigh)
                                },
                                valueRange = 0f..100f,
                                steps = 99,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFF6366F1),
                                    activeTrackColor = Color(0xFF6366F1),
                                    inactiveTrackColor = Color(0xFF334155)
                                )
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Lower: ${sliderPosition.start.toInt()}% (Plug In)", color = Color(0xFFEF4444), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text("Upper: ${sliderPosition.endInclusive.toInt()}% (Unplug)", color = Color(0xFFF59E0B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = {
                                KioskService.triggerTestTts(context, "Piso Phone voice system online and functional.")
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
                }
            }
        }
    }
}
