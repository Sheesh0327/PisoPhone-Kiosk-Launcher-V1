package com.pisophone.kiosk.service

/** The hourly health line in the log (AO-08): measures how fast `payment_receipts` grows. It only measures; nothing is pruned. */
object HealthSnapshot {
    const val INTERVAL_MS = 60L * 60L * 1000L

    /** True at the first call and then once an hour. */
    fun isDue(lastMs: Long, nowMs: Long): Boolean = lastMs <= 0L || nowMs - lastMs >= INTERVAL_MS || nowMs < lastMs

    fun line(receiptCount: Int): String = "Health snapshot: payment_receipts=$receiptCount"

    /** Logs the line when it is due and returns the time to remember; otherwise returns [lastMs] unchanged. */
    fun logIfDue(lastMs: Long, receiptCount: () -> Int, log: (String) -> Unit = { android.util.Log.i("HealthSnapshot", it) }): Long {
        val nowMs = System.currentTimeMillis()
        if (!isDue(lastMs, nowMs)) return lastMs
        log(line(receiptCount()))
        return nowMs
    }
}
