package com.pisophone.kiosk.overlay.ui

import androidx.compose.ui.graphics.Color

/** How urgent the remaining time looks on the pill: calm, running low (5 min), nearly out (1 min). */
enum class TimeLevel(val color: Color?) {
    OK(null),
    LOW(Color(0xFFFFB800)),
    CRITICAL(Color(0xFFFF3333)),
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
