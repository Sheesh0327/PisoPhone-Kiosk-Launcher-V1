package com.pisophone.kiosk.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The colour sets a shop owner can pick for the lock screen and the home screen (feature 013). They are compiled into the
 * signed app; the coin box only sends the [id]. A palette changes the backdrop (two colours, three soft clouds), one accent and
 * the colour of the maker's credit. It never changes money gold, warning red or success teal, the text colour or the panel.
 * `BrandPaletteContrastUnitTest` checks every palette, so a palette that would hide the text does not build.
 */
class BrandPalette(
    val id: String,
    /** Top and bottom of the backdrop gradient. */
    val top: Color,
    val bottom: Color,
    val cloudA: Color,
    val cloudB: Color,
    val cloudC: Color,
    /** The one brand colour: the header title and the marquee. Also an icon colour, so 3:1 on the backdrop at least. */
    val accent: Color,
    /** The colour of "by TechNet Solutions". Chosen per palette so it is readable (4.5:1) on both backdrop colours. */
    val credit: Color,
) {
    companion object {
        const val DEFAULT_ID = "technet"

        // technet, tropiko and ginto are the three backdrops the phones have always had (BackdropTint), colour for colour.
        val technet = BrandPalette(
            "technet",
            Color(0xFF050D22),
            Color(0xFF0A1B45),
            Color(0xFF2F7BFF),
            Color(0xFF16C9A4),
            Color(0xFFFFB81C),
            accent = Color(0xFF16C9A4),
            credit = Color(0xFF97A6C7),
        )
        val dagat = BrandPalette(
            "dagat",
            Color(0xFF061A2E),
            Color(0xFF0B3557),
            Color(0xFF38BDF8),
            Color(0xFF2F7BFF),
            Color(0xFF16C9A4),
            accent = Color(0xFF38BDF8),
            credit = Color(0xFFA9C4DC),
        )
        val tropiko = BrandPalette(
            "tropiko",
            Color(0xFF04161A),
            Color(0xFF063A3A),
            Color(0xFF16C9A4),
            Color(0xFF2F7BFF),
            Color(0xFFFF6FB5),
            accent = Color(0xFF16C9A4),
            credit = Color(0xFF9FC7C4),
        )
        val ginto = BrandPalette(
            "ginto",
            Color(0xFF0B1530),
            Color(0xFF2A2210),
            Color(0xFFFFB81C),
            Color(0xFF2F7BFF),
            Color(0xFF16C9A4),
            accent = Color(0xFFFFB81C),
            credit = Color(0xFFC9B88A),
        )
        val rosas = BrandPalette(
            "rosas",
            Color(0xFF2A0A1C),
            Color(0xFF4A1431),
            Color(0xFFFF6FB5),
            Color(0xFFB45BFF),
            Color(0xFFFFB81C),
            accent = Color(0xFFFF6FB5),
            credit = Color(0xFFD9A9C4),
        )
        val berde = BrandPalette(
            "berde",
            Color(0xFF04190F),
            Color(0xFF0B3A1E),
            Color(0xFF4ADE80),
            Color(0xFF16C9A4),
            Color(0xFFFFB81C),
            accent = Color(0xFF4ADE80),
            credit = Color(0xFFA3CDB0),
        )

        /** Every built-in palette, the default first. The box's admin page offers exactly these ids. */
        val all: List<BrandPalette> = listOf(technet, dagat, tropiko, ginto, rosas, berde)

        /** The palette with this id; an id nobody knows (an old or newer box, a typo) gives [technet], today's look. */
        fun of(id: String?): BrandPalette = all.firstOrNull { it.id == id } ?: technet

        /**
         * The look of a phone whose shop never chose a palette: the child's palette button on the lock screen still cycles the
         * three backdrops it always cycled (an old stored number maps with a modulo, as `BackdropTint.of` does).
         */
        fun forTheme(index: Int): BrandPalette = listOf(technet, tropiko, ginto)[Math.floorMod(index, 3)]
    }
}
