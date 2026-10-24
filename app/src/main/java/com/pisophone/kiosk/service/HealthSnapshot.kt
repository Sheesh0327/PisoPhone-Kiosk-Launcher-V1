package com.pisophone.kiosk.service

/** The hourly health line in the log (AO-08): measures how fast `payment_receipts` grows. It only measures; nothing is pruned. */
object HealthSnapshot {
    const val INTERVAL_MS = 60L * 60L * 1000L

    /** True at the first call and then once an hour. */
    fun isDue(lastMs: Long, nowMs: Long): Boolean = lastMs <= 0L || nowMs - lastMs >= INTERVAL_MS || nowMs < lastMs

    fun line(receiptCount: Int): String = "Health snapshot: payment_receipts=$receiptCount"
}
