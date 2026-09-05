package com.pisophone.kiosk.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF060B14),
                        Color(0xFF0B1120),
                        Color(0xFF0F172A)
                    )
                )
            )
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            color = Color(0x1A10B981),
            shape = RoundedCornerShape(100.dp),
            border = BorderStroke(1.dp, Color(0x4D10B981)),
            modifier = Modifier.padding(bottom = 24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(Color(0xFF10B981), CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "SYSTEM AUTHORIZATION",
                    color = Color(0xFF34D399),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }

        Box(
            modifier = Modifier
                .size(88.dp)
                .background(Color(0x1A10B981), shape = CircleShape)
                .border(1.dp, Color(0x4D10B981), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Security,
                contentDescription = "Security Permission",
                tint = Color(0xFF10B981),
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            "Overlay Permission Required",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, color = Color(0xFFF8FAFC)),
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "PisoPhone Kiosk requires permission to display over other apps to lock the terminal securely and manage gaming sessions reliably.",
            style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFF94A3B8), lineHeight = 22.sp),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onRequest,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981), contentColor = Color(0xFF020617)),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
            modifier = Modifier.testTag("grant_permission_button"),
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Grant Overlay Permission", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}
