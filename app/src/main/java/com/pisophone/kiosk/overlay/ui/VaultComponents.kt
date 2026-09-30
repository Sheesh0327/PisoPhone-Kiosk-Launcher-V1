package com.pisophone.kiosk.overlay.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
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
                description = "Temporarily bypasses the kiosk lock screen overlay. Grants 5 minutes of unlocked maintenance time.",
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
                KioskService.triggerAdminBypass(context, 300)
                onClose()
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981), contentColor = Color.White),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f).height(38.dp),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
        ) {
            Icon(Icons.Filled.LockOpen, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("5m Direct Bypass", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }

        Button(
            onClick = {
                KioskService.triggerLockSession(context)
                onClose()
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626), contentColor = Color.White),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f).height(38.dp),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Lock Terminal", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun VaultEsp32HardwareSection(
    context: Context,
    onShowHelp: (String, String) -> Unit
) {
    var configuredIp by remember { mutableStateOf(KioskSecurity.getConfiguredEsp32Ip(context)) }
    var configuredMac by remember { mutableStateOf(KioskSecurity.getConfiguredEsp32Mac(context)) }
    var configuredSecret by remember { mutableStateOf(KioskSecurity.getSharedSecret(context)) }
    var secretVisible by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B).copy(alpha = 0.6f)),
        border = BorderStroke(1.dp, Color(0xFF334155).copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(Color(0xFF6366F1).copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Router,
                            contentDescription = null,
                            tint = Color(0xFF818CF8),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text("ESP32 Master Box Configuration", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("Manual IP, MAC & Secret Key settings", color = Color(0xFF94A3B8), fontSize = 10.sp)
                    }
                }
                HelpInfoButton(
                    title = "ESP32 Hardware Configuration",
                    description = "Configure the ESP32 Master Cabinet IP address, MAC address, and Box Secret Key for hardware communication and arming authorization.",
                    onShowHelp = onShowHelp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = configuredIp,
                onValueChange = { configuredIp = it },
                label = { Text("ESP32 IP Address", fontSize = 11.sp, color = Color(0xFF94A3B8)) },
                placeholder = { Text("e.g. 192.168.1.100", fontSize = 11.sp, color = Color.Gray) },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF6366F1),
                    unfocusedBorderColor = Color(0xFF334155),
                    focusedContainerColor = Color(0xFF0F172A),
                    unfocusedContainerColor = Color(0xFF0F172A)
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = configuredMac,
                onValueChange = { configuredMac = it },
                label = { Text("ESP32 MAC Address", fontSize = 11.sp, color = Color(0xFF94A3B8)) },
                placeholder = { Text("e.g. AA:BB:CC:DD:EE:FF", fontSize = 11.sp, color = Color.Gray) },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF6366F1),
                    unfocusedBorderColor = Color(0xFF334155),
                    focusedContainerColor = Color(0xFF0F172A),
                    unfocusedContainerColor = Color(0xFF0F172A)
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = configuredSecret,
                onValueChange = { configuredSecret = it },
                label = { Text("Box Secret Key (256-bit Hex)", fontSize = 11.sp, color = Color(0xFF94A3B8)) },
                placeholder = { Text("Paste secret key from ESP32 portal", fontSize = 11.sp, color = Color.Gray) },
                singleLine = true,
                visualTransformation = if (secretVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { secretVisible = !secretVisible }) {
                        Icon(
                            if (secretVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (secretVisible) "Hide secret" else "Show secret",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                },
                textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF6366F1),
                    unfocusedBorderColor = Color(0xFF334155),
                    focusedContainerColor = Color(0xFF0F172A),
                    unfocusedContainerColor = Color(0xFF0F172A)
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = {
                    val cleanMac = KioskSecurity.formatMacAddress(configuredMac)
                    val cleanIp = configuredIp.trim()
                    val cleanSecret = configuredSecret.trim()
                    KioskSecurity.setConfiguredEsp32Ip(context, cleanIp)
                    KioskSecurity.setConfiguredEsp32Mac(context, cleanMac)
                    if (cleanSecret.isNotBlank()) {
                        KioskSecurity.setSharedSecret(context, cleanSecret)
                    }
                    KioskService.configureMasterBox(
                        context,
                        mac = cleanMac,
                        ip = cleanIp,
                        secret = cleanSecret.ifBlank { null }
                    )
                    Toast.makeText(context, "Hardware Box settings saved!", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().height(36.dp)
            ) {
                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Save Hardware Settings", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

