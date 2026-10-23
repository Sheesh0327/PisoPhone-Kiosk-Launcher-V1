package com.pisophone.kiosk.overlay.ui

import com.pisophone.kiosk.ui.video.VideoBackgroundPolicy

/**
 * When the shop name may scroll (feature 013). The marquee is the one moving thing the branding adds, so it follows the same
 * thermal and battery ladder as the rest of the screen (`GUI_SPEC` section 10): it scrolls only on a cool phone with the screen
 * in front, animations on, not in power save and not nearly flat; otherwise the name stands still, centred. Pure, so it is
 * tested without a phone, like [VideoBackgroundPolicy.shouldPlay].
 */
object MarqueeRule {
    /** Below this charge, and not charging, the marquee stops (the same number the videos use). */
    const val LOW_BATTERY_PERCENT = VideoBackgroundPolicy.LOW_BATTERY_PERCENT

    /** [batteryPercent] is -1 when unknown; a phone that reports no thermal status passes 0. */
    fun shouldScroll(
        density: AnimationDensity,
        thermalStatus: Int,
        powerSaveMode: Boolean,
        batteryPercent: Int,
        charging: Boolean,
        animationsOn: Boolean,
        screenInFront: Boolean,
    ): Boolean {
        // `off` means the picture stands still, the shop name included (the maker's credit is the one exception, MakerCredit.kt)
        if (density == AnimationDensity.Off) return false
        if (thermalStatus >= EffectsLevel.THERMAL_MODERATE) return false
        if (powerSaveMode || !animationsOn || !screenInFront) return false
        val lowAndDraining = batteryPercent in 0 until LOW_BATTERY_PERCENT && !charging
        return !lowAndDraining
    }
}
