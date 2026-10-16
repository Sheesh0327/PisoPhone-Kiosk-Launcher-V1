package com.pisophone.kiosk.service

/**
 * Decides, once per second, what the phone says about the time left, so that every announcement happens exactly once and none is
 * skipped when a tick is late: "5 minutes", "3 minutes", "1 minute", "10 seconds", then 5, 4, 3, 2, 1 one by one.
 * The old code compared the remaining seconds with a number, so a late tick silently skipped the warning for it.
 */
class TimeAnnouncer {
    /** What to do at this tick. */
    sealed class Action {
        /** Say a sentence ("5 minutes time remaining"). */
        data class Warning(val text: String) : Action()

        /** Say one number of the final countdown. */
        data class Digit(val number: Int) : Action()

        /** Time was added during the final countdown: stop it. */
        object CancelCountdown : Action()
    }

    private var last = Int.MAX_VALUE
    private var inCountdown = false

    /** Call with the seconds left at each tick; returns what to say (at most one thing). */
    fun onTick(remaining: Int): Action? {
        val previous = last
        last = remaining
        // the first tick only sets the baseline (a session restored at 200 s must not announce "5 minutes")
        if (previous == Int.MAX_VALUE) return null
        if (remaining > previous) {
            // time was added: nothing is due, and a started countdown is over
            return if (inCountdown && remaining > COUNTDOWN_FROM) {
                inCountdown = false
                Action.CancelCountdown
            } else {
                null
            }
        }
        if (remaining <= 0) {
            inCountdown = false
            return null
        }
        if (remaining <= COUNTDOWN_FROM) {
            // only the number that matches the clock now: a late tick says the current number, never an old one
            if (remaining < previous) {
                inCountdown = true
                return Action.Digit(remaining)
            }
            return null
        }
        // the warnings: the one that was crossed since the last tick (the lowest, if a late tick crossed two)
        val crossed = WARNINGS.filter { (at, _) -> previous > at && remaining <= at }
        return crossed.minByOrNull { it.first }?.let { Action.Warning(it.second) }
    }

    companion object {
        const val COUNTDOWN_FROM = 5
        private val WARNINGS = listOf(
            300 to "5 minutes time remaining",
            180 to "3 minutes time remaining",
            60 to "1 minute time remaining",
            10 to "10 seconds time remaining",
        )
    }
}
