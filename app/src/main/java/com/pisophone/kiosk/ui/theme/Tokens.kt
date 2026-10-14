package com.pisophone.kiosk.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The TechNet design tokens for the PisoPhone screens (docs/design/GUI_SPEC_PISOPHONE_KIDS.md section 2). New and redesigned
 * screens read these instead of writing colour literals; the values are the same hex values the website, the router console
 * and the box dashboard use, so the product looks like one product.
 *
 * Meaning of the colours: blue = an action, teal = connected or done, gold = money, minutes and rewards (and "low time"),
 * red = a problem. A state is never shown by colour alone: it always has a word or an icon too.
 */
object TechNet {
    val bg = Color(0xFF050D22)
    val surface = Color(0xFF0C1A3A)
    val surfaceHi = Color(0xFF132552)

    val brand = Color(0xFF1D63D6) // filled buttons (white text on it: 5.5:1)
    val brandEdge = Color(0xFF0F3F94)
    val brandFg = Color(0xFF5B9BFF) // links, progress, icons (6.2:1 on surface)
    val haloFrom = Color(0xFF2F7BFF)
    val haloTo = Color(0xFF16C9A4)

    val teal = Color(0xFF16C9A4)
    val tealEdge = Color(0xFF0B8F75)
    val onTeal = Color(0xFF052E2A)

    val gold = Color(0xFFFFB81C)
    val goldEdge = Color(0xFFB07A00)
    val onGold = Color(0xFF0B1530)

    val text = Color(0xFFEEF3FF)
    val textMuted = Color(0xFF97A6C7)
    val line = Color(0xFF1F3361) // decoration only
    val lineStrong = Color(0xFF6B82B8) // outlines of controls (4.5:1)

    val ok = Color(0xFF4BE3B4)
    val warn = Color(0xFFFFB81C)
    val danger = Color(0xFFFF5A5F)
    val dangerText = Color(0xFFFF8A8A)
    val dangerEdge = Color(0xFFB3262B)

    // dark fills behind a warning, an error or a success note (text on them stays text / dangerText / ok)
    val dangerSurface = Color(0xFF3A1216)
    val warnSurface = Color(0xFF3A2A08)
    val okSurface = Color(0xFF0B3B33)
}
