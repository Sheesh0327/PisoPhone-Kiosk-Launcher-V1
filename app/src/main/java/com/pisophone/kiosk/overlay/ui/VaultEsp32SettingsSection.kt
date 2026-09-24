package com.pisophone.kiosk.overlay.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.KioskService
import com.pisophone.kiosk.security.KioskSecurity

@Composable
fun VaultEsp32SettingsSection(
    context: Context,
    onShowHelp: (String, String) -> Unit
) {
    var savedIp by remember { mutableStateOf(KioskSecurity.getConfiguredEsp32Ip(context)) }
    var inputIp by remember { mutableStateOf(savedIp) }
    var savedMac by remember { mutableStateOf(KioskSecurity.getConfiguredEsp32Mac(context)) }
    var inputMac by remember { mutableStateOf(savedMac) }
    var isEditing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isPinging by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B).copy(alpha = 0.6f)),
        border = BorderStroke(1.dp, Color(0xFF334155).copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "ESP32 Master Controller IP & MAC",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Direct IP & Hardware MAC destination",
                        color = Color(0xFF94A3B8),
                        fontSize = 10.sp
                    )
                }
                HelpInfoButton(
                    title = "ESP32 Direct Connection",
                    description = "Configures the IP address and MAC address of the ESP32 coin controller box. Direct pairing requests will be sent directly to this destination without discovery scanning.",
                    onShowHelp = onShowHelp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // IP Input
            OutlinedTextField(
                value = inputIp,
                onValueChange = {
                    inputIp = it
                    errorMessage = null
                    isEditing = true
                },
                label = { Text("ESP32 Static IPv4 Address", fontSize = 11.sp, color = Color(0xFF94A3B8)) },
                placeholder = { Text(KioskSecurity.DEFAULT_ESP32_IP, fontSize = 12.sp, color = Color(0xFF64748B)) },
                isError = errorMessage != null,
                supportingText = errorMessage?.let { { Text(it, color = Color(0xFFEF4444), fontSize = 10.sp) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Next
                ),
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF6366F1),
                    unfocusedBorderColor = Color(0xFF334155),
                    focusedContainerColor = Color(0xFF0F172A),
                    unfocusedContainerColor = Color(0xFF0F172A),
                    cursorColor = Color(0xFF818CF8)
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // MAC Input
            OutlinedTextField(
                value = inputMac,
                onValueChange = {
                    inputMac = it
                    errorMessage = null
                    isEditing = true
                },
                label = { Text("ESP32 MAC Address (Optional / Pinning)", fontSize = 11.sp, color = Color(0xFF94A3B8)) },
                placeholder = { Text("AA:BB:CC:DD:EE:FF", fontSize = 12.sp, color = Color(0xFF64748B)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        keyboardController?.hide()
                    }
                ),
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF6366F1),
                    unfocusedBorderColor = Color(0xFF334155),
                    focusedContainerColor = Color(0xFF0F172A),
                    unfocusedContainerColor = Color(0xFF0F172A),
                    cursorColor = Color(0xFF818CF8)
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Action Buttons Row 1: Save & Pair, Direct Pair
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Save Button
                Button(
                    onClick = {
                        keyboardController?.hide()
                        val trimmedIp = inputIp.trim()
                        val trimmedMac = inputMac.trim()
                        if (KioskSecurity.isValidIpv4(trimmedIp)) {
                            val ipSuccess = KioskService.updateConfiguredEsp32Ip(context, trimmedIp)
                            if (trimmedMac.isNotBlank()) {
                                KioskService.updateConfiguredEsp32Mac(context, trimmedMac)
                            }
                            if (ipSuccess) {
                                savedIp = trimmedIp
                                savedMac = KioskSecurity.getConfiguredEsp32Mac(context)
                                inputIp = savedIp
                                inputMac = savedMac
                                isEditing = false
                                errorMessage = null
                                KioskService.triggerDirectPairing(context, savedIp, savedMac)
                                Toast.makeText(context, "Saved & Pairing request sent to $trimmedIp", Toast.LENGTH_SHORT).show()
                            } else {
                                errorMessage = "Failed to save IP address."
                            }
                        } else {
                            errorMessage = "Invalid IPv4 format (e.g. 192.168.1.10)"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f).height(36.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save & Pair", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                // Direct Pair Button
                OutlinedButton(
                    onClick = {
                        isPinging = true
                        KioskService.triggerDirectPairing(context, savedIp, savedMac)
                        Toast.makeText(context, "Sending direct pairing request to $savedIp...", Toast.LENGTH_SHORT).show()
                        isPinging = false
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8)),
                    border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f).height(36.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Direct Pair", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Action Buttons Row 2: Reset Default IP, Unpair Device
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Reset to Default Button
                OutlinedButton(
                    onClick = {
                        val defaultIp = KioskSecurity.DEFAULT_ESP32_IP
                        inputIp = defaultIp
                        KioskService.updateConfiguredEsp32Ip(context, defaultIp)
                        savedIp = defaultIp
                        isEditing = false
                        errorMessage = null
                        KioskService.triggerDirectPairing(context, defaultIp, savedMac)
                        Toast.makeText(context, "Reset to default: $defaultIp", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8)),
                    border = BorderStroke(1.dp, Color(0xFF475569)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f).height(36.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text("Reset Default IP", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }

                // Unpair / Disconnect Button
                OutlinedButton(
                    onClick = {
                        KioskService.triggerUnpair(context) { success, _ ->
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                savedMac = ""
                                inputMac = ""
                                isEditing = false
                                Toast.makeText(
                                    context,
                                    if (success) "Unpaired from ESP32 successfully" else "Unpaired locally (ESP32 unreachable)",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF87171)),
                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f).height(36.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Filled.Clear, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Unpair Device", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
