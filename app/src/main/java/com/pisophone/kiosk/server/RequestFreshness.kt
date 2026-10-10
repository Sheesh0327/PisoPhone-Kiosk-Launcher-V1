package com.pisophone.kiosk.server

import kotlin.math.abs

/** Where the replay state is kept so that it survives a restart of the app. The state is one opaque line of text. */
interface ReplayStateStore {
    fun load(): String

    fun save(state: String)
}

/**
 * Whether a signed message from the box is recent enough to be believed, and the memory that stops a captured one being played
 * again. This is the phone's side of the rule the box applies to the phone's own requests (firmware `ReplayCheck.h`). A message
 * is refused when
 *  - its timestamp is more than [WINDOW_MS] away from the box's clock, or
 *  - it is older than the newest message accepted before it, allowing [JITTER_MS].
 * The newest timestamp and the signatures of the last few accepted messages are kept in the [ReplayStateStore], so a message
 * captured on the network cannot be played again after the app restarted or after [ReplayGuard] has forgotten it (the server
 * feeds [recentSignatures] back into the guard when it starts).
 *
 * "The box's clock" is [com.pisophone.kiosk.network.BoxClock], the time the box itself reported. Until the phone has heard it
 * ([boxClockKnown]) there is nothing to compare with, so nothing is enforced and the signature cache alone protects: a box
 * that has not learned the time yet, or a phone that has just started, can never be locked out. A stored newest timestamp that
 * lies further ahead of the box's clock than any real message could (it came from a wrong clock) is ignored and replaced by the
 * next accepted message, so a clock mistake in the past cannot refuse the box for good.
 *
 * Payments do not use this: they are idempotent by transaction id and the box repeats them with their original timestamp.
 */
class RequestFreshness(
    private val boxNowMs: () -> Long,
    private val boxClockKnown: () -> Boolean,
    private val store: ReplayStateStore,
) {
    enum class Verdict {
        /** Within the window and not older than the newest message: believed. Call [accepted] once it has been acted on. */
        FRESH,

        /** Nothing to compare with (no box clock yet, or the box sent no time): left to the signature cache. */
        UNVERIFIED,

        OUTSIDE_WINDOW,
        OLDER_THAN_NEWEST,
    }

    companion object {
        /** How far a message's time may be from the box's clock (the phone learns the box's time every few seconds). */
        const val WINDOW_MS = 120_000L

        /** How much older than the newest accepted message a message may be (messages reach the phone slightly out of order). */
        const val JITTER_MS = 30_000L

        /** A copy of the box's message must be remembered at least as long as it can still pass the window: 2 x window. */
        const val MIN_REPLAY_MEMORY_MS = 2 * WINDOW_MS

        private const val MAX_RECENT = 32
        private const val FORMAT = "1"
    }

    private class Seen(val ts: Long, val sig: String)

    private var loaded = false
    private var newest = 0L
    private val recent = ArrayList<Seen>()

    @Synchronized
    fun check(ts: Long): Verdict {
        if (!boxClockKnown() || ts <= 0L) return Verdict.UNVERIFIED
        val now = boxNowMs()
        if (abs(ts - now) > WINDOW_MS) return Verdict.OUTSIDE_WINDOW
        ensureLoaded()
        if (newest > JITTER_MS && !isBogus(newest, now) && ts + JITTER_MS < newest) return Verdict.OLDER_THAN_NEWEST
        return Verdict.FRESH
    }

    /**
     * Remembers a message that [check] called [Verdict.FRESH] and that was then acted on: moves the newest timestamp forward
     * and keeps its [signature] (lower case) so it cannot be accepted a second time, even after a restart.
     */
    @Synchronized
    fun accepted(ts: Long, signature: String) {
        ensureLoaded()
        if (ts > newest || isBogus(newest, boxNowMs())) newest = ts
        recent.add(Seen(ts, signature))
        // Anything older than the newest by more than the jitter is refused by time alone, so it need not be remembered.
        recent.removeAll { it.ts + JITTER_MS < newest }
        while (recent.size > MAX_RECENT) recent.removeAt(0)
        save()
    }

    /** The signatures of the recently accepted messages (loaded from storage), to put back into the signature cache. */
    @Synchronized
    fun recentSignatures(): List<String> {
        ensureLoaded()
        return recent.map { it.sig }
    }

    private fun isBogus(stored: Long, now: Long) = stored > now + WINDOW_MS

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val parts = try {
            store.load().split(';')
        } catch (_: Exception) {
            return
        }
        if (parts.size < 2 || parts[0] != FORMAT) return // empty, from another format, or damaged: start clean
        val storedNewest = parts[1].toLongOrNull() ?: return
        val entries = ArrayList<Seen>()
        for (part in parts.drop(2)) {
            val comma = part.indexOf(',')
            val ts = if (comma > 0) part.substring(0, comma).toLongOrNull() else null
            val sig = if (comma > 0) part.substring(comma + 1) else ""
            if (ts == null || !isSignature(sig)) return // a damaged line is dropped as a whole, never half-trusted
            entries.add(Seen(ts, sig))
        }
        newest = storedNewest.coerceAtLeast(0L)
        recent.addAll(entries.takeLast(MAX_RECENT))
    }

    private fun isSignature(s: String) = s.length in 1..128 && s.all { it in '0'..'9' || it in 'a'..'f' }

    private fun save() {
        val state = buildString {
            append(FORMAT).append(';').append(newest)
            for (e in recent) append(';').append(e.ts).append(',').append(e.sig)
        }
        try {
            store.save(state)
        } catch (_: Exception) {
            // Not saving only weakens the protection after a restart; the message in hand was already judged.
        }
    }
}
