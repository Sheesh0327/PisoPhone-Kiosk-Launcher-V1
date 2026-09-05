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

@Composable
fun SecurityVaultView(
    context: Context,
    onClose: () -> Unit
) {
    var activeHelpDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showRecoveryHub by remember { mutableStateOf(false) }
    var hiddenSet by remember { mutableStateOf(KioskSecurity.getHiddenApps(context)) }

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
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                onOpenRecoveryHub = { showRecoveryHub = true }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = Color(0xFF334155))

            // Section 2: Auto-Clear Cache & Battery TTS Voice Reminders
            VaultSleepAndBatterySection(
                context = context,
                onShowHelp = { t, d -> activeHelpDialog = Pair(t, d) }
            )

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
