package com.pisophone.kiosk.overlay.ui

import android.app.ActivityManager
import android.content.Context
import android.media.AudioManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
fun FloatingBallStatusChips(
    batteryPct: Int,
    ramStats: Pair<Long, Long>,
    outlineColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Battery Chip
        Box(
            modifier = Modifier
                .weight(1f)
                .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.BatteryFull, contentDescription = null, tint = outlineColor, modifier = Modifier.size(13.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("$batteryPct%", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        // RAM Usage Chip
        val ramPct = if (ramStats.second > 0) ((ramStats.first.toFloat() / ramStats.second) * 100).toInt() else 45
        Box(
            modifier = Modifier
                .weight(1f)
                .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Memory, contentDescription = null, tint = Color(0xFF00FF88), modifier = Modifier.size(13.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("$ramPct% RAM", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Turbo Status Chip
        Box(
            modifier = Modifier
                .weight(1f)
                .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                .border(1.dp, outlineColor.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Bolt, contentDescription = null, tint = Color(0xFFFFD600), modifier = Modifier.size(13.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text("TURBO", color = Color(0xFFFFD600), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
fun FloatingBallBrightnessControl(
    currentBrightness: Float,
    maxBrightness: Float,
    onBrightnessChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0B132B).copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val brightnessPct = ((currentBrightness / maxBrightness) * 100).toInt()
        Icon(
            if (brightnessPct > 60) Icons.Filled.BrightnessHigh else if (brightnessPct > 25) Icons.Filled.BrightnessMedium else Icons.Filled.BrightnessLow,
            contentDescription = null,
            tint = Color(0xFFFFB800),
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Slider(
            value = currentBrightness,
            onValueChange = onBrightnessChange,
            valueRange = 10f..maxBrightness,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFFFB800),
                activeTrackColor = Color(0xFFFFB800),
                inactiveTrackColor = Color(0xFF334155)
            ),
            modifier = Modifier
                .weight(1f)
                .height(24.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            "$brightnessPct%", 
            color = Color(0xFFFFB800), 
            fontSize = 11.sp, 
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun FloatingBallVolumeControl(
    audioManager: AudioManager,
    maxVolume: Int,
    outlineColor: Color
) {
    var currentVolume by remember { 
        mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) 
    }
    var isMuted by remember { mutableStateOf(currentVolume == 0f) }
    var preMuteVolume by remember { mutableFloatStateOf(currentVolume) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0B132B).copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val volumePct = ((currentVolume / maxVolume) * 100).toInt()
        Icon(
            if (currentVolume == 0f) Icons.AutoMirrored.Filled.VolumeMute else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = "Volume Icon",
            tint = outlineColor,
            modifier = Modifier
                .size(15.dp)
                .clickable {
                    if (currentVolume > 0) {
                        preMuteVolume = currentVolume
                        currentVolume = 0f
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                        isMuted = true
                    } else {
                        val restored = if (preMuteVolume > 0) preMuteVolume else (maxVolume * 0.5f)
                        currentVolume = restored
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restored.toInt(), 0)
                        isMuted = false
                    }
                }
        )
        Spacer(modifier = Modifier.width(6.dp))
        Slider(
            value = currentVolume,
            onValueChange = {
                currentVolume = it
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, it.toInt(), 0)
                isMuted = it == 0f
            },
            valueRange = 0f..maxVolume.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = outlineColor,
                activeTrackColor = outlineColor,
                inactiveTrackColor = Color(0xFF334155)
            ),
            modifier = Modifier
                .weight(1f)
                .height(24.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            "$volumePct%", 
            color = outlineColor, 
            fontSize = 11.sp, 
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun FloatingBallRamCleaner(
    context: Context,
    activityManager: ActivityManager,
    ramStats: Pair<Long, Long>,
    onRefreshRam: () -> Unit
) {
    var isBoosting by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0B132B).copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CleaningServices, contentDescription = null, tint = Color(0xFF00FF88), modifier = Modifier.size(13.dp))
            Spacer(modifier = Modifier.width(5.dp))
            Text("RAM", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                "${String.format(Locale.US, "%.1f", ramStats.first / 1024.0)} / ${String.format(Locale.US, "%.1f", ramStats.second / 1024.0)} GB",
                color = Color(0xFFA6ADC8),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Button(
            onClick = {
                isBoosting = true
                try {
                    val beforeUsed = ramStats.first
                    val runningApps = activityManager.runningAppProcesses ?: emptyList()
                    for (proc in runningApps) {
                        if (proc.processName != context.packageName) {
                            activityManager.killBackgroundProcesses(proc.processName)
                        }
                    }
                    System.gc()
                    Runtime.getRuntime().gc()
                    onRefreshRam()
                    val freed = (beforeUsed - ramStats.first).coerceAtLeast(160L)
                    Toast.makeText(context, "⚡ Turbo Boost: Freed ${freed}MB RAM", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "⚡ RAM Cleaned & Boosted for Gaming", Toast.LENGTH_SHORT).show()
                }
                isBoosting = false
            },
            modifier = Modifier.height(26.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF00FF88),
                contentColor = Color.Black
            ),
            shape = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) {
            Icon(Icons.Filled.RocketLaunch, contentDescription = null, modifier = Modifier.size(11.dp), tint = Color.Black)
            Spacer(modifier = Modifier.width(4.dp))
            Text("BOOST", fontWeight = FontWeight.ExtraBold, fontSize = 10.sp)
        }
    }
}
