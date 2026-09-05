package com.pisophone.kiosk.overlay.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
            .background(Color.Black.copy(alpha = 0.85f))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
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
                                .size(40.dp)
                                .background(Color(0xFF334155), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = "Recovery",
                                tint = Color(0xFFF87171),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "SYSTEM RECOVERY HUB",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Fail-Safe Maintenance & ADB Controller",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp
                            )
                        }
                    }

                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider(color = Color(0xFF334155))
                Spacer(modifier = Modifier.height(20.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Diagnostic Status Matrix
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "SYSTEM STATUS DIAGNOSTICS",
                                color = Color(0xFF38BDF8),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("USB Debugging (ADB):", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(if (isAdbEnabled) Color(0xFF10B981) else Color(0xFFEF4444), CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        if (isAdbEnabled) "ACTIVE / ENABLED" else "RESTRICTED / DISABLED",
                                        color = if (isAdbEnabled) Color(0xFF10B981) else Color(0xFFEF4444),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Device Owner Status:", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(if (isDeviceOwner) Color(0xFF10B981) else Color(0xFFF59E0B), CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        if (isDeviceOwner) "PROVISIONED" else "UNPROVISIONED",
                                        color = if (isDeviceOwner) Color(0xFF10B981) else Color(0xFFF59E0B),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "EMERGENCY ACTIONS",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Action 1: Force Re-Enable USB Debugging (ADB)
                    Button(
                        onClick = {
                            KioskSecurity.emergencyEnableUsbDebugging(context)
                            refreshStatus()
                            Toast.makeText(context, "USB Debugging Re-Enabled", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF38BDF8),
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Filled.Usb, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("FORCE RE-ENABLE USB DEBUGGING (ADB)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Action 2: Emergency Exit Kiosk Lockdown & Open System Settings
                    Button(
                        onClick = {
                            KioskSecurity.emergencyExitKiosk(context)
                            onClose()
                            Toast.makeText(context, "Exited Kiosk Lockdown", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF334155),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Filled.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFFFBBF24))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("EXIT KIOSK LOCKDOWN & OPEN SETTINGS", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                .weight(1f)
                                .height(46.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCBD5E1)),
                            border = BorderStroke(1.dp, Color(0xFF334155)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Filled.DeveloperMode, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF38BDF8))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("DEV OPTIONS", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }

                        // Action 4: Reboot Device
                        OutlinedButton(
                            onClick = {
                                val success = KioskSecurity.rebootDevice(context)
                                if (!success) {
                                    Toast.makeText(context, "Reboot requires Device Owner status", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCBD5E1)),
                            border = BorderStroke(1.dp, Color(0xFF334155)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF10B981))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("SAFE REBOOT", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Action 5: De-provision Device Owner
                    if (!showConfirmDeprovision) {
                        OutlinedButton(
                            onClick = { showConfirmDeprovision = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                            border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Filled.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFFEF4444))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("DE-PROVISION DEVICE OWNER", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF450a0a)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "⚠️ CONFIRM DE-PROVISION",
                                    color = Color(0xFFF87171),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "This completely removes Device Owner lockdown so you can uninstall the app without factory resetting. Proceed?",
                                    color = Color(0xFFE2E8F0),
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = { showConfirmDeprovision = false }) {
                                        Text("Cancel", color = Color(0xFF94A3B8), fontSize = 13.sp)
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(
                                        onClick = {
                                            KioskSecurity.emergencyClearDeviceOwner(context)
                                            refreshStatus()
                                            showConfirmDeprovision = false
                                            onClose()
                                            Toast.makeText(context, "✅ Device Owner successfully removed!", Toast.LENGTH_LONG).show()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626), contentColor = Color.White),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Yes, De-provision", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Developer Remote Emergency Reference Guide
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Terminal, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("REMOTE ADB / RECOVERY COMMANDS", color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "• Enable ADB via Broadcast:\nadb shell am broadcast -a com.pisophone.kiosk.ENABLE_ADB --es pin 1234\n\n• Deprovision via ADB:\nadb shell am broadcast -a com.pisophone.kiosk.DEPROVISION --es pin 1234",
                                color = Color(0xFF94A3B8),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider(color = Color(0xFF334155))
                Spacer(modifier = Modifier.height(16.dp))
                
                // Explicit Exit Button
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = onClose,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1), contentColor = Color.White),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(44.dp)
                    ) {
                        Text("Exit Recovery Hub", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
