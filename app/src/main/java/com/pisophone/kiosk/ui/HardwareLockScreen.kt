package com.pisophone.kiosk.ui

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
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pisophone.kiosk.security.HardwareLockManager

@Composable
fun HardwareLockScreen(
    onRebindSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var showAdminDialog by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val currentHwId = remember { HardwareLockManager.getHardwareFingerprint(context) }
    val currentDevName = remember { HardwareLockManager.getHardwareDescription() }
    val boundHwId = remember { HardwareLockManager.getBoundHardwareId(context) }
    val boundDevName = remember { HardwareLockManager.getBoundDeviceName(context) }
    val securityUpdateVer by HardwareLockManager.securityUpdateVersion.collectAsState()
    val isSlotLocked = remember(securityUpdateVer) { HardwareLockManager.isSlotLockedDown(context) }
    val (slotReason, slotNum, _) = remember(securityUpdateVer, isSlotLocked) {
        HardwareLockManager.getSlotLockdownDetails(context)
    }

    val headerTitle = when {
        isSlotLocked -> "SLOT LICENSE EXPIRED"
        boundHwId.isEmpty() -> "UNPROVISIONED TERMINAL"
        else -> "HARDWARE TAMPER LOCK"
    }

    val headerSubtitle = when {
        isSlotLocked -> "HARD LOCKDOWN ACTIVE"
        boundHwId.isEmpty() -> "Activation & ESP32 Pairing Required"
        else -> "Unauthorized Device or Cloned Storage Detected"
    }

    val headerDescription = when {
        isSlotLocked -> "This terminal's slot license has expired in the ESP32 Master controller's memory. The app will not accept coins and will remain locked until a new slot is purchased on the ESP32."
        boundHwId.isEmpty() -> "This kiosk terminal has not been provisioned. Connect this device to an authorized PisoPhone ESP32 Box via WebADB to pair a licensed terminal seat."
        else -> "This kiosk application is cryptographically sealed to its original physical hardware to prevent unauthorized copying, disk cloning, or firmware extraction."
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF060B14),
                        Color(0xFF0B1120),
                        Color(0xFF0F172A)
                    )
                )
            )
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Lock Icon Badge
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .background(Color(0x22EF4444), CircleShape)
                    .border(1.5.dp, Color(0xFFEF4444), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = "Hardware Tamper Lock",
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(44.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = headerTitle,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                color = Color(0xFFF87171),
                letterSpacing = 1.5.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = headerSubtitle,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = headerDescription,
                fontSize = 13.sp,
                color = Color(0xFF94A3B8),
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Diagnostic Hardware Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                isSlotLocked -> "ESP32 Slot Memory Expired"
                                boundHwId.isEmpty() -> "Awaiting WebADB / ESP32 Provisioning"
                                else -> "Hardware Signature Mismatch"
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFEF4444)
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = Color(0xFF1E293B))

                    if (isSlotLocked) {
                        Text("ESP32 CONTROLLER LOCKDOWN STATUS:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                        Text(
                            if (slotNum > 0) "Assigned Slot: #$slotNum" else "Unassigned / Expired Slot",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Reason: $slotReason", fontSize = 12.sp, color = Color(0xFFF87171))
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "🔄 Automatic Reconnect Active: The app keeps heartbeating to the ESP32 and will auto-unlock once a new slot license is purchased on the controller.",
                            fontSize = 11.sp,
                            color = Color(0xFF38BDF8),
                            lineHeight = 16.sp
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = Color(0xFF1E293B))
                    }

                    // Current Device
                    Text("CURRENT HARDWARE IDENTITY:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                    Text(currentDevName, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    
                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            currentHwId,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF10B981),
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(currentHwId))
                                Toast.makeText(context, "Hardware ID copied to clipboard!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = "Copy Hardware ID",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    if (boundHwId.isNotEmpty() && boundHwId != currentHwId) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("PREVIOUSLY BOUND SEAL:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                        Text(boundDevName, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF94A3B8))
                        Text(
                            boundHwId,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFEF4444),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Admin Authorization Button
            Button(
                onClick = {
                    pinInput = ""
                    errorMessage = null
                    showAdminDialog = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("admin_rebind_button"),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6), contentColor = Color.White),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.AdminPanelSettings, contentDescription = null, modifier = Modifier.size(20.dp), tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Unlock with Admin PIN",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }

    // Admin PIN Dialog
    if (showAdminDialog) {
        Dialog(
            onDismissRequest = { showAdminDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .padding(16.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Filled.AdminPanelSettings,
                        contentDescription = null,
                        tint = Color(0xFF3B82F6),
                        modifier = Modifier.size(36.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Authorized Hardware Re-Seal",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Enter the Kiosk Admin PIN to cryptographically re-bind this application to the current device hardware.",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = {
                            if (it.length <= 12) pinInput = it
                            errorMessage = null
                        },
                        label = { Text("Admin PIN") },
                        placeholder = { Text("Enter Admin PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = errorMessage != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("admin_rebind_pin_field"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF3B82F6),
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = errorMessage ?: "",
                            color = Color(0xFFEF4444),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showAdminDialog = false },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8))
                        ) {
                            Text("Cancel")
                        }

                        Button(
                            onClick = {
                                val success = HardwareLockManager.rebindWithAdminPin(context, pinInput.trim())
                                if (success) {
                                    showAdminDialog = false
                                    Toast.makeText(context, "✅ Hardware seal established successfully!", Toast.LENGTH_LONG).show()
                                    onRebindSuccess()
                                } else {
                                    errorMessage = "Invalid Admin PIN. Authorization rejected."
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .testTag("admin_confirm_rebind_btn"),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
                        ) {
                            Text("Re-Seal Hardware", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
