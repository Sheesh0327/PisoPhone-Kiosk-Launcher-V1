#ifndef IDLE_COIN_H
#define IDLE_COIN_H

// Pulses that arrived while no phone owns the coin slot (FW-06). The coin may be real money that nothing is waiting for, so it
// is written to the orphan payments list where the operator will see it.

// Counted before the counters are zeroed (the old log line printed only the new ones, after zeroing the rest).
inline int idleCoinPulses(int newPulses, int accumulatedPulses) {
    return newPulses + accumulatedPulses;
}

// A stray pulse train at an idle slot writes flash, so at most one record every 5 s. lastMs is 0 until the first record.
// Unsigned subtraction keeps the elapsed time right when the millisecond counter wraps.
inline bool mayRecordIdleCoin(unsigned long lastMs, unsigned long nowMs) {
    return lastMs == 0 || nowMs - lastMs >= 5000UL;
}

#endif
