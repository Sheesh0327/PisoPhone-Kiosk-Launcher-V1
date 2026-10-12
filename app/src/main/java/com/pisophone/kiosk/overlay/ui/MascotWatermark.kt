package com.pisophone.kiosk.overlay.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.pisophone.kiosk.R
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.ui.video.VideoBackgroundPolicy

/**
 * The TechNet mascot as a faint watermark behind a screen: it breathes (its opacity pulses) and floats a little. It never takes
 * touches and sits under the content, so the screen's layout is unchanged. It holds still, at a low opacity, on the phones where
 * background videos are off or not affordable (the admin's switch, low memory, power saving, low battery: [VideoBackgroundPolicy]).
 */
@Composable
fun MascotWatermark(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val animate = remember {
        val facts = VideoBackgroundPolicy.readDeviceFacts(context)
        VideoBackgroundPolicy.shouldPlay(
            enabled = KioskSecurity.isVideoBackgroundEnabled(context),
            lowRamDevice = facts.lowRamDevice,
            powerSaveMode = facts.powerSaveMode,
            batteryPercent = facts.batteryPercent,
            charging = facts.charging,
            screenInFront = true,
            covered = false,
        )
    }
    val transition = if (animate) rememberInfiniteTransition(label = "mascot") else null
    // State objects, read only while drawing: a frame redraws the layer and recomposes nothing.
    val pulse: State<Float> = transition?.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "mascotPulse",
    ) ?: remember { mutableStateOf(0.5f) }
    val drift: State<Float> = transition?.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "mascotDrift",
    ) ?: remember { mutableStateOf(0.5f) }
    Image(
        painter = painterResource(R.drawable.technet_mascot_watermark),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.graphicsLayer {
            alpha = 0.10f + 0.10f * pulse.value
            translationY = -12.dp.toPx() * drift.value
            val scale = 1f + 0.03f * drift.value
            scaleX = scale
            scaleY = scale
        },
    )
}
