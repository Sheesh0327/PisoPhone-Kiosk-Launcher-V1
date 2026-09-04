package com.pisophone.kiosk.overlay.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.model.BatteryAlertState
import com.pisophone.kiosk.model.BatteryStatus

@Composable
fun BatteryAlertBanner(
    batteryStatus: BatteryStatus,
    modifier: Modifier = Modifier
) {
    if (batteryStatus.alertState == BatteryAlertState.NONE) return

    val isLowBattery = batteryStatus.alertState == BatteryAlertState.LOW_BATTERY_UNPLUGGED

    val bgColor = if (isLowBattery) Color(0xFF350B0B).copy(alpha = 0.97f) else Color(0xFF332002).copy(alpha = 0.97f)
    val borderColor = if (isLowBattery) Color(0xFFFF2222) else Color(0xFFFFB800)
    val titleColor = if (isLowBattery) Color(0xFFFF4D4D) else Color(0xFFFFD13B)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = BorderStroke(2.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (isLowBattery) Color(0xFFFF2222).copy(alpha = 0.25f) else Color(0xFFFFB800).copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                if (isLowBattery) {
                    Icon(
                        Icons.Filled.BatteryFull,
                        contentDescription = "Low Battery",
                        tint = Color(0xFFFF3333),
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    Icon(
                        Icons.Filled.Bolt,
                        contentDescription = "Disconnect Charger",
                        tint = Color(0xFFFFB800),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isLowBattery) "⚠️ LOW BATTERY (${batteryStatus.level}%)" else "⚡ UNPLUG CHARGER (${batteryStatus.level}%)",
                    color = titleColor,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 12.sp,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isLowBattery) 
                        "Battery is below the threshold! Connect charger now to avoid shutdown." 
                    else 
                        "Battery reached the threshold! Please disconnect charger cable to preserve battery health.",
                    color = Color(0xFFF1F5F9),
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
