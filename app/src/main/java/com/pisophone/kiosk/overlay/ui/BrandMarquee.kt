package com.pisophone.kiosk.overlay.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The shop's name as a bold band across the width (feature 023, extending 013): [MarqueeText.TEXT_SP] sp bold text in the palette's
 * accent with a gradient and a soft glow, on a strip of the palette's own backdrop colours, at least [MarqueeText.BAND_MIN_HEIGHT_DP] dp
 * high. With no name it says "PisoPhone", so the band is always there. While [scrolling] (the answer of [MarqueeRule]) the name runs
 * across without a stop, repeated with a gap so the band is never empty even for a short name, and a slow shine crosses the band: one
 * animation for the whole band. Otherwise the name stands still, centred, and is never clipped: a long one wraps to a second line.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrandMarquee(
    name: String?,
    accent: Color,
    bandTop: Color,
    bandBottom: Color,
    scrolling: Boolean,
    modifier: Modifier = Modifier,
) {
    val text = MarqueeText.textFor(name)
    val style = TextStyle(
        brush = Brush.horizontalGradient(listOf(accent, lerp(accent, Color.White, 0.55f), accent)),
        fontSize = MarqueeText.TEXT_SP.sp,
        fontWeight = FontWeight.ExtraBold,
        shadow = Shadow(color = accent.copy(alpha = 0.55f), blurRadius = 18f),
        textAlign = TextAlign.Center,
    )
    val band = Modifier
        .fillMaxWidth()
        .heightIn(min = MarqueeText.BAND_MIN_HEIGHT_DP.dp)
        .background(Brush.verticalGradient(listOf(bandTop, bandBottom)))
    if (scrolling) {
        val shine by rememberInfiniteTransition(label = "brand shine").animateFloat(
            initialValue = -0.3f,
            targetValue = 1.3f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 4_500, easing = LinearEasing), RepeatMode.Restart),
            label = "shine",
        )
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = modifier.then(band).drawWithContent {
                drawContent()
                val x = size.width * shine
                drawRect(
                    brush = Brush.linearGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.18f), Color.Transparent),
                        start = Offset(x - 120f, 0f),
                        end = Offset(x + 120f, size.height),
                    ),
                )
            },
        ) {
            Text(
                text = MarqueeText.runningLine(text),
                style = style,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier.fillMaxWidth().basicMarquee(
                    iterations = Int.MAX_VALUE,
                    initialDelayMillis = 0,
                    repeatDelayMillis = 0,
                    spacing = MarqueeSpacing(MarqueeText.GAP_DP.dp),
                    velocity = MarqueeText.SPEED_DP_PER_S.dp,
                ),
            )
        }
    } else {
        Box(contentAlignment = Alignment.Center, modifier = modifier.then(band)) {
            Text(
                text = text,
                style = style,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
