package com.pisophone.kiosk.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme =
    darkColorScheme(
        primary = Primary,
        onPrimary = OnPrimary,
        primaryContainer = SurfaceVariant,
        onPrimaryContainer = Primary,
        secondary = Secondary,
        onSecondary = OnSecondary,
        background = Background,
        onBackground = OnBackground,
        surface = Surface,
        onSurface = OnSurface,
        surfaceVariant = SurfaceVariant,
        onSurfaceVariant = OnSurfaceVariant,
        outline = Outline,
        outlineVariant = OutlineVariant,
        tertiary = Tertiary,
        onTertiary = OnTertiary,
    )

@Composable
fun PisoPhoneLauncherTheme(content: @Composable () -> Unit) {
    // Always the dark look: the kiosk has no light theme and no dynamic colours.
    MaterialTheme(colorScheme = DarkColorScheme, typography = Typography, content = content)
}
