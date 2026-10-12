package com.pisophone.kiosk.overlay.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pisophone.kiosk.R
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.ui.video.VideoBackgroundPolicy
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** A sticker floating on the lock screen: where it sits (fractions of the screen), how big, and how fast it bobs and wiggles. */
private class FloatingSticker(
    @DrawableRes val res: Int,
    val x: Float,
    val y: Float,
    val sizeDp: Float,
    val phase: Float,
)

private val STICKERS = listOf(
    FloatingSticker(R.drawable.technet_sticker_pisowifi, 0.13f, 0.13f, 78f, 0.00f),
    FloatingSticker(R.drawable.technet_sticker_ok, 0.87f, 0.10f, 66f, 0.31f),
    FloatingSticker(R.drawable.technet_sticker_happy, 0.10f, 0.33f, 70f, 0.57f),
    FloatingSticker(R.drawable.technet_sticker_controller, 0.89f, 0.30f, 74f, 0.12f),
    FloatingSticker(R.drawable.technet_sticker_cool, 0.09f, 0.54f, 68f, 0.83f),
    FloatingSticker(R.drawable.technet_sticker_phone, 0.90f, 0.52f, 74f, 0.46f),
    FloatingSticker(R.drawable.technet_sticker_laptop, 0.12f, 0.74f, 76f, 0.69f),
    FloatingSticker(R.drawable.technet_sticker_gamer, 0.88f, 0.72f, 72f, 0.22f),
    FloatingSticker(R.drawable.technet_sticker_wink, 0.30f, 0.90f, 62f, 0.95f),
    FloatingSticker(R.drawable.technet_sticker_pisophone, 0.74f, 0.91f, 74f, 0.40f),
    FloatingSticker(R.drawable.technet_sticker_question, 0.50f, 0.045f, 52f, 0.74f),
)

/** Where the coins fall (fractions of the width). The sparkles sit on a golden-ratio scatter: the same after every restart. */
private val COIN_X = floatArrayOf(.18f, .40f, .62f, .80f, .30f, .70f, .52f, .08f, .92f)

private const val SPARKLE_COUNT = 18
private const val LOOP_MS = 60_000
private const val TAU = 6.2831855f

/**
 * The TechNet mascot and his stickers, moving behind the lock screen for younger users: slow colour clouds, turning rays, the
 * mascot bobbing and swaying in the middle, stickers floating and wiggling around the edges, sparkles twinkling and coins
 * falling. It is one canvas that takes no touches and sits under the content, so the screen's layout does not change. Every
 * movement repeats seamlessly (a whole number of cycles in [LOOP_MS]). Where background videos are off or not affordable (the
 * admin's switch, low memory, power saving, low battery: [VideoBackgroundPolicy]) it stands still. [scrim] is the screen's own
 * background colour, used for the dim layer.
 */
@Composable
fun LockScreenArt(modifier: Modifier = Modifier, landscape: Boolean = false, scrim: Color = Color.Black) {
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
    // a State read only while drawing: a frame redraws the canvas and recomposes nothing
    val time: State<Float> = if (animate) {
        rememberInfiniteTransition(label = "lockArt").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(LOOP_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "lockArtTime",
        )
    } else {
        remember { mutableStateOf(0.07f) }
    }
    val hero = ImageBitmap.imageResource(R.drawable.technet_mascot_hero)
    val stickers = STICKERS.map { ImageBitmap.imageResource(it.res) }

    Canvas(modifier = modifier.fillMaxSize()) {
        val t = time.value
        drawClouds(t)
        val center = heroCenter(landscape)
        drawRays(t, center)
        if (animate) drawCoins(t)
        drawHero(hero, t, center, landscape)
        // a dim layer that grows toward the bottom keeps the text on top readable; the stickers and sparkles are above it
        drawRect(
            Brush.verticalGradient(
                0f to scrim.copy(alpha = 0.05f),
                0.45f to scrim.copy(alpha = 0.25f),
                1f to scrim.copy(alpha = 0.55f),
            ),
        )
        STICKERS.forEachIndexed { i, s -> drawSticker(stickers[i], s, i, t) }
        if (animate) drawSparkles(t)
    }
}

private fun DrawScope.heroCenter(landscape: Boolean) =
    if (landscape) {
        Offset(size.width * 0.25f, size.height * 0.5f)
    } else {
        Offset(size.width * 0.5f, size.height * 0.3f)
    }

/** Three slow colour clouds (TechNet blue, teal and gold) drifting around the screen. */
private fun DrawScope.drawClouds(t: Float) {
    val colors = listOf(Color(0xFF2F7BFF), Color(0xFF16C9A4), Color(0xFFFFB81C))
    val radius = size.width * 0.85f
    colors.forEachIndexed { i, c ->
        val a = TAU * (t * 2f + i / 3f)
        val center = Offset(size.width * (0.5f + 0.38f * sin(a)), size.height * (0.32f + 0.22f * cos(a * 1.5f + i)))
        drawCircle(
            brush = Brush.radialGradient(listOf(c.copy(alpha = 0.26f), Color.Transparent), center = center, radius = radius),
            radius = radius,
            center = center,
        )
    }
}

/** Light rays turning slowly behind the mascot. */
private fun DrawScope.drawRays(t: Float, center: Offset) {
    val reach = maxOf(size.width, size.height) * 1.3f
    rotate(degrees = 360f * t * 2f, pivot = center) {
        for (i in 0 until 12) {
            drawArc(
                color = Color.White.copy(alpha = 0.07f),
                startAngle = i * 30f,
                sweepAngle = 13f,
                useCenter = true,
                topLeft = Offset(center.x - reach, center.y - reach),
                size = Size(reach * 2, reach * 2),
            )
        }
    }
}

/** The mascot: a glow behind him pulses, he bobs, breathes and sways. */
private fun DrawScope.drawHero(hero: ImageBitmap, t: Float, center: Offset, landscape: Boolean) {
    val aspect = hero.width.toFloat() / hero.height
    val heroH = if (landscape) size.height * 0.82f else minOf(size.height * 0.46f, size.width * 0.96f / aspect)
    val heroW = heroH * aspect
    val glow = 0.22f + 0.1f * sin(TAU * t * 30f)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color(0xFF7FB0FF).copy(alpha = glow), Color.Transparent),
            center = center,
            radius = heroH * 0.7f,
        ),
        radius = heroH * 0.7f,
        center = center,
    )
    val bob = 8.dp.toPx() * sin(TAU * t * 20f)
    val sway = 2.5f * sin(TAU * t * 10f)
    val breathe = 1f + 0.02f * sin(TAU * t * 30f)
    withTransform({
        translate(0f, bob)
        rotate(sway, center)
        scale(breathe, breathe, center)
    }) {
        drawImage(
            image = hero,
            dstOffset = IntOffset((center.x - heroW / 2).toInt(), (center.y - heroH / 2).toInt()),
            dstSize = IntSize(heroW.toInt(), heroH.toInt()),
            filterQuality = FilterQuality.Medium,
        )
    }
}

/** One sticker: it bobs, wiggles and now and then pops bigger. */
private fun DrawScope.drawSticker(image: ImageBitmap, s: FloatingSticker, index: Int, t: Float) {
    val base = s.sizeDp.dp.toPx()
    val aspect = image.width.toFloat() / image.height
    val h = base
    val w = base * aspect
    val bobCycles = 10 + (index % 4) * 5
    val wiggleCycles = 12 + (index % 3) * 6
    val popCycles = 15 + (index % 5) * 5
    val bob = 10.dp.toPx() * sin(TAU * (t * bobCycles + s.phase))
    val wiggle = 10f * sin(TAU * (t * wiggleCycles + s.phase * 2f))
    val pop = 1f + 0.09f * sin(TAU * (t * popCycles + s.phase))
    val center = Offset(size.width * s.x, size.height * s.y + bob)
    withTransform({
        rotate(wiggle, center)
        scale(pop, pop, center)
    }) {
        drawImage(
            image = image,
            dstOffset = IntOffset((center.x - w / 2).toInt(), (center.y - h / 2).toInt()),
            dstSize = IntSize(w.toInt(), h.toInt()),
            filterQuality = FilterQuality.Medium,
        )
    }
}

/** Four-point stars that twinkle. */
private fun DrawScope.drawSparkles(t: Float) {
    val palette = listOf(Color.White, Color(0xFFFFD66B), Color(0xFF6FE3C8), Color(0xFF8FB8FF))
    for (i in 0 until SPARKLE_COUNT) {
        val twinkle = (sin(TAU * (t * (8 + i % 5 * 4) + i * 0.137f)) + 1f) / 2f
        val r = (4 + i % 4 * 2).dp.toPx() * (0.4f + 0.9f * twinkle)
        val c = Offset(size.width * ((i * 0.6180339f + 0.07f) % 1f), size.height * ((i * 0.4142136f + 0.11f) % 1f))
        val color = palette[i % palette.size].copy(alpha = 0.25f + 0.7f * twinkle)
        val stroke = 2.dp.toPx()
        drawLine(color, Offset(c.x - r, c.y), Offset(c.x + r, c.y), stroke, StrokeCap.Round)
        drawLine(color, Offset(c.x, c.y - r), Offset(c.x, c.y + r), stroke, StrokeCap.Round)
        val d = r * 0.55f
        drawLine(color, Offset(c.x - d, c.y - d), Offset(c.x + d, c.y + d), stroke * 0.6f, StrokeCap.Round)
        drawLine(color, Offset(c.x - d, c.y + d), Offset(c.x + d, c.y - d), stroke * 0.6f, StrokeCap.Round)
    }
}

/** Coins drifting down and spinning (a coin seen from the side is a thin oval). */
private fun DrawScope.drawCoins(t: Float) {
    val r = 11.dp.toPx()
    for (i in COIN_X.indices) {
        val fall = (t * (2 + i % 3) + i * 0.173f) % 1f
        val y = -r + fall * (size.height + 2 * r)
        val x = size.width * COIN_X[i] + 12.dp.toPx() * sin(TAU * (t * (6 + i % 4 * 3) + i * 0.21f))
        val squish = 0.18f + 0.82f * abs(cos(TAU * (t * (24 + i % 3 * 6) + i * 0.31f)))
        val fade = when {
            fall < 0.08f -> fall / 0.08f
            fall > 0.9f -> (1f - fall) / 0.1f
            else -> 1f
        }
        val topLeft = Offset(x - r * squish, y - r)
        val oval = Size(2 * r * squish, 2 * r)
        drawOval(Color(0xFFFFC21A).copy(alpha = 0.8f * fade), topLeft, oval)
        drawOval(Color(0xFFB57A00).copy(alpha = 0.9f * fade), topLeft, oval, style = Stroke(width = 2.dp.toPx()))
        drawOval(
            Color.White.copy(alpha = 0.55f * fade),
            Offset(x - r * squish * 0.45f, y - r * 0.45f),
            Size(r * squish * 0.9f, r * 0.9f),
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}
