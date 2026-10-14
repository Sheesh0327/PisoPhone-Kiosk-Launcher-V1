package com.pisophone.kiosk.overlay.ui

import androidx.compose.ui.graphics.Color
import com.pisophone.kiosk.ui.theme.TechNet

/** How urgent the remaining time looks on the pill: calm, running low (5 min), nearly out (1 min). */
enum class TimeLevel(val color: Color?) {
    OK(null),
    LOW(TechNet.gold),
    CRITICAL(TechNet.danger),
    ;

    companion object {
        const val LOW_SECONDS = 300
        const val CRITICAL_SECONDS = 60

        /** [seconds] of 0 means no session, which is never urgent. */
        fun of(seconds: Int): TimeLevel = when {
            seconds <= 0 -> OK
            seconds <= CRITICAL_SECONDS -> CRITICAL
            seconds <= LOW_SECONDS -> LOW
            else -> OK
        }
    }
}

/** Text colour of a time level: calm = text, running low = gold, nearly out = the readable red. */
fun TimeLevel.textColor(): Color = when (this) {
    TimeLevel.OK -> TechNet.text
    TimeLevel.LOW -> TechNet.gold
    TimeLevel.CRITICAL -> TechNet.dangerText
}

/** Colour of borders, bars and fills of a time level. */
fun TimeLevel.accentColor(): Color = when (this) {
    TimeLevel.OK -> TechNet.brandFg
    TimeLevel.LOW -> TechNet.gold
    TimeLevel.CRITICAL -> TechNet.danger
}
