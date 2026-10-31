package com.pisophone.kiosk.overlay.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.R
import com.pisophone.kiosk.ui.theme.TechNet

/**
 * The banner shown above the lock screen or the game while a voice message from the shop plays (feature 056): a gold chip with a dot
 * that pulses like a voice. It takes no touches; the window is removed when the sound ends.
 */
@Composable
fun VoiceMessageBannerContent(effects: EffectsLevel) {
    val dotAlpha = if (effects.events) {
        val pulse = rememberInfiniteTransition(label = "voice-message")
        val animated by pulse.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 600), RepeatMode.Reverse),
            label = "voice-message-dot",
        )
        animated
    } else {
        1f
    }
    Row(
        modifier = Modifier
            .background(TechNet.gold, RoundedCornerShape(24.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .alpha(dotAlpha)
                .background(TechNet.onGold, CircleShape),
        )
        Text(
            text = stringResource(R.string.voice_message_banner),
            color = TechNet.onGold,
            fontSize = 18.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}
