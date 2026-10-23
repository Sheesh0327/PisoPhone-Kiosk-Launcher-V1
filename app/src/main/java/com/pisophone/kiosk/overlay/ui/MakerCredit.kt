package com.pisophone.kiosk.overlay.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.R
import kotlinx.coroutines.delay

/**
 * The maker's credit, "by TechNet Solutions", at the bottom of the lock and home screens (feature 013). It is part of the signed
 * app, not configuration: it takes no shop name, no title, no density and no switch, only the colour its palette gives it and the
 * thermal and battery level the phone is at, so nothing the box sends can remove, hide or still it. The words always stay on
 * screen; only the sheen rests, with all the other motion, on a hot or nearly flat phone.
 */
object MakerCredit {
    /** One sheen about every 6 seconds: it sweeps for [SWEEP_MS] and rests for the rest of the cycle. */
    const val CYCLE_MS = 6_000
    const val SWEEP_MS = 1_200

    /** The sheen runs where ambient motion may run; the animation density has no say. */
    fun sheenRuns(effects: EffectsLevel): Boolean = effects.ambient
}

@Composable
fun MakerCredit(
    color: Color,
    effects: EffectsLevel,
    modifier: Modifier = Modifier,
) {
    val sweep = remember { Animatable(0f) }
    val runs = MakerCredit.sheenRuns(effects)
    LaunchedEffect(runs) {
        sweep.snapTo(0f)
        // frames are drawn only during the sweep: 0 fps at rest between sweeps
        while (runs) {
            delay((MakerCredit.CYCLE_MS - MakerCredit.SWEEP_MS).toLong())
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(MakerCredit.SWEEP_MS, easing = LinearEasing))
        }
    }
    Text(
        text = stringResource(R.string.maker_credit),
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.W600,
        maxLines = 1,
        modifier = modifier
            .padding(vertical = 4.dp)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val p = sweep.value
                if (p > 0f && p < 1f) {
                    // a soft white band crossing the words; SrcAtop keeps it on the letters only
                    val band = size.width * 0.35f
                    val x = -band + (size.width + 2 * band) * p
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = 0.85f), Color.Transparent),
                            startX = x - band / 2,
                            endX = x + band / 2,
                        ),
                        topLeft = Offset.Zero,
                        size = size,
                        blendMode = BlendMode.SrcAtop,
                    )
                }
            },
    )
}
