package com.pisophone.kiosk.overlay.ui

import java.util.Locale

// Money and minutes as the lock screen says them: "₱5 = 30 min", "₱10 = 1 hr" (pure functions, unit-tested).

/** "30 min" under an hour, then "1 hr", "1 hr 30 min", "2 hr". */
fun formatMinutes(minutes: Int): String {
    val m = minutes.coerceAtLeast(0)
    val hours = m / 60
    val rest = m % 60
    return when {
        hours == 0 -> "$rest min"
        rest == 0 -> "$hours hr"
        else -> "$hours hr $rest min"
    }
}

/** "₱5" for a whole amount, "₱2.50" otherwise. */
fun formatPesos(amount: Double): String =
    if (amount % 1.0 == 0.0) "₱${amount.toLong()}" else String.format(Locale.US, "₱%.2f", amount)

/** "₱5 = 30 min" */
fun formatRate(pricePerCoin: Double, minutesPerCoin: Int): String = "${formatPesos(pricePerCoin)} = ${formatMinutes(minutesPerCoin)}"

/** The rows of the rate card: one, two and four coins at the box's own rate (the rate is linear, so these are exact). */
fun rateRows(pricePerCoin: Double, minutesPerCoin: Int, rows: Int): List<String> =
    listOf(1, 2, 4).take(rows.coerceIn(1, 3)).map { coins -> formatRate(pricePerCoin * coins, minutesPerCoin * coins) }

/** The countdown as MM:SS, and H:MM:SS from one hour on. */
fun formatClock(totalSeconds: Int): String {
    val t = totalSeconds.coerceAtLeast(0)
    val h = t / 3600
    val m = t % 3600 / 60
    val sec = t % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%02d:%02d", m, sec)
}

/** "+30 min" for time that was just added: seconds under a minute say "+N sec". */
fun formatAdded(seconds: Int): String =
    if (seconds < 60) "+${seconds.coerceAtLeast(0)} sec" else "+" + formatMinutes((seconds + 30) / 60)
