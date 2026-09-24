package com.pisophone.kiosk.overlay.ui

import androidx.compose.ui.graphics.Color

data class OverlayTheme(
    val name: String,
    val background: Color,
    val primary: Color,
    val onPrimary: Color,
    val surface: Color,
    val border: Color,
    val secondary: Color
)

val KIOSK_OVERLAY_THEMES = listOf(
    OverlayTheme(
        name = "PISOPHONE OBSIDIAN",
        background = Color(0xFF060B14),
        primary = Color(0xFF10B981),
        onPrimary = Color(0xFF020617),
        surface = Color(0xFF0F172A),
        border = Color(0xFF1E293B),
        secondary = Color(0xFF34D399)
    ),
    OverlayTheme(
        name = "ULTRA VIOLET",
        background = Color(0xFF0F061E),
        primary = Color(0xFFB026FF),
        onPrimary = Color(0xFFFFFFFF),
        surface = Color(0xFF221140),
        border = Color(0xFFB026FF),
        secondary = Color(0xFFFFB800)
    ),
    OverlayTheme(
        name = "MATRIX LIME",
        background = Color(0xFF04120B),
        primary = Color(0xFF00FF88),
        onPrimary = Color(0xFF000000),
        surface = Color(0xFF0C2B1D),
        border = Color(0xFF00FF88),
        secondary = Color(0xFF00F5D4)
    ),
    OverlayTheme(
        name = "SOLAR FLARE",
        background = Color(0xFF140804),
        primary = Color(0xFFFF6600),
        onPrimary = Color(0xFF000000),
        surface = Color(0xFF2A140B),
        border = Color(0xFFFF6600),
        secondary = Color(0xFFFFD600)
    ),
    OverlayTheme(
        name = "CRIMSON NOVA",
        background = Color(0xFF120509),
        primary = Color(0xFFFF2A5F),
        onPrimary = Color(0xFFFFFFFF),
        surface = Color(0xFF2C111C),
        border = Color(0xFFFF2A5F),
        secondary = Color(0xFFFF6488)
    ),
    OverlayTheme(
        name = "ELECTRIC SUNSET",
        background = Color(0xFF130410),
        primary = Color(0xFFFF007F),
        onPrimary = Color(0xFFFFFFFF),
        surface = Color(0xFF2D1027),
        border = Color(0xFFFF007F),
        secondary = Color(0xFFFF66B2)
    ),
    OverlayTheme(
        name = "ARCTIC FROST",
        background = Color(0xFF060D17),
        primary = Color(0xFF38BDF8),
        onPrimary = Color(0xFF000000),
        surface = Color(0xFF16273B),
        border = Color(0xFF38BDF8),
        secondary = Color(0xFF7DD3FC)
    ),
    OverlayTheme(
        name = "NEON MATRIX",
        background = Color(0xFF040E07),
        primary = Color(0xFF00FF66),
        onPrimary = Color(0xFF000000),
        surface = Color(0xFF0F2A16),
        border = Color(0xFF00FF66),
        secondary = Color(0xFF66FF99)
    )
)
