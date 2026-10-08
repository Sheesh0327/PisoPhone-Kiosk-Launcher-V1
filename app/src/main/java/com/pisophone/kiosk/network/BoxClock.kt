package com.pisophone.kiosk.network

import kotlin.math.abs

/**
 * The time this phone signs its requests with.
 *
 * The box accepts a signed request only when its timestamp is near the box's own clock (a replay window), and the box has
 * no clock of its own: it takes its time from the phones. A phone whose date is wrong (a new phone on a Wi-Fi without
 * internet has never set its clock) would be refused as "stale" even with the right secret, and could drag the box off for the
 * others. So every answer from the box carries `server_time_ms`, and the phone signs with its own clock corrected by the
 * difference. All phones then agree with the box, whatever their own clocks say. Nothing else uses this time: sessions are
 * counted on the phone's monotonic clock.
 */
object BoxClock {
    /** The box's clock must look like a real date (after 2017) to be believed. */
    private const val MIN_PLAUSIBLE_MS = 1_500_000_000_000L

    /** A smaller difference is network delay, not a wrong clock: keep what is learned. */
    private const val TOLERANCE_MS = 2_000L

    @Volatile
    private var offsetMs = 0L

    fun nowMs(): Long = System.currentTimeMillis() + offsetMs

    fun offsetMs(): Long = offsetMs

    /** Learns the box's time. Returns true when the correction changed. */
    fun learn(serverTimeMs: Long, phoneNowMs: Long = System.currentTimeMillis()): Boolean {
        if (serverTimeMs < MIN_PLAUSIBLE_MS) return false
        val newOffset = serverTimeMs - phoneNowMs
        if (abs(newOffset - offsetMs) <= TOLERANCE_MS) return false
        offsetMs = newOffset
        return true
    }

    /** Reads `server_time_ms` from any answer of the box (a body that is not JSON, or has none, is ignored). */
    fun learnFromBody(body: String, phoneNowMs: Long = System.currentTimeMillis()): Boolean {
        if (body.isBlank() || !body.contains("server_time_ms")) return false
        val serverTimeMs = SERVER_TIME_FIELD.find(body)?.groupValues?.get(1)?.toLongOrNull() ?: return false
        return learn(serverTimeMs, phoneNowMs)
    }

    private val SERVER_TIME_FIELD = Regex(""""server_time_ms"\s*:\s*(\d{1,19})""")

    /** For tests. */
    fun reset() {
        offsetMs = 0L
    }
}
