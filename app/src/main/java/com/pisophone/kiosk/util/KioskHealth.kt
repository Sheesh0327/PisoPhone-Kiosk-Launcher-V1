package com.pisophone.kiosk.util

/**
 * The few facts an operator needs at a glance when a kiosk misbehaves on site: is the phone on Wi-Fi, can it reach
 * its coin box (and since when), and did the last coin go through. Updated by the ESP32 coordinator, read by the
 * health block at the top of the admin vault's diagnostics. [summarize] is pure so the rules are unit tested.
 */
object KioskHealth {
    /** How long the box may be unreachable before the summary turns red (a short drop is normal while it restarts). */
    const val BOX_OFFLINE_RED_MS = 2 * 60_000L

    /** Below this the Wi-Fi signal is weak enough to explain dropped heartbeats. */
    const val WEAK_SIGNAL_DBM = -75

    enum class Level { OK, WARN, PROBLEM }

    data class Snapshot(
        val startedAtMs: Long,
        /** null until the box was found or lost once in this run. */
        val boxOnline: Boolean?,
        val boxSinceMs: Long,
        val boxIp: String?,
        val lastFoundAtMs: Long,
        val lastCoinAtMs: Long,
        val lastCoinResult: String?,
    )

    /** What the phone's Wi-Fi looks like right now. [rssiDbm] is null when unknown. */
    data class Wifi(val address: String, val rssiDbm: Int?)

    data class Summary(val level: Level, val lines: List<String>)

    internal var clock: () -> Long = { System.currentTimeMillis() }

    private var startedAtMs = clock()
    private var boxOnline: Boolean? = null
    private var boxSinceMs = 0L
    private var boxIp: String? = null
    private var lastFoundAtMs = 0L
    private var lastCoinAtMs = 0L
    private var lastCoinResult: String? = null

    @Synchronized
    fun boxFound(ip: String) {
        boxIp = ip
        lastFoundAtMs = clock()
        boxLink(true)
    }

    @Synchronized
    fun boxLink(online: Boolean) {
        if (boxOnline != online) {
            boxOnline = online
            boxSinceMs = clock()
        }
    }

    @Synchronized
    fun coin(result: String) {
        lastCoinAtMs = clock()
        lastCoinResult = result
    }

    @Synchronized
    fun snapshot() = Snapshot(startedAtMs, boxOnline, boxSinceMs, boxIp, lastFoundAtMs, lastCoinAtMs, lastCoinResult)

    @Synchronized
    internal fun reset() {
        startedAtMs = clock()
        boxOnline = null
        boxSinceMs = 0L
        boxIp = null
        lastFoundAtMs = 0L
        lastCoinAtMs = 0L
        lastCoinResult = null
    }

    fun summarize(nowMs: Long, s: Snapshot, wifi: Wifi): Summary {
        var level = Level.OK
        fun raise(to: Level) {
            if (to.ordinal > level.ordinal) level = to
        }
        val lines = mutableListOf<String>()

        if (wifi.address.isBlank()) {
            raise(Level.PROBLEM)
            lines += "Wi-Fi: no address (not on the kiosk network)"
        } else {
            val signal = wifi.rssiDbm?.let { ", signal $it dBm" } ?: ""
            val weak = wifi.rssiDbm != null && wifi.rssiDbm < WEAK_SIGNAL_DBM
            if (weak) raise(Level.WARN)
            lines += "Wi-Fi: ${wifi.address}$signal" + if (weak) " (weak)" else ""
        }

        when (s.boxOnline) {
            true -> lines += "Box: online at ${s.boxIp ?: "?"} for ${ago(nowMs - s.boxSinceMs)}"
            false -> {
                val down = nowMs - s.boxSinceMs
                raise(if (down >= BOX_OFFLINE_RED_MS) Level.PROBLEM else Level.WARN)
                val last = if (s.lastFoundAtMs > 0) ", last found at ${s.boxIp ?: "?"} ${ago(nowMs - s.lastFoundAtMs)} ago" else ""
                lines += "Box: OFFLINE for ${ago(down)}$last"
            }
            null -> {
                val searching = nowMs - s.startedAtMs
                raise(if (searching >= BOX_OFFLINE_RED_MS) Level.PROBLEM else Level.WARN)
                lines += "Box: not found yet (searching for ${ago(searching)})"
            }
        }

        if (s.lastCoinAtMs > 0) {
            val result = s.lastCoinResult ?: "?"
            if (result != "APPLIED" && result != "ALREADY_APPLIED") raise(Level.WARN)
            lines += "Last coin: $result, ${ago(nowMs - s.lastCoinAtMs)} ago"
        } else {
            lines += "Last coin: none since the app started"
        }
        return Summary(level, lines)
    }

    /** "45s", "12m", "3h 5m", "2d 4h". */
    fun ago(ms: Long): String {
        val s = (ms.coerceAtLeast(0L)) / 1000
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            s < 86400 -> "${s / 3600}h ${(s % 3600) / 60}m"
            else -> "${s / 86400}d ${(s % 86400) / 3600}h"
        }
    }
}
