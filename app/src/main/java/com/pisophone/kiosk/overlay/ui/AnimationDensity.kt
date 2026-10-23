package com.pisophone.kiosk.overlay.ui

import com.pisophone.kiosk.ui.video.VideoBackgroundPolicy

/**
 * How busy the shop wants the lock screen's background to be (feature 012). The box stores one of four words and the phone
 * draws accordingly. It is a ceiling the owner lowers: it never raises the screen past what [AmbientBudget] allows for the
 * phone's temperature and battery, and it governs ambient motion only, never the feedback that a coin counted.
 */
enum class AnimationDensity(val word: String) {
    /** The picture stands still: clouds, rays, stickers and bubbles do not move. */
    Off("off"),

    /** 4 stickers, no bubbles, no twinkle, a slow cloud drift only. */
    Low("low"),

    /** Today's screen exactly: 10 stickers wiggling, 12 bubbles, 20 sparkles, turning rays, the 60 s loop. */
    Normal("normal"),

    /** Today's counts (never more) with a quicker drift and ray turn: a 40 s loop. */
    High("high"),
    ;

    companion object {
        /** The level for the word the box sent; a missing or unknown word is [Normal], today's screen. */
        fun of(word: String?): AnimationDensity = values().firstOrNull { it.word == word?.trim()?.lowercase() } ?: Normal
    }
}

/**
 * What the lock screen's art may draw and move right now: the owner's [AnimationDensity] under the thermal and battery ladder
 * (`GUI_SPEC` section 10). The ladder wins at every level: MODERATE heat, power save, a low draining battery, animations
 * switched off or a screen that is not in front hold the picture still ([EffectsLevel.Events] and below), and SEVERE heat draws
 * no art at all ([EffectsLevel.Flat]), whatever the density.
 *
 * Nothing here touches the coin rain, the sparkle on a coin, the "time added" pop-over or the spoken confirmation: those are
 * gated by [EffectsLevel.events], which has no density input, so even `off` shows a customer that their coin counted.
 */
data class AmbientBudget(
    /** False when the art is not drawn at all (the phone is running hot). */
    val art: Boolean,
    /** How many of the ten stickers are drawn. */
    val stickers: Int,
    /** The stickers bob, wiggle and breathe. */
    val stickersMove: Boolean,
    val bubbles: Int,
    val sparkles: Int,
    /** The ambient coins falling behind the panel. */
    val fallingCoins: Boolean,
    val shootingStar: Boolean,
    val cloudsDrift: Boolean,
    val raysTurn: Boolean,
    /** One whole loop of the ambient motion; a shorter loop is a quicker drift, and every movement still repeats seamlessly. */
    val loopMs: Int,
) {
    val anythingMoves: Boolean get() = stickersMove || bubbles > 0 || sparkles > 0 || fallingCoins || shootingStar || cloudsDrift || raysTurn

    /** The ambient particles on screen: bubbles, sparkles and the falling coins. */
    val ambientParticles: Int get() = bubbles + sparkles + if (fallingCoins) FALLING_COINS else 0

    companion object {
        /** `GUI_SPEC` section 5: one burst of sparkles is at most 16 particles. No density raises it. */
        const val BURST_PARTICLE_CAP = 16

        /** The ambient coins in the art today. */
        const val FALLING_COINS = 9

        private const val ALL_STICKERS = 10
        private const val LOW_STICKERS = 4
        private const val NORMAL_BUBBLES = 12
        private const val NORMAL_SPARKLES = 20
        private const val NORMAL_LOOP_MS = 60_000
        private const val HIGH_LOOP_MS = 40_000
        private const val LOW_LOOP_MS = 120_000

        private val FLAT = AmbientBudget(false, 0, false, 0, 0, false, false, false, false, NORMAL_LOOP_MS)

        /** The whole ladder for one phone state, pure so it is tested without a phone. [batteryPercent] is -1 when unknown. */
        fun of(
            density: AnimationDensity,
            thermalStatus: Int,
            videoSwitchOn: Boolean,
            lowRamDevice: Boolean,
            powerSaveMode: Boolean,
            batteryPercent: Int,
            charging: Boolean,
            animationsOn: Boolean,
            screenInFront: Boolean,
        ): AmbientBudget {
            val motionAllowed = VideoBackgroundPolicy.shouldPlay(
                enabled = videoSwitchOn,
                lowRamDevice = lowRamDevice,
                powerSaveMode = powerSaveMode,
                batteryPercent = batteryPercent,
                charging = charging,
                screenInFront = screenInFront,
                covered = false,
            )
            return of(density, EffectsLevel.of(thermalStatus, motionAllowed, animationsOff = !animationsOn))
        }

        /** The same, from the effects level the lock screen already works out ([rememberEffectsLevel]). */
        fun of(density: AnimationDensity, effects: EffectsLevel): AmbientBudget {
            if (!effects.art) return FLAT
            val stickers = if (density == AnimationDensity.Low) LOW_STICKERS else ALL_STICKERS
            // a hot, flat-battery or animation-free phone shows the same picture standing still, whatever the density
            if (!effects.ambient || density == AnimationDensity.Off) {
                return AmbientBudget(true, stickers, false, 0, 0, false, false, false, false, NORMAL_LOOP_MS)
            }
            return when (density) {
                AnimationDensity.Low -> AmbientBudget(true, stickers, false, 0, 0, false, false, true, false, LOW_LOOP_MS)
                AnimationDensity.High -> AmbientBudget(true, stickers, true, NORMAL_BUBBLES, NORMAL_SPARKLES, true, true, true, true, HIGH_LOOP_MS)
                else -> AmbientBudget(true, stickers, true, NORMAL_BUBBLES, NORMAL_SPARKLES, true, true, true, true, NORMAL_LOOP_MS)
            }
        }
    }
}
