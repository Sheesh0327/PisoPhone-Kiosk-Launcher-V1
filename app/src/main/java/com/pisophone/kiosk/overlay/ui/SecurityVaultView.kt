package com.pisophone.kiosk.overlay.ui

import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.pisophone.kiosk.AppInfo
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
