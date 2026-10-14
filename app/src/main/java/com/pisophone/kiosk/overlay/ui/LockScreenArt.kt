package com.pisophone.kiosk.overlay.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pisophone.kiosk.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The three backdrop colours a child can pick (the palette button on the lock screen). They change the background only: text,
 * buttons and the panel keep their colours. Old stored theme numbers 0 to 7 map to the three with a modulo.
 */
enum class BackdropTint(val top: Color, val bottom: Color, val cloudA: Color, val cloudB: Color, val cloudC: Color) {
    Dagat(Color(0xFF050D22), Color(0xFF0A1B45), Color(0xFF2F7BFF), Color(0xFF16C9A4), Color(0xFFFFB81C)),
    Tropiko(Color(0xFF04161A), Color(0xFF063A3A), Color(0xFF16C9A4), Color(0xFF2F7BFF), Color(0xFFFF6FB5)),
    Ginto(Color(0xFF0B1530), Color(0xFF2A2210), Color(0xFFFFB81C), Color(0xFF2F7BFF), Color(0xFF16C9A4)),
    ;

    companion object {
        fun of(index: Int): BackdropTint = values()[Math.floorMod(index, values().size)]
    }
}

/**
 * A sticker floating on the lock screen: which screen edge it peeks in from ([x], a fraction of the width), where it sits between
 * the header and the footer ([v], 0 is the top of that band and 1 the bottom), how big, and its own rhythm. Stickers stay on the
 * edges and out of the header and footer rows, so no text (the title, the phone chip, the box status) is ever drawn over one.
 */
private class ArtSticker(
    @DrawableRes val res: Int,
    val x: Float,
    val v: Float,
    val sizeDp: Float,
    val phase: Float,
    /** Shown on a wide (landscape) screen too: there only the left edge is free (the panel fills the right), so there are fewer. */
    val inLandscape: Boolean = true,
)

/** The band the stickers float in: below the header (status bar, title, phone chip) and above the footer (palette, box status, owner). */
private val BAND_TOP_PORTRAIT = 140.dp
private val BAND_BOTTOM_PORTRAIT = 130.dp
private val BAND_TOP_LANDSCAPE = 70.dp
private val BAND_BOTTOM_LANDSCAPE = 70.dp

private val STICKERS = listOf(
    ArtSticker(R.drawable.technet_sticker_pisowifi, 0.0f, 0.00f, 72f, 0.00f),
    ArtSticker(R.drawable.technet_sticker_ok, 1.0f, 0.12f, 66f, 0.31f, inLandscape = false),
    ArtSticker(R.drawable.technet_sticker_happy, 0.0f, 0.25f, 66f, 0.57f, inLandscape = false),
    ArtSticker(R.drawable.technet_sticker_controller, 1.0f, 0.37f, 70f, 0.12f, inLandscape = false),
    ArtSticker(R.drawable.technet_sticker_cool, 0.0f, 0.50f, 64f, 0.83f),
    ArtSticker(R.drawable.technet_sticker_phone, 1.0f, 0.62f, 68f, 0.46f, inLandscape = false),
    ArtSticker(R.drawable.technet_sticker_laptop, 0.0f, 0.75f, 70f, 0.69f, inLandscape = false),
    ArtSticker(R.drawable.technet_sticker_gamer, 1.0f, 0.87f, 66f, 0.22f, inLandscape = false),
    ArtSticker(R.drawable.technet_sticker_wink, 0.0f, 1.00f, 60f, 0.95f),
    ArtSticker(R.drawable.technet_sticker_pisophone, 1.0f, 1.00f, 66f, 0.40f, inLandscape = false),
)

/** Where the ambient coins fall (fractions of the width). */
private val COIN_X = floatArrayOf(.18f, .40f, .62f, .80f, .30f, .70f, .52f, .08f, .92f)

private const val POP_MS = 260
private const val INTRO_MS = 2_600
private const val LOOP_MS = 60_000
private const val SPARKLE_COUNT = 20
private const val BUBBLE_COUNT = 12
private const val TAU = 6.2831855f

/**
 * The backdrop of the lock screen, made to be seen through the semi-translucent panel: colour clouds drifting, light rays
 * turning, bubbles rising, ten stickers floating and wiggling, sparkles twinkling, coins falling and now and then a shooting
 * star. It is one canvas that takes no touches. Every movement repeats seamlessly (a whole number of cycles in [LOOP_MS]).
 * The movement runs only while [effects] allow ambient motion (a phone that is not hot, with animations on, and the owner's
 * video switch not off); otherwise the same picture stands still.
 */
@Composable
fun LockScreenArt(
    modifier: Modifier = Modifier,
    tint: BackdropTint = BackdropTint.Dagat,
    landscape: Boolean = false,
    effects: EffectsLevel = EffectsLevel.Full,
) {
    val stickerImages = STICKERS.map { ImageBitmap.imageResource(it.res) }
    val animate = effects.ambient
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
    val intro = remember { Animatable(if (effects.events) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (effects.events && intro.value < 1f) intro.animateTo(1f, tween(INTRO_MS, easing = LinearEasing))
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val t = time.value
        drawRect(Brush.verticalGradient(listOf(tint.top, tint.bottom)))
        drawClouds(tint, t)
        drawRays(landscape, t)
        if (animate) drawBubbles(t)
        // a light dim layer keeps the text readable; the stickers and sparkles are above it
        drawRect(
            Brush.verticalGradient(
                0f to tint.top.copy(alpha = 0.12f),
                0.5f to tint.top.copy(alpha = 0.22f),
                1f to tint.top.copy(alpha = 0.5f),
            ),
        )
        if (animate) drawCoins(t)
        val seconds = intro.value * INTRO_MS
        val bandTop = (if (landscape) BAND_TOP_LANDSCAPE else BAND_TOP_PORTRAIT).toPx()
        val bandBottom = size.height - (if (landscape) BAND_BOTTOM_LANDSCAPE else BAND_BOTTOM_PORTRAIT).toPx()
        STICKERS.forEachIndexed { i, s ->
            if (landscape && !s.inLandscape) return@forEachIndexed
            val appear = ((seconds - i * POP_MS) / POP_MS).coerceIn(0f, 1f)
            if (appear > 0f) drawSticker(stickerImages[i], s, i, t, appear, animate, bandTop..maxOf(bandTop, bandBottom))
        }
        if (animate) {
            drawSparkles(t)
            drawShootingStar(t)
        }
    }
}

/** Three big soft colour clouds drifting around the screen. */
private fun DrawScope.drawClouds(tint: BackdropTint, t: Float) {
    val colors = listOf(tint.cloudA, tint.cloudB, tint.cloudC)
    val radius = size.width * 0.95f
    colors.forEachIndexed { i, c ->
        val a = TAU * (t * 2f + i / 3f)
        val center = Offset(size.width * (0.5f + 0.42f * sin(a)), size.height * (0.34f + 0.26f * cos(a * 1.5f + i)))
        drawCircle(
            brush = Brush.radialGradient(listOf(c.copy(alpha = 0.34f), Color.Transparent), center = center, radius = radius),
            radius = radius,
            center = center,
        )
    }
}

/** Light rays turning slowly behind the mascot. */
private fun DrawScope.drawRays(landscape: Boolean, t: Float) {
    val center = if (landscape) Offset(size.width * 0.25f, size.height * 0.5f) else Offset(size.width * 0.5f, size.height * 0.22f)
    val reach = maxOf(size.width, size.height) * 1.3f
    val pulse = 0.07f + 0.03f * sin(TAU * t * 30f)
    rotate(degrees = 360f * t * 2f, pivot = center) {
        for (i in 0 until 12) {
            drawArc(
                color = Color.White.copy(alpha = pulse),
                startAngle = i * 30f,
                sweepAngle = 13f,
                useCenter = true,
                topLeft = Offset(center.x - reach, center.y - reach),
                size = Size(reach * 2, reach * 2),
            )
        }
    }
}

/** Soap bubbles rising from the bottom, swaying a little. */
private fun DrawScope.drawBubbles(t: Float) {
    for (i in 0 until BUBBLE_COUNT) {
        val rise = (t * (3 + i % 3) + i * 0.083f) % 1f
        val r = (6 + i % 5 * 3).dp.toPx()
        val x = size.width * ((i * 0.6180339f + 0.13f) % 1f) + 14.dp.toPx() * sin(TAU * (t * (6 + i % 4 * 3) + i * 0.2f))
        val y = size.height + r - rise * (size.height + 2 * r)
        val fade = when {
            rise < 0.1f -> rise / 0.1f
            rise > 0.85f -> (1f - rise) / 0.15f
            else -> 1f
        }
        val c = Offset(x, y)
        drawCircle(Color.White.copy(alpha = 0.10f * fade), radius = r, center = c)
        drawCircle(Color.White.copy(alpha = 0.35f * fade), radius = r, center = c, style = Stroke(width = 1.5.dp.toPx()))
        drawCircle(Color.White.copy(alpha = 0.5f * fade), radius = r * 0.22f, center = Offset(c.x - r * 0.35f, c.y - r * 0.35f))
    }
}

/** One sticker: it bobs, wiggles and now and then pops bigger; when the screen comes up it pops in. */
private fun DrawScope.drawSticker(
    image: ImageBitmap,
    s: ArtSticker,
    index: Int,
    t: Float,
    appear: Float,
    animate: Boolean,
    band: ClosedFloatingPointRange<Float>,
) {
    val base = s.sizeDp.dp.toPx()
    val aspect = image.width.toFloat() / image.height
    val w = base * aspect
    val bobCycles = 10 + (index % 4) * 5
    val wiggleCycles = 12 + (index % 3) * 6
    val popCycles = 15 + (index % 5) * 5
    val bob = if (animate) 12.dp.toPx() * sin(TAU * (t * bobCycles + s.phase)) else 0f
    val wiggle = if (animate) 10f * sin(TAU * (t * wiggleCycles + s.phase * 2f)) else 0f
    val breathe = if (animate) 1f + 0.09f * sin(TAU * (t * popCycles + s.phase)) else 1f
    val intro = if (appear < 0.7f) appear / 0.7f * 1.15f else 1.15f - 0.15f * ((appear - 0.7f) / 0.3f)
    val center = Offset(size.width * s.x, band.start + (band.endInclusive - band.start) * s.v + bob)
    withTransform({
        rotate(wiggle, center)
        scale(breathe * intro, breathe * intro, center)
    }) {
        drawImage(
            image = image,
            dstOffset = IntOffset((center.x - w / 2).toInt(), (center.y - base / 2).toInt()),
            dstSize = IntSize(w.toInt(), base.toInt()),
            alpha = 0.95f,
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
        drawOval(Color(0xFFFFC21A).copy(alpha = 0.85f * fade), topLeft, oval)
        drawOval(Color(0xFFB57A00).copy(alpha = 0.9f * fade), topLeft, oval, style = Stroke(width = 2.dp.toPx()))
        drawOval(
            Color.White.copy(alpha = 0.55f * fade),
            Offset(x - r * squish * 0.45f, y - r * 0.45f),
            Size(r * squish * 0.9f, r * 0.9f),
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

/** A shooting star crossing the upper screen every few seconds. */
private fun DrawScope.drawShootingStar(t: Float) {
    val phase = (t * 10f) % 1f // ten passes per loop: one every six seconds
    if (phase > 0.28f) return
    val p = phase / 0.28f
    val start = Offset(size.width * 1.05f, size.height * (0.06f + 0.25f * ((t * 10f).toInt() % 3) / 3f))
    val end = Offset(size.width * -0.1f, start.y + size.height * 0.22f)
    val head = Offset(start.x + (end.x - start.x) * p, start.y + (end.y - start.y) * p)
    val tail = Offset(head.x + size.width * 0.18f, head.y - size.height * 0.06f)
    val alpha = (if (p < 0.2f) p / 0.2f else 1f - (p - 0.2f) / 0.8f).coerceIn(0f, 1f)
    drawLine(
        brush = Brush.linearGradient(listOf(Color.White.copy(alpha = alpha), Color.Transparent), start = head, end = tail),
        start = head,
        end = tail,
        strokeWidth = 3.dp.toPx(),
        cap = StrokeCap.Round,
    )
    drawCircle(Color.White.copy(alpha = alpha), radius = 3.5.dp.toPx(), center = head)
}
