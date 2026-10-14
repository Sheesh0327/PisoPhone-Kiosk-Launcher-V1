package com.pisophone.kiosk.overlay.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.R
import com.pisophone.kiosk.ui.theme.TechNet
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** What the TechNet mascot is doing (docs/design/GUI_SPEC_PISOPHONE_KIDS.md section 3). */
enum class MascotState { Idle, Listening, Cheer, Worried, Sleepy, ThankYou }

private const val TAU = 6.2831855f

/**
 * The mascot, in plain Compose: the one picture, moved only through graphicsLayer. At rest nothing runs. Idle wiggles now and then
 * (every 3 to 6 seconds, only while [effects] allow ambient movement), Listening holds a tilt, Worried shakes once a second,
 * Sleepy is grey and still, ThankYou waves. [cheerKey] counts events (a coin): each new value makes him hop.
 */
@Composable
fun MascotView(
    state: MascotState,
    size: Dp,
    effects: EffectsLevel,
    modifier: Modifier = Modifier,
    cheerKey: Int = 0,
    description: String = "",
) {
    val hero = ImageBitmap.imageResource(R.drawable.technet_mascot_hero)
    val tilt = remember { Animatable(0f) }
    val hop = remember { Animatable(0f) }

    LaunchedEffect(state, effects) {
        if (!effects.events) {
            tilt.snapTo(if (state == MascotState.Listening) 6f else 0f)
            return@LaunchedEffect
        }
        when (state) {
            MascotState.Idle -> {
                tilt.animateTo(0f, tween(150))
                var round = 0
                while (effects.ambient) {
                    delay(Random.nextLong(2_000L, 3_500L))
                    if (round % 3 == 2) {
                        // every third time a hop instead of a wave
                        hop.animateTo(1f, tween(160, easing = FastOutSlowInEasing))
                        hop.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
                    } else {
                        tilt.animateTo(9f, tween(150))
                        tilt.animateTo(-9f, tween(260))
                        tilt.animateTo(5f, tween(200))
                        tilt.animateTo(0f, tween(150))
                    }
                    round++
                }
            }
            MascotState.Listening -> tilt.animateTo(6f, tween(200))
            MascotState.Worried -> {
                while (true) {
                    tilt.animateTo(-5f, tween(70))
                    tilt.animateTo(5f, tween(70))
                    tilt.animateTo(0f, tween(70))
                    delay(700)
                }
            }
            MascotState.ThankYou -> {
                repeat(5) {
                    tilt.animateTo(10f, tween(220))
                    tilt.animateTo(-10f, tween(220))
                }
                tilt.animateTo(0f, tween(150))
            }
            MascotState.Cheer, MascotState.Sleepy -> tilt.animateTo(0f, tween(150))
        }
    }

    LaunchedEffect(cheerKey) {
        if (cheerKey > 0 && effects.events) {
            hop.snapTo(0f)
            hop.animateTo(1f, tween(160, easing = FastOutSlowInEasing))
            hop.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
    }

    // a slow breath while he waits (only when ambient movement is allowed)
    val breathing = effects.ambient && state == MascotState.Idle
    val breath: State<Float> = if (breathing) {
        rememberInfiniteTransition(label = "mascotBreath").animateFloat(
            initialValue = 1f,
            targetValue = 1.035f,
            animationSpec = infiniteRepeatable(tween(1_300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "mascotBreathScale",
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    val grey = if (state == MascotState.Sleepy) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null
    Image(
        bitmap = hero,
        contentDescription = null,
        colorFilter = grey,
        alpha = if (state == MascotState.Sleepy) 0.7f else 1f,
        modifier = modifier
            .size(size)
            .then(if (description.isNotEmpty()) Modifier.semantics { contentDescription = description } else Modifier)
            .graphicsLayer {
                rotationZ = tilt.value
                translationY = -hop.value * 16.dp.toPx()
                scaleY = (1f + 0.05f * hop.value) * breath.value
                scaleX = (1f - 0.03f * hop.value) * breath.value
            },
    )
}

/**
 * The disc behind the mascot: one 135-degree blue-to-teal gradient (the only gradient on the screen) and, while coins are being
 * waited for, the countdown ring with the seconds in a badge. [ringFraction] is null when there is no countdown.
 */
@Composable
fun Halo(
    discSize: Dp,
    effects: EffectsLevel,
    mascot: MascotState,
    modifier: Modifier = Modifier,
    ringFraction: Float? = null,
    ringSeconds: Int = 0,
    cheerKey: Int = 0,
    mascotDescription: String = "",
) {
    val mascotSize = discSize - 24.dp
    Box(modifier = modifier.size(discSize), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(discSize)
                .drawWithCache {
                    val brush = Brush.linearGradient(
                        listOf(TechNet.haloFrom, TechNet.haloTo),
                        start = Offset.Zero,
                        end = Offset(this.size.width, this.size.height),
                    )
                    onDrawBehind { drawCircle(brush) }
                },
        )
        if (ringFraction != null) {
            val warn = ringSeconds in 1..5
            Canvas(modifier = Modifier.size(discSize)) {
                val stroke = 8.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
                drawCircle(Color.White.copy(alpha = 0.25f), radius = arcSize.width / 2, style = Stroke(width = stroke))
                drawArc(
                    color = if (warn) TechNet.gold else Color.White,
                    startAngle = -90f,
                    sweepAngle = 360f * ringFraction.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        MascotView(
            state = mascot,
            size = mascotSize,
            effects = effects,
            cheerKey = cheerKey,
            description = mascotDescription,
            modifier = Modifier.offset(y = (-8).dp),
        )
        if (ringFraction != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 10.dp)
                    .drawWithCache {
                        onDrawBehind { drawRoundRect(TechNet.surface, cornerRadius = CornerRadius(14.dp.toPx())) }
                    },
            ) {
                Text(
                    text = "${ringSeconds}s",
                    color = if (ringSeconds in 1..5) TechNet.gold else TechNet.text,
                    style = TextStyle(
                        fontSize = 20.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFeatureSettings = "tnum",
                    ),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
        }
    }
}

private val COIN_X = floatArrayOf(.18f, .40f, .62f, .80f, .30f, .70f, .52f, .08f, .92f)

/**
 * The burst for one coin: nine coins falling and a few sparkles opening around the mascot, at most 1.2 seconds, then nothing
 * (no frames at rest). [trigger] counts events; each new value plays it once. Nothing plays when [enabled] is false.
 */
@Composable
fun CelebrationLayer(trigger: Int, enabled: Boolean, modifier: Modifier = Modifier, burstAt: Offset = Offset(0.5f, 0.28f)) {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(trigger) {
        if (trigger > 0 && enabled) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(1_200, easing = LinearEasing))
        }
    }
    Canvas(modifier = modifier.fillMaxSize()) {
        val p = progress.value
        if (p >= 1f) return@Canvas
        drawFallingCoins(p)
        drawBurst(p, Offset(size.width * burstAt.x, size.height * burstAt.y))
    }
}

private fun DrawScope.drawFallingCoins(p: Float) {
    val r = 11.dp.toPx()
    for (i in COIN_X.indices) {
        val t = ((p - i * 0.03f) / 0.73f).coerceIn(0f, 1f)
        if (t <= 0f) continue
        val y = -r + t * (size.height * 0.75f + 2 * r)
        val x = size.width * COIN_X[i] + 12.dp.toPx() * sin(TAU * (t * 1.5f + i * 0.21f))
        val squish = 0.18f + 0.82f * abs(cos(TAU * (t * 3f + i * 0.31f)))
        val fade = if (t > 0.85f) (1f - t) / 0.15f else 1f
        val topLeft = Offset(x - r * squish, y - r)
        val oval = Size(2 * r * squish, 2 * r)
        drawOval(TechNet.gold.copy(alpha = 0.9f * fade), topLeft, oval)
        drawOval(TechNet.goldEdge.copy(alpha = fade), topLeft, oval, style = Stroke(width = 2.dp.toPx()))
    }
}

private fun DrawScope.drawBurst(p: Float, center: Offset) {
    val t = (p / 0.5f).coerceIn(0f, 1f)
    if (t >= 1f) return
    val palette = listOf(Color.White, TechNet.gold, TechNet.teal)
    for (i in 0 until 6) {
        val angle = TAU * (i / 6f) + 0.4f
        val reach = (40 + 30 * t).dp.toPx()
        val c = Offset(center.x + reach * cos(angle), center.y + reach * sin(angle))
        val r = (3 + 4 * (1f - t)).dp.toPx()
        val color = palette[i % palette.size].copy(alpha = 1f - t)
        val stroke = 2.dp.toPx()
        drawLine(color, Offset(c.x - r, c.y), Offset(c.x + r, c.y), stroke, StrokeCap.Round)
        drawLine(color, Offset(c.x, c.y - r), Offset(c.x, c.y + r), stroke, StrokeCap.Round)
    }
}
