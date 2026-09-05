

package com.pisophone.kiosk.overlay.ui
import kotlinx.coroutines.isActive

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.model.BatteryStatus
import com.pisophone.kiosk.security.KioskSecurity
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun BlockScreenTimeHeader(batteryStatus: BatteryStatus, themeTextPrimary: Color) {
    var currentTimeStr by remember { mutableStateOf("") }
    
    LaunchedEffect(Unit) {
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        while (isActive) {
            currentTimeStr = timeFormat.format(Date())
            delay(1000)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .alpha(0.8f),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            currentTimeStr,
            color = themeTextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.5).sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Wifi, contentDescription = null, tint = themeTextPrimary, modifier = Modifier.size(16.dp))
            Icon(Icons.Filled.SignalCellular4Bar, contentDescription = null, tint = themeTextPrimary, modifier = Modifier.size(16.dp))
            Icon(Icons.Filled.BatteryFull, contentDescription = null, tint = themeTextPrimary, modifier = Modifier.size(16.dp))
            Text("${batteryStatus.level}%", color = themeTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun DeviceTitleBadge(
    deviceIp: String,
    themePrimary: Color,
    themeTextPrimary: Color,
    themeTextTertiary: Color,
    themeSurfaceVariant: Color
) {
    val ctx = LocalContext.current
    var customAlias by remember { mutableStateOf(KioskSecurity.getDeviceAlias(ctx)) }
    LaunchedEffect(Unit) {
        while (isActive) {
            customAlias = KioskSecurity.getDeviceAlias(ctx)
            delay(5000)
        }
    }
    val deviceNumber = remember(deviceIp) {
        try {
            val lastOctet = deviceIp.substringAfterLast(".").toIntOrNull()
            if (lastOctet != null && lastOctet in 100..120) {
                (lastOctet - 99).toString()
            } else if (lastOctet != null && lastOctet in 1..254) {
                lastOctet.toString()
            } else {
                "1"
            }
        } catch (e: Exception) {
            "1"
        }
    }
    val mainTitle = if (customAlias.isNotBlank()) customAlias else "PisoPhone $deviceNumber"

    Text(
        mainTitle,
        color = themeTextPrimary,
        fontSize = 30.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
        modifier = Modifier.padding(bottom = 2.dp)
    )

    Surface(
        color = themeSurfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(bottom = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(if (deviceIp != "127.0.0.1") themePrimary else Color.Gray, CircleShape)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                if (customAlias.isNotBlank()) "$mainTitle • IP: $deviceIp" else "IP: $deviceIp",
                color = themeTextTertiary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun BlockScreenRateTableCard(
    pricePerCoin: Double,
    minutesPerCoin: Int,
    isWaiting: Boolean,
    coinsInserted: Int,
    paymentTimeout: Int,
    isEsp32Online: Boolean,
    isSlotBusy: Boolean,
    buttonText: String,
    primaryColor: Color,
    onPrimaryColor: Color,
    surfaceColor: Color,
    surfaceVariantColor: Color,
    borderColor: Color,
    backgroundColor: Color,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    textTertiaryColor: Color,
    successColor: Color,
    onDoneClick: () -> Unit,
    onInsertCoin: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(surfaceColor, RoundedCornerShape(24.dp))
            .border(1.dp, borderColor, RoundedCornerShape(24.dp))
            .padding(24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Text(
                    "STANDARD RATE",
                    color = primaryColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(String.format(java.util.Locale.US, "%.2f PHP", pricePerCoin), color = textPrimaryColor, fontSize = 30.sp, fontWeight = FontWeight.Medium)
                    Text("/ unit", color = textTertiaryColor, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
            Box(
                modifier = Modifier
                    .background(surfaceVariantColor, RoundedCornerShape(12.dp))
                    .padding(8.dp)
            ) {
                Icon(Icons.Filled.Token, contentDescription = null, tint = primaryColor)
            }
        }

        // Alert Info Box
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(backgroundColor, RoundedCornerShape(16.dp))
                .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                .padding(16.dp)
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = successColor, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                if (isWaiting) "Waiting for coins from coinslot..." else "Device will auto-lock when timer expires. Save all work and remove account credentials before end of session.",
                color = textSecondaryColor,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        if (isWaiting && coinsInserted > 0) {
            Button(
                onClick = onDoneClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = successColor, contentColor = Color.Black),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("DONE (${paymentTimeout}s)", fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 1.sp)
            }
        } else if (!isWaiting) {
            val activeContainerColor = if (isSlotBusy) Color(0xFFDC3545) else if (isEsp32Online) primaryColor else surfaceVariantColor
            val activeContentColor = if (isSlotBusy) Color.White else if (isEsp32Online) onPrimaryColor else textTertiaryColor
            val activeText = if (isSlotBusy) "COINSLOT BUSY" else if (isEsp32Online) buttonText else "CONNECTING TO COINSLOT..."
            Button(
                onClick = onInsertCoin,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = activeContainerColor, 
                    contentColor = activeContentColor,
                    disabledContainerColor = activeContainerColor,
                    disabledContentColor = activeContentColor
                ),
                shape = RoundedCornerShape(14.dp),
                enabled = isEsp32Online && !isSlotBusy
            ) {
                if (isSlotBusy) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = Color.White)
                } else if (isEsp32Online) {
                    Icon(Icons.Filled.AddCircle, contentDescription = null)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    activeText, 
                    fontWeight = FontWeight.Bold, 
                    fontSize = 14.sp, 
                    letterSpacing = 1.sp
                )
            }
        }
    }
}
