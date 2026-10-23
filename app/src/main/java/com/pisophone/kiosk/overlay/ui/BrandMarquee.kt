package com.pisophone.kiosk.overlay.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The shop's name on one line (feature 012). While [scrolling] (the answer of [MarqueeRule]) a name too long for the width runs
 * across it, and one that fits stays centred. Otherwise the name stands still, centred, and is never clipped: a long one wraps
 * to a second line instead of being cut. Draws nothing for a blank name, so an unbranded phone has no marquee row at all.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrandMarquee(
    name: String?,
    color: Color,
    scrolling: Boolean,
    modifier: Modifier = Modifier,
) {
    if (name.isNullOrBlank()) return
    if (scrolling) {
        Text(
            text = name,
            color = color,
            fontSize = 14.sp,
            fontWeight = FontWeight.W600,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.Center,
            modifier = modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1_500, velocity = 40.dp),
        )
    } else {
        Text(
            text = name,
            color = color,
            fontSize = 14.sp,
            fontWeight = FontWeight.W600,
            maxLines = 2,
            textAlign = TextAlign.Center,
            modifier = modifier.fillMaxWidth(),
        )
    }
}
