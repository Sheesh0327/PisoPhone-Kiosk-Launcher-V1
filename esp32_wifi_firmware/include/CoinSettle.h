#ifndef COIN_SETTLE_H
#define COIN_SETTLE_H

// Settling time after the relay powers the coin acceptor.
// Switching the relay on can emit a stray pulse. Pulses are ignored for this window, and any
// pulse train that started inside the window is suppressed until silence.
#ifndef PISO_COIN_SETTLE_MS
#define PISO_COIN_SETTLE_MS 500UL
#endif

// Wrap-safe: is `now` still before `until`? (until == 0 means no window)
inline bool coinSettleActive(unsigned long now, unsigned long until) {
    return until != 0 && (long)(until - now) > 0;
}

// Did a pulse that arrived at `pulseMs` fall inside the window?
inline bool coinSettleCovers(unsigned long pulseMs, unsigned long until) {
    return until != 0 && (long)(until - pulseMs) > 0;
}

inline unsigned long coinSettleRemainingMs(unsigned long now, unsigned long until) {
    return coinSettleActive(now, until) ? (unsigned long)((long)(until - now)) : 0UL;
}

// Keep ignoring until the train has been silent for `interPulseMs`.
inline unsigned long coinSettleExtend(unsigned long until, unsigned long lastPulseMs, unsigned long interPulseMs) {
    unsigned long tail = lastPulseMs + interPulseMs;
    return (long)(tail - until) > 0 ? tail : until;
}

#endif // COIN_SETTLE_H
