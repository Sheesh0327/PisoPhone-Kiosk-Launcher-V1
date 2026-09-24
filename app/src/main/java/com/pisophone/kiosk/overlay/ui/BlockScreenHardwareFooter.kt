package com.pisophone.kiosk.overlay.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.security.KioskSecurity

@Composable
fun BlockScreenHardwareFooter(
    context: Context,
    isEsp32Online: Boolean,
    surfaceColor: Color,
    successColor: Color,
    textPrimaryColor: Color,
    textTertiaryColor: Color,
    onThemeChange: () -> Unit,
    onOpenSecurityVault: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(surfaceColor, RoundedCornerShape(28.dp))
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(if (isEsp32Online) successColor else Color.Red, CircleShape)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        if (isEsp32Online) "HARDWARE CONTROLLER CONNECTED" else "HARDWARE CONTROLLER OFFLINE", 
                        color = textPrimaryColor, 
                        fontSize = 11.sp, 
                        fontWeight = FontWeight.Bold, 
                        letterSpacing = 0.5.sp
                    )
                    val configuredEsp32Ip = remember(context) { KioskSecurity.getConfiguredEsp32Ip(context) }
                    Text(
                        if (isEsp32Online) "Hardware Interlock Synchronized ($configuredEsp32Ip)" else "Connecting to ESP32 ($configuredEsp32Ip)...", 
                        color = textTertiaryColor, 
                        fontSize = 10.sp
                    )
                }
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
            .alpha(0.6f),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onThemeChange) { 
            Icon(Icons.Filled.Palette, contentDescription = "Change Theme", tint = textPrimaryColor) 
        }
        Spacer(modifier = Modifier.width(16.dp))
        IconButton(onClick = onOpenSecurityVault) { 
            Icon(Icons.Filled.AdminPanelSettings, contentDescription = "Security Vault", tint = textPrimaryColor) 
        }
    }
}
