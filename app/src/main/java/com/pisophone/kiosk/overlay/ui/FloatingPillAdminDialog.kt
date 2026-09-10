package com.pisophone.kiosk.overlay.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.security.KioskSecurity
import kotlinx.coroutines.delay

@Composable
fun FloatingPillAdminAuthCard(
    context: Context,
    outlineColor: Color,
    primaryColor: Color,
    onPrimaryColor: Color,
    onDismiss: () -> Unit,
    onUnlockSuccess: () -> Unit
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
            .background(Color(0xFF0F172A).copy(alpha = 0.97f), RoundedCornerShape(18.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AdminPanelSettings, contentDescription = null, tint = outlineColor, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Admin Authentication", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text("Enter Master Admin Password:", color = Color(0xFFA6ADC8), fontSize = 11.sp)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = enteredPin,
                onValueChange = { 
                    if (it.length <= 32) {
                        enteredPin = it
                        pinError = false
                    }
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                placeholder = { Text("Password", color = Color.Gray) },
                singleLine = true,
                isError = pinError,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF6366F1),
                    unfocusedBorderColor = Color(0xFF475569),
                    focusedContainerColor = Color(0xFF1E293B),
                    unfocusedContainerColor = Color(0xFF1E293B)
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(pinFocusRequester)
            )
            if (pinError) {
                Text("Invalid password. Default is 1234", color = Color(0xFFFF6B6B), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color(0xFFA6ADC8), fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Button(
                    onClick = {
                        if (KioskSecurity.verifyAdminPin(context, enteredPin)) {
                            onUnlockSuccess()
                        } else {
                            pinError = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor, contentColor = onPrimaryColor)
                ) {
                    Text("Unlock", fontSize = 12.sp)
                }
            }
        }
    }
}
