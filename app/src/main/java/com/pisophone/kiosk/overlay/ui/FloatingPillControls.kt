package com.pisophone.kiosk.overlay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.R
import com.pisophone.kiosk.ui.theme.TechNet

/** One caption line instead of three chips: "Battery 80% · RAM 45%". */
@Composable
fun FloatingPillStatusLine(batteryPct: Int, ramStats: Pair<Long, Long>, modifier: Modifier = Modifier) {
    val ramPct = if (ramStats.second > 0) ((ramStats.first.toFloat() / ramStats.second) * 100).toInt() else 0
    Text(
        text = stringResource(R.string.pill_status_line, batteryPct, ramPct),
        color = TechNet.textMuted,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}

private val percentStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.ExtraBold, fontFeatureSettings = "tnum")

@Composable
fun FloatingPillBrightnessControl(
    currentBrightness: Float,
    maxBrightness: Float,
    onBrightnessChange: (Float) -> Unit,
) {
    val brightnessPct = ((currentBrightness / maxBrightness) * 100).toInt()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TechNet.surface, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (brightnessPct > 60) {
                Icons.Filled.BrightnessHigh
            } else if (brightnessPct > 25) {
                Icons.Filled.BrightnessMedium
            } else {
                Icons.Filled.BrightnessLow
            },
            contentDescription = stringResource(R.string.pill_brightness),
            tint = TechNet.gold,
            modifier = Modifier.size(24.dp),
        )
        Slider(
            value = currentBrightness,
            onValueChange = onBrightnessChange,
            valueRange = 10f..maxBrightness,
            colors = vaultSliderColors(TechNet.gold),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text("$brightnessPct%", color = TechNet.gold, style = percentStyle, modifier = Modifier.width(40.dp))
    }
}

@Composable
fun FloatingPillVolumeControl(
    currentVolume: Int,
    maxVolume: Int,
    onVolumeChange: (Int) -> Unit,
) {
    var preMuteVolume by remember { mutableIntStateOf(if (currentVolume > 0) currentVolume else (maxVolume / 2)) }
    val safeMax = if (maxVolume > 0) maxVolume else 1
    val volumePct = ((currentVolume.toFloat() / safeMax) * 100).toInt().coerceIn(0, 100)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TechNet.surface, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (currentVolume == 0) Icons.AutoMirrored.Filled.VolumeMute else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = stringResource(R.string.pill_cd_mute),
            tint = TechNet.brandFg,
            modifier = Modifier
                .size(24.dp)
                .clickable {
                    if (currentVolume > 0) {
                        preMuteVolume = currentVolume
                        onVolumeChange(0)
                    } else {
                        onVolumeChange(if (preMuteVolume > 0) preMuteVolume else (safeMax / 2))
                    }
                },
        )
        Slider(
            value = currentVolume.toFloat(),
            onValueChange = { onVolumeChange(it.toInt().coerceIn(0, safeMax)) },
            valueRange = 0f..safeMax.toFloat(),
            colors = vaultSliderColors(TechNet.brandFg),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text("$volumePct%", color = TechNet.brandFg, style = percentStyle, modifier = Modifier.width(40.dp))
    }
}
