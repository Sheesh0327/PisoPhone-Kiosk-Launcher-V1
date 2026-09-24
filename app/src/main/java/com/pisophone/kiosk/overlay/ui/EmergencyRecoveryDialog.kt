package com.pisophone.kiosk.overlay.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.security.KioskPolicyManager
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.util.AppLauncher

@Composable
fun EmergencyRecoveryDialog(
    context: Context = LocalContext.current,
    onClose: () -> Unit
) {
    var isDeviceOwner by remember { mutableStateOf(KioskPolicyManager.isDeviceOwner(context)) }
    var isAdbEnabled by remember { mutableStateOf(KioskSecurity.isUsbDebuggingEnabled(context)) }
    var showConfirmDeprovision by remember { mutableStateOf(false) }

    val refreshStatus = {
        isDeviceOwner = KioskPolicyManager.isDeviceOwner(context)
        isAdbEnabled = KioskSecurity.isUsbDebuggingEnabled(context)
    }

    LaunchedEffect(Unit) {
        refreshStatus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f))
            .clickable { onClose() }
            .padding(horizontal = 8.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.96f)
                .clickable(enabled = false) {},
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            border = BorderStroke(1.dp, Color(0xFF334155))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                // Responsive Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(Color(0xFF334155), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = "Recovery Hub",
                                tint = Color(0xFFF87171),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "SYSTEM RECOVERY HUB",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Emergency Maintenance & ADB",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
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

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Color(0xFF334155))
                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    EmergencyDiagnosticsMatrix(
                        isAdbEnabled = isAdbEnabled,
                        isDeviceOwner = isDeviceOwner
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "EMERGENCY ACTIONS",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // Action 1: Force Re-Enable USB Debugging
                    RecoveryActionButton(
                        icon = Icons.Filled.Usb,
                        iconTint = Color(0xFF38BDF8),
                        title = "Force Re-Enable USB Debugging",
                        subtitle = "Overrides system policy & activates ADB bridge",
                        containerColor = Color(0xFF0284C7),
                        contentColor = Color.White,
                        onClick = {
                            KioskSecurity.emergencyEnableUsbDebugging(context)
                            refreshStatus()
                            Toast.makeText(context, "USB Debugging Re-Enabled", Toast.LENGTH_SHORT).show()
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Action 2: Exit Kiosk Lockdown & Open Settings
                    RecoveryActionButton(
                        icon = Icons.Filled.LockOpen,
                        iconTint = Color(0xFFFBBF24),
                        title = "Exit Kiosk Lockdown",
                        subtitle = "Disables overlay & opens Android System Settings",
                        containerColor = Color(0xFF334155),
                        contentColor = Color.White,
                        onClick = {
                            KioskSecurity.emergencyExitKiosk(context)
                            onClose()
                            Toast.makeText(context, "Exited Kiosk Lockdown", Toast.LENGTH_SHORT).show()
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Action 3: Open Developer Options
                    RecoveryActionButton(
                        icon = Icons.Filled.DeveloperMode,
                        iconTint = Color(0xFF38BDF8),
                        title = "Open Developer Options",
                        subtitle = "Launch system developer preferences directly",
                        containerColor = Color(0xFF1E293B),
                        contentColor = Color(0xFFE2E8F0),
                        borderColor = Color(0xFF334155),
                        onClick = {
                            AppLauncher.launchDeveloperSettings(context)
                        }
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Action 4: De-provision Device Owner
                    if (!showConfirmDeprovision) {
                        RecoveryActionButton(
                            icon = Icons.Filled.DeleteForever,
                            iconTint = Color(0xFFF87171),
                            title = "De-provision Device Owner",
                            subtitle = "Completely remove kiosk administrator for uninstallation",
                            containerColor = Color(0xFF450A0A).copy(alpha = 0.5f),
                            contentColor = Color(0xFFFCA5A5),
                            borderColor = Color(0xFFEF4444).copy(alpha = 0.4f),
                            onClick = { showConfirmDeprovision = true }
                        )
                    } else {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF450A0A)),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Color(0xFFEF4444))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFF87171), modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "CONFIRM DE-PROVISION",
                                        color = Color(0xFFF87171),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "This completely removes Device Owner lockdown so you can uninstall the app without factory resetting. Proceed?",
                                    color = Color(0xFFE2E8F0),
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(onClick = { showConfirmDeprovision = false }) {
                                        Text("Cancel", color = Color(0xFF94A3B8), fontSize = 12.sp)
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
                                        Text("Yes, De-provision", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Developer Remote Emergency Reference Guide
                    EmergencyRemoteAdbCard()
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = Color(0xFF334155))
                Spacer(modifier = Modifier.height(10.dp))

                // Bottom Exit Button
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = onClose,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1), contentColor = Color.White),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        Text("Exit Recovery Hub", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecoveryActionButton(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    containerColor: Color,
    contentColor: Color,
    borderColor: Color? = null,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        border = borderColor?.let { BorderStroke(1.dp, it) },
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(Color.Black.copy(alpha = 0.25f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = iconTint)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = contentColor,
                    lineHeight = 16.sp
                )
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    color = contentColor.copy(alpha = 0.75f),
                    lineHeight = 14.sp
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = contentColor.copy(alpha = 0.5f)
            )
        }
    }
}
