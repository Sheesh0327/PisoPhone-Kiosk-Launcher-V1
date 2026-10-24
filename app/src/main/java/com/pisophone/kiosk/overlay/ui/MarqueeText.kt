package com.pisophone.kiosk.overlay.ui

import com.pisophone.kiosk.util.ShopBranding

/**
 * The pure rules of the shop-name band (feature 023): what it says, how often the text is repeated so the band is never empty, and
 * its size. Kept out of the composable so they are tested without a phone, like [MarqueeRule].
 */
object MarqueeText {
    /** The text size and the band height the owner asked to be hard to miss (feature 013 had 14 sp). */
    const val TEXT_SP = 28
    const val BAND_MIN_HEIGHT_DP = 56

    /** About 60 dp a second, the speed the owner approved. */
    const val SPEED_DP_PER_S = 60

    /** The empty run between the end of one copy of the name and the start of the next. */
    const val GAP_DP = 48

    /** Enough characters that the running text is wider than the widest tablet, so even a short name keeps moving. */
    const val MIN_CHARS = 96
    private const val SEPARATOR = "   ✦   "

    /** The shop name, or the plain default "PisoPhone" for no name or only blanks. */
    fun textFor(name: String?): String = name?.trim()?.takeIf { it.isNotEmpty() } ?: ShopBranding.DEFAULT_TITLE

    /** How many copies of the text run side by side: at least two, and enough to pass [MIN_CHARS]. */
    fun repeatsFor(text: String): Int = maxOf(2, (MIN_CHARS + text.length - 1) / text.length.coerceAtLeast(1))

    /** The moving line: the text and a star between the copies. */
    fun runningLine(text: String): String = List(repeatsFor(text)) { text }.joinToString(SEPARATOR) + SEPARATOR
}
