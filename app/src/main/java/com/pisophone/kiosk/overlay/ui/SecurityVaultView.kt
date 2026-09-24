package com.pisophone.kiosk.overlay.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.pisophone.kiosk.model.AppInfo
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.security.KioskUpdateManager

@Composable
fun SecurityVaultView(
    context: Context,
    onClose: () -> Unit,
    onOpenRecoveryHub: (() -> Unit)? = null
) {
    var activeHelpDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showRecoveryHub by remember { mutableStateOf(false) }
    var hiddenSet by remember { mutableStateOf(KioskSecurity.getHiddenApps(context)) }
    var vaultTitleTapCount by remember { mutableIntStateOf(0) }

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
                bitmap = try { resolveInfo.activityInfo.loadIcon(pm).toBitmap().asImageBitmap() } catch (e: Exception) { null }
            )
        }.sortedBy { it.name }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(8.dp)
        ) {
            // Vault Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    ) {
                        vaultTitleTapCount++
                        if (vaultTitleTapCount >= 7) {
                            vaultTitleTapCount = 0
                            if (onOpenRecoveryHub != null) {
                                onOpenRecoveryHub()
                            } else {
                                showRecoveryHub = true
                            }
                        }
                    }
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = Color(0xFF6366F1), modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Security Vault & Admin Console", fontWeight = FontWeight.ExtraBold, color = Color.White, fontSize = 16.sp)
                }
                IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Section 1: Admin System Bypass & Quick Tools
            VaultBypassSection(
                context = context,
                onClose = onClose,
                onShowHelp = { t, d -> activeHelpDialog = Pair(t, d) },
                onOpenRecoveryHub = {
                    if (onOpenRecoveryHub != null) {
                        onOpenRecoveryHub()
                    } else {
                        showRecoveryHub = true
                    }
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = Color(0xFF334155))

            // Section 1B: ESP32 Master Controller Static IP Configuration
            VaultEsp32SettingsSection(
                context = context,
                onShowHelp = { t, d -> activeHelpDialog = Pair(t, d) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = Color(0xFF334155))

            // Section 2: Auto-Clear Cache & Battery TTS Voice Reminders
            VaultSleepAndBatterySection(
                context = context,
                onShowHelp = { t, d -> activeHelpDialog = Pair(t, d) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = Color(0xFF334155))

            // Section 2B: Direct App Update
            val updateState by KioskUpdateManager.updateState.collectAsState()
            val currentVersionName = remember {
                try {
                    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    "${packageInfo.versionName} (${packageInfo.versionCode})"
                } catch (e: Exception) {
                    "Unknown"
                }
            }

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B).copy(alpha = 0.6f)),
                border = BorderStroke(1.dp, Color(0xFF334155).copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color(0xFF0EA5E9).copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.CloudDownload,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Direct App Update", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("Current: v$currentVersionName", color = Color(0xFF94A3B8), fontSize = 10.sp)
                        }
                        HelpInfoButton(
                            title = "Direct App Update",
                            description = "Directly downloads and installs the latest secure application update APK.",
                            onShowHelp = { t, d -> activeHelpDialog = Pair(t, d) }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Status display
                    when (val state = updateState) {
                        is KioskUpdateManager.UpdateState.Idle -> {
                            Button(
                                onClick = {
                                    KioskUpdateManager.startUpdate(context, "https://pisophone.pages.dev/update/app-release.apk")
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().height(36.dp)
                            ) {
                                Icon(Icons.Filled.SystemUpdateAlt, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Download & Install Update", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        is KioskUpdateManager.UpdateState.Downloading -> {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Downloading update...", color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text("${(state.progress * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { state.progress },
                                    color = Color(0xFF38BDF8),
                                    trackColor = Color(0xFF334155),
                                    modifier = Modifier.fillMaxWidth().height(4.dp)
                                )
                            }
                        }
                        is KioskUpdateManager.UpdateState.Installing -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color(0xFFF59E0B),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Installing APK update package...", color = Color(0xFFF59E0B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        is KioskUpdateManager.UpdateState.Success -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Update initiated successfully!", color = Color(0xFF10B981), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        is KioskUpdateManager.UpdateState.Error -> {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.Error, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Update failed: ${state.message}", color = Color(0xFFEF4444), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Button(
                                    onClick = { KioskUpdateManager.resetState() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth().height(32.dp)
                                ) {
                                    Text("Retry", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = Color(0xFF334155))

            // Section 3: Hidden Apps Vault
            VaultHiddenAppsSection(
                context = context,
                allInstalledApps = allInstalledApps,
                hiddenSet = hiddenSet,
                onHiddenSetChange = { hiddenSet = it },
                onClose = onClose,
                onShowHelp = { t, d -> activeHelpDialog = Pair(t, d) }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Bottom Done Button
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1), contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(44.dp)
                ) {
                    Text("Exit Admin Console", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Crash-safe Modal in-view Dialog for Help Explanations
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

        if (showRecoveryHub) {
            EmergencyRecoveryDialog(
                context = context,
                onClose = { showRecoveryHub = false }
            )
        }
    }
}
