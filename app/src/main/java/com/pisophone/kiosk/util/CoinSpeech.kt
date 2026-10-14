package com.pisophone.kiosk.util

/** Wording for the spoken coin confirmation. */
object CoinSpeech {
    fun confirmation(pesos: Int, seconds: Int): String {
        val coin = if (pesos == 1) "1 peso" else "$pesos pesos"
        return "$coin coin, ${duration(seconds)}"
    }

    fun duration(seconds: Int): String {
        val minutes = seconds / 60
        val rest = seconds % 60
        return when {
            minutes == 0 -> "$rest seconds"
            rest == 0 -> if (minutes == 1) "1 minute" else "$minutes minutes"
            else -> "$minutes minutes $rest seconds"
        }
    }
}
