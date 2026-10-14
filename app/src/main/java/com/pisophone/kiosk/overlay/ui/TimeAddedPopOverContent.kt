package com.pisophone.kiosk.overlay.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.R
import com.pisophone.kiosk.ui.theme.TechNet
import kotlinx.coroutines.delay

/**
 * The reward when a customer adds time during a session: the mascot hops with a burst of sparkles and coins above whatever they
 * are playing, and a gold chip says how much was added. It lives 1.5 seconds (pops in, holds, fades) and takes no touches.
 */
@Composable
fun TimeAddedPopOverContent(addedSeconds: Int, effects: EffectsLevel) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        delay(900)
        appear.animateTo(0f, tween(260))
    }
    TimeAddedPopOverBody(addedSeconds, effects, appear.value)
}

/** What the pop-over looks like at one moment of its life: [appear] 0 is gone, 1 is fully shown (also used by the screenshot tests). */
@Composable
internal fun TimeAddedPopOverBody(addedSeconds: Int, effects: EffectsLevel, appear: Float) {
    Box(
        modifier = Modifier
            .width(240.dp)
            .height(190.dp)
            .graphicsLayer {
                val a = appear
                alpha = a.coerceIn(0f, 1f)
                scaleX = 0.7f + 0.3f * a
                scaleY = 0.7f + 0.3f * a
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        CelebrationLayer(trigger = 1, enabled = effects.events, burstAt = Offset(0.5f, 0.32f))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MascotView(state = MascotState.Cheer, size = 110.dp, effects = effects, cheerKey = 1)
            Text(
                text = formatAdded(addedSeconds),
                color = TechNet.onGold,
                style = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.ExtraBold, fontFeatureSettings = "tnum"),
                modifier = Modifier
                    .padding(top = 6.dp)
                    .background(TechNet.gold, RoundedCornerShape(20.dp))
                    .padding(horizontal = 18.dp, vertical = 4.dp),
            )
            Text(
                text = stringResource(R.string.popover_time_added),
                color = TechNet.text,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .background(TechNet.surface.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
    }
}
