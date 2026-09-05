package com.pisophone.kiosk.overlay.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.security.KioskSecurity
import kotlinx.coroutines.delay

/**
 * Authentication dialog for unlocking the administrator Security Vault from the Block Screen.
 */
@Composable
fun AdminAuthenticationDialog(
    context: Context,
    surfaceColor: Color,
    primaryColor: Color,
    borderColor: Color,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    onDismiss: () -> Unit,
    onUnlockSuccess: (String) -> Unit,
    onOpenEmergencyRecovery: () -> Unit
) {
    var enteredPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }
    val pinFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        delay(150)
        pinFocusRequester.requestFocus()
        keyboardController?.show()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = surfaceColor)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Admin Authentication", fontWeight = FontWeight.Bold, color = textPrimaryColor, fontSize = 20.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Enter Master Admin Password:", color = textSecondaryColor, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = enteredPin,
                    onValueChange = { if (it.length <= 32) enteredPin = it },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    isError = pinError,
                    textStyle = TextStyle(color = textPrimaryColor, fontSize = 16.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = textPrimaryColor,
                        unfocusedTextColor = textPrimaryColor,
                        focusedBorderColor = primaryColor,
                        unfocusedBorderColor = borderColor
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(pinFocusRequester)
                )
                if (pinError) {
                    Text("Invalid password. Default is 1234", color = Color(0xFFFF6B6B), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = {
                        if (KioskSecurity.verifyAdminPin(context, enteredPin)) {
                            onOpenEmergencyRecovery()
                        } else {
                            pinError = true
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF4444)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color(0xFFFF4444))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("SYSTEM RECOVERY & ADB HUB", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        if (KioskSecurity.verifyAdminPin(context, enteredPin)) {
                            val secret = KioskSecurity.getSharedSecret(context)
                            onUnlockSuccess(secret)
                        } else {
                            pinError = true
                        }
                    }) { Text("Unlock") }
                }
            }
        }
    }
}

/**
 * Overlay shown when the machine is unlicensed.
 */
@Composable
fun UnlicensedActivationOverlay(
    macAddress: String,
    backgroundColor: Color,
    surfaceColor: Color,
    borderColor: Color,
    primaryColor: Color,
    onPrimaryColor: Color,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    onActivateClick: (String) -> Unit
) {
    var activationCode by remember { mutableStateOf("") }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .clickable(enabled = false) {},
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(top = 100.dp, start = 32.dp, end = 32.dp)
                .background(surfaceColor, RoundedCornerShape(24.dp))
                .border(2.dp, borderColor, RoundedCornerShape(24.dp))
                .padding(32.dp)
        ) {
            Icon(Icons.Filled.Lock, contentDescription = "Locked", tint = Color.Red, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(16.dp))
            Text("DEVICE ACTIVATION REQUIRED", color = textPrimaryColor, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("This Piso phone machine is unlicensed.", color = textSecondaryColor, fontSize = 14.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Text("Hardware ID (MAC):", color = textSecondaryColor, fontSize = 12.sp)
            Text(macAddress.ifEmpty { "UNKNOWN" }, color = primaryColor, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(24.dp))
            
            OutlinedTextField(
                value = activationCode,
                onValueChange = { activationCode = it.trim().uppercase() },
                placeholder = { Text("e.g. PISO-XXXX-XXXX", color = textSecondaryColor) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = primaryColor,
                    unfocusedBorderColor = borderColor,
                    focusedTextColor = textPrimaryColor,
                    unfocusedTextColor = textPrimaryColor,
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { onActivateClick(activationCode) },
                colors = ButtonDefaults.buttonColors(containerColor = primaryColor, contentColor = onPrimaryColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text("ACTIVATE MACHINE", fontWeight = FontWeight.Bold)
            }
        }
    }
}
