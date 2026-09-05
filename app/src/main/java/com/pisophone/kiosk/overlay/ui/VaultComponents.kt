package com.pisophone.kiosk.overlay.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.model.AppInfo
import com.pisophone.kiosk.KioskService
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.util.AppLauncher

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
fun VaultBypassSection(
    context: Context,
    onClose: () -> Unit,
    onShowHelp: (String, String) -> Unit,
    onOpenRecoveryHub: () -> Unit
) {
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
                onShowHelp = onShowHelp
            )
        }
    }
    Spacer(modifier = Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
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

    Spacer(modifier = Modifier.height(8.dp))

    OutlinedButton(
        onClick = onOpenRecoveryHub,
        modifier = Modifier.fillMaxWidth().height(38.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252)),
        border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.6f)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFFFF5252))
        Spacer(modifier = Modifier.width(8.dp))
        Text("SYSTEM RECOVERY HUB & USB DEBUGGING (ADB)", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
fun VaultSleepAndBatterySection(
    context: Context,
    onShowHelp: (String, String) -> Unit
) {
    var autoClearEnabled by remember { mutableStateOf(KioskSecurity.isAutoClearOnSleepEnabled(context)) }
    var sleepTimeoutMins by remember { mutableIntStateOf(KioskSecurity.getSleepClearTimeoutMinutes(context)) }

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
                onShowHelp = onShowHelp
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

    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color(0xFF334155))

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
                onShowHelp = onShowHelp
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Low Battery Warning Threshold:", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.width(4.dp))
            HelpInfoButton(
                title = "Low Battery Warning Threshold",
                description = "Triggers voice reminders and alert tones when battery falls to or below this percentage.",
                onShowHelp = onShowHelp
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

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Full Battery Warning Threshold:", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.width(4.dp))
            HelpInfoButton(
                title = "Full Battery Warning Threshold",
                description = "Triggers voice reminders to unplug the charger when battery reaches this percentage to preserve lithium battery lifespan.",
                onShowHelp = onShowHelp
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
