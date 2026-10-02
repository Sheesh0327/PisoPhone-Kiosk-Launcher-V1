package com.pisophone.kiosk.server

/**
 * Remembers the signatures of recent signed requests so a captured message cannot be sent again.
 * The box has no trustworthy clock, so a timestamp window is not enforced; each message is instead accepted
 * once. Payments are NOT passed through here: the box retries them unchanged until acknowledged, and they
 * are already idempotent by transaction id.
 */
class ReplayGuard(
    private val ttlMs: Long = 10 * 60_000L,
    private val maxEntries: Int = 1024,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val seen = LinkedHashMap<String, Long>()

    /** True the first time [signature] is seen within the window, false for every repeat. */
    @Synchronized
    fun firstSeen(signature: String): Boolean {
        val now = clock()
        val it = seen.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value > ttlMs) it.remove() else break // oldest first
        }
        if (seen.containsKey(signature)) return false
        if (seen.size >= maxEntries) seen.remove(seen.keys.first())
        seen[signature] = now
        return true
    }
}
