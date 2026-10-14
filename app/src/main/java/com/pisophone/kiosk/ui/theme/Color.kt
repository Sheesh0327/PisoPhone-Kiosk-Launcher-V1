package com.pisophone.kiosk.ui.theme

import androidx.compose.ui.graphics.Color

// TechNet colours (docs/design/GUI_SPEC_PISOPHONE_KIDS.md section 2): navy, one blue, teal for "connected", gold for money.
// Material's roles keep the same pattern as before (a light accent with dark text on it), so every screen that reads them
// keeps its contrast; Tokens.kt holds the full set for the redesigned screens.
val Background = Color(0xFF050D22)
val OnBackground = Color(0xFFEEF3FF)

val Primary = Color(0xFF5B9BFF) // brandFg: links, progress, filled accents
val OnPrimary = Color(0xFF04122B)

val Secondary = Color(0xFF16C9A4) // teal: connected, success
val OnSecondary = Color(0xFF052E2A)

val Surface = Color(0xFF0C1A3A)
val OnSurface = Color(0xFFEEF3FF)
val SurfaceVariant = Color(0xFF132552)
val OnSurfaceVariant = Color(0xFF97A6C7)

val Outline = Color(0xFF6B82B8)
val OutlineVariant = Color(0xFF1F3361)

val Tertiary = Color(0xFFFFB81C) // gold: money, minutes
val OnTertiary = Color(0xFF0B1530)

// Semantic tokens

val TextPrimary = Color(0xFFEEF3FF)
val TextSecondary = Color(0xFF97A6C7)
