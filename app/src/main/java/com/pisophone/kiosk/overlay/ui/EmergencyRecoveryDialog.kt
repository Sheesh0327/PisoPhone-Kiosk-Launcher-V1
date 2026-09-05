package com.pisophone.kiosk.overlay.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.security.KioskSecurity

@Composable
fun EmergencyRecoveryDialog(
    context: Context = LocalContext.current,
    onClose: () -> Unit
) {
    val dpm = remember { context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager }
    var isDeviceOwner by remember { mutableStateOf(dpm.isDeviceOwnerApp(context.packageName)) }
    var isAdbEnabled by remember { mutableStateOf(KioskSecurity.isUsbDebuggingEnabled(context)) }
    var showConfirmDeprovision by remember { mutableStateOf(false) }

    val refreshStatus = {
        isDeviceOwner = dpm.isDeviceOwnerApp(context.packageName)
        isAdbEnabled = KioskSecurity.isUsbDebuggingEnabled(context)
    }

    LaunchedEffect(Unit) {
        refreshStatus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF030712).copy(alpha = 0.98f))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f)
                .border(1.5.dp, Color(0xFFFF4444).copy(alpha = 0.7f), RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0B132B))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color(0x33FF4444), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = "Recovery",
                                tint = Color(0xFFFF4444),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "SYSTEM RECOVERY HUB",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = "Fail-Safe Maintenance & ADB Controller",
                                color = Color(0xFFA6ADC8),
                                fontSize = 11.sp
                            )
                        }
                    }

                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color(0xFFA6ADC8))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Diagnostic Status Matrix
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131E3A)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "SYSTEM STATUS DIAGNOSTICS",
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("USB Debugging (ADB):", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(if (isAdbEnabled) Color(0xFF00FF88) else Color(0xFFFF4444), CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    if (isAdbEnabled) "ACTIVE / ENABLED" else "RESTRICTED / DISABLED",
                                    color = if (isAdbEnabled) Color(0xFF00FF88) else Color(0xFFFF4444),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Device Owner Status:", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(if (isDeviceOwner) Color(0xFF00FF88) else Color(0xFFF59E0B), CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    if (isDeviceOwner) "DEVICE OWNER PROVISIONED" else "UNPROVISIONED",
                                    color = if (isDeviceOwner) Color(0xFF00FF88) else Color(0xFFF59E0B),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "EMERGENCY ACTIONS",
                    color = Color(0xFFA6ADC8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Action 1: Force Re-Enable USB Debugging (ADB)
                Button(
                    onClick = {
                        KioskSecurity.emergencyEnableUsbDebugging(context)
                        refreshStatus()
                        Toast.makeText(context, "⚡ Emergency: USB Debugging Re-Enabled & Developer Options opened", Toast.LENGTH_LONG).show()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00E5FF),
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Filled.Usb, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("FORCE RE-ENABLE USB DEBUGGING (ADB)", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Action 2: Emergency Exit Kiosk Lockdown & Open System Settings
                Button(
                    onClick = {
                        KioskSecurity.emergencyExitKiosk(context)
                        onClose()
                        Toast.makeText(context, "🔓 Exited Kiosk Lockdown. Opening System Settings...", Toast.LENGTH_LONG).show()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1E293B),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Filled.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFFF59E0B))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("EXIT KIOSK LOCKDOWN & OPEN SETTINGS", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Action 3: Open Developer Options Directly
                OutlinedButton(
                    onClick = {
                        try {
                            val devIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(devIntent)
                        } catch (e: Exception) {
                            try {
                                val sIntent = Intent(Settings.ACTION_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(sIntent)
                            } catch (_: Exception) {}
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCBD5E1)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Filled.DeveloperMode, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF38BDF8))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("OPEN DEVELOPER OPTIONS", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Action 4: Reboot Device
                OutlinedButton(
                    onClick = {
                        val success = KioskSecurity.rebootDevice(context)
                        if (!success) {
                            Toast.makeText(context, "Reboot requires Device Owner status", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCBD5E1)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF00FF88))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("SAFE REBOOT DEVICE", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Action 5: De-provision Device Owner
                if (!showConfirmDeprovision) {
                    OutlinedButton(
                        onClick = { showConfirmDeprovision = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF6B6B)),
                        border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFFFF6B6B).copy(alpha = 0.5f))),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Filled.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFFFF6B6B))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("DE-PROVISION DEVICE OWNER (UNINSTALL MODE)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2C111C)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "⚠️ CONFIRM DE-PROVISION",
                                color = Color(0xFFFF4444),
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "This completely removes Device Owner lockdown so you can uninstall the app without factory resetting. Proceed?",
                                color = Color(0xFFE2E8F0),
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { showConfirmDeprovision = false }) {
                                    Text("Cancel", color = Color(0xFFA6ADC8), fontSize = 11.sp)
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Button(
                                    onClick = {
                                        KioskSecurity.emergencyClearDeviceOwner(context)
                                        refreshStatus()
                                        showConfirmDeprovision = false
                                        onClose()
                                        Toast.makeText(context, "✅ Device Owner successfully removed! App can now be uninstalled.", Toast.LENGTH_LONG).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4444), contentColor = Color.White),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Yes, De-provision", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Developer Remote Emergency Reference Guide
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF060B14)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Terminal, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("REMOTE ADB / RECOVERY COMMANDS", color = Color(0xFF38BDF8), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "• Enable ADB via Broadcast:\nadb shell am broadcast -a com.pisophone.kiosk.ENABLE_ADB --es pin 1234\n\n• Deprovision via ADB:\nadb shell am broadcast -a com.pisophone.kiosk.DEPROVISION --es pin 1234",
                            color = Color(0xFFA6ADC8),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }
    }
}
