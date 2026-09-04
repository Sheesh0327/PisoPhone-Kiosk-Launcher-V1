package com.pisophone.kiosk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.security.KioskSecurity

@Composable
fun ProvisioningScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    val deviceSecret = remember { KioskSecurity.getSharedSecret(context) }
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.Warning, contentDescription = "Setup Required", tint = Color(0xFFF59E0B), modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text("Initial Provisioning Required", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Before this kiosk can go live, you must change the default Admin PIN and copy the Device Secret to the ESP32.",
            color = Color(0xFF94A3B8),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("1. Set New Admin PIN", fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.take(8) },
                    label = { Text("New PIN") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { confirmPin = it.take(8) },
                    label = { Text("Confirm PIN") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White
                    )
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("2. Copy Device Secret to ESP32", fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Connect to the ESP32 'AdminSetup' Wi-Fi, go to the Captive Portal at 192.168.4.1, and enter this exact secret into the MASTER_CRYPTO_SECRET field.",
                    color = Color(0xFF94A3B8), fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = deviceSecret,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = Color(0xFF10B981),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF020617), RoundedCornerShape(6.dp))
                        .padding(12.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        
        if (errorMsg.isNotEmpty()) {
            Text(errorMsg, color = Color(0xFFEF4444), modifier = Modifier.padding(bottom = 16.dp))
        }

        Button(
            onClick = {
                if (pin.length < 4) {
                    errorMsg = "PIN must be at least 4 digits"
                } else if (pin == "1234") {
                    errorMsg = "You cannot use the default PIN"
                } else if (pin != confirmPin) {
                    errorMsg = "PINs do not match"
                } else {
                    KioskSecurity.setAdminPin(context, pin)
                    onComplete()
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
        ) {
            Text("Complete Provisioning", fontWeight = FontWeight.Bold, color = Color.Black)
        }
    }
}
