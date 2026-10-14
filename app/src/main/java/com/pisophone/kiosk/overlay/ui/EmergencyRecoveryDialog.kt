package com.pisophone.kiosk.overlay.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.R
import com.pisophone.kiosk.security.KioskPolicyManager
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.ui.theme.TechNet
import com.pisophone.kiosk.util.AppLauncher

@Composable
fun EmergencyRecoveryDialog(
    context: Context = LocalContext.current,
    onClose: () -> Unit,
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
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable { onClose() }
            .padding(horizontal = 8.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.96f)
                .clickable(enabled = false) {},
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = TechNet.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            border = BorderStroke(1.dp, TechNet.line),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                // Responsive Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(TechNet.line, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                tint = TechNet.dangerText,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.ui_system_recovery),
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = stringResource(R.string.ui_emergency_tools_for_the_owner),
                                color = TechNet.textMuted,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.ui_close), tint = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = TechNet.line)
                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // Diagnostic Status Matrix with fluid layout
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = TechNet.surfaceHi),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, TechNet.surface),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = stringResource(R.string.ui_status),
                                color = TechNet.brandFg,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            // Diagnostic Row 1: ADB Status
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(TechNet.surface.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text("USB debugging (ADB)", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (isAdbEnabled) "The USB bridge is on" else "The policy keeps USB debugging off",
                                        color = TechNet.textMuted,
                                        fontSize = 12.sp,
                                    )
                                }
                                Surface(
                                    color = if (isAdbEnabled) TechNet.okSurface else TechNet.dangerSurface,
                                    shape = RoundedCornerShape(6.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .background(if (isAdbEnabled) TechNet.ok else TechNet.dangerText, CircleShape),
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isAdbEnabled) "Active" else "Restricted",
                                            color = if (isAdbEnabled) TechNet.ok else TechNet.dangerText,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Diagnostic Row 2: Device Owner Status
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(TechNet.surface.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text("Device owner", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (isDeviceOwner) "Kiosk lock is on" else "A normal phone, not locked as a kiosk",
                                        color = TechNet.textMuted,
                                        fontSize = 12.sp,
                                    )
                                }
                                Surface(
                                    color = if (isDeviceOwner) TechNet.okSurface else TechNet.warnSurface,
                                    shape = RoundedCornerShape(6.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .background(if (isDeviceOwner) TechNet.ok else TechNet.gold, CircleShape),
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isDeviceOwner) "Set up" else "Not set",
                                            color = if (isDeviceOwner) TechNet.ok else TechNet.gold,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = stringResource(R.string.ui_emergency_actions),
                        color = TechNet.textMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // Action 1: Force Re-Enable USB Debugging
                    RecoveryActionButton(
                        icon = Icons.Filled.Usb,
                        iconTint = TechNet.brandFg,
                        title = "Turn USB debugging on",
                        subtitle = "Overrides the policy and starts the ADB bridge",
                        containerColor = TechNet.brand,
                        contentColor = Color.White,
                        onClick = {
                            KioskSecurity.emergencyEnableUsbDebugging(context)
                            refreshStatus()
                            Toast.makeText(context, "USB debugging is on", Toast.LENGTH_SHORT).show()
                        },
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Action 2: Exit Kiosk Lockdown & Open Settings
                    RecoveryActionButton(
                        icon = Icons.Filled.LockOpen,
                        iconTint = TechNet.gold,
                        title = "Leave kiosk mode",
                        subtitle = "Turns the lock screen off and opens Android settings",
                        containerColor = TechNet.line,
                        contentColor = Color.White,
                        onClick = {
                            KioskSecurity.emergencyExitKiosk(context)
                            onClose()
                            Toast.makeText(context, "Left kiosk mode", Toast.LENGTH_SHORT).show()
                        },
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Action 3: Open Developer Options
                    RecoveryActionButton(
                        icon = Icons.Filled.DeveloperMode,
                        iconTint = TechNet.brandFg,
                        title = "Open developer options",
                        subtitle = "Opens the system developer settings",
                        containerColor = TechNet.surface,
                        contentColor = TechNet.text,
                        borderColor = TechNet.line,
                        onClick = {
                            AppLauncher.launchDeveloperSettings(context)
                        },
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Action 4: De-provision Device Owner
                    if (!showConfirmDeprovision) {
                        RecoveryActionButton(
                            icon = Icons.Filled.DeleteForever,
                            iconTint = TechNet.dangerText,
                            title = "Remove device owner",
                            subtitle = "Lets you uninstall the app without a factory reset",
                            containerColor = TechNet.dangerSurface.copy(alpha = 0.5f),
                            contentColor = TechNet.dangerText,
                            borderColor = TechNet.danger.copy(alpha = 0.4f),
                            onClick = { showConfirmDeprovision = true },
                        )
                    } else {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = TechNet.dangerSurface),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, TechNet.danger),
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Warning, contentDescription = null, tint = TechNet.dangerText, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.ui_remove_device_owner),
                                        color = TechNet.dangerText,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = stringResource(R.string.ui_this_removes_the_device_owner_lock_c),
                                    color = TechNet.text,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TextButton(onClick = { showConfirmDeprovision = false }) {
                                        Text("Cancel", color = TechNet.textMuted, fontSize = 12.sp)
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(
                                        onClick = {
                                            KioskSecurity.emergencyClearDeviceOwner(context)
                                            refreshStatus()
                                            showConfirmDeprovision = false
                                            onClose()
                                            Toast.makeText(context, "Device owner removed", Toast.LENGTH_LONG).show()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = TechNet.dangerEdge, contentColor = Color.White),
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        Text("Remove device owner", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Developer Remote Emergency Reference Guide
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = TechNet.surfaceHi),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, TechNet.surface),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Terminal, contentDescription = null, tint = TechNet.brandFg, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Recovery from a computer", color = TechNet.brandFg, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                            Spacer(modifier = Modifier.height(10.dp))

                            Text("Turn ADB on from a computer:", color = TechNet.textMuted, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(TechNet.bg, RoundedCornerShape(6.dp))
                                    .border(1.dp, TechNet.surface, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                SelectionContainer {
                                    Text(
                                        text = "adb shell am broadcast -a com.pisophone.kiosk.ENABLE_ADB --es pin <admin password>",
                                        color = TechNet.brandFg,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 15.sp,
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text("Remove the device owner from a computer:", color = TechNet.textMuted, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(TechNet.bg, RoundedCornerShape(6.dp))
                                    .border(1.dp, TechNet.surface, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                SelectionContainer {
                                    Text(
                                        text = "adb shell am broadcast -a com.pisophone.kiosk.DEPROVISION --es pin <admin password>",
                                        color = TechNet.dangerText,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 15.sp,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = TechNet.line)
                Spacer(modifier = Modifier.height(10.dp))

                // Bottom Exit Button
                PisoButton(text = stringResource(R.string.ui_close_system_recovery), onClick = onClose, style = PisoButtonStyle.Primary, size = PisoButtonSize.Medium)
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
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        border = borderColor?.let { BorderStroke(1.dp, it) },
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(Color.Black.copy(alpha = 0.25f), CircleShape),
                contentAlignment = Alignment.Center,
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
                    lineHeight = 16.sp,
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = contentColor.copy(alpha = 0.75f),
                    lineHeight = 14.sp,
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = contentColor.copy(alpha = 0.5f),
            )
        }
    }
}
