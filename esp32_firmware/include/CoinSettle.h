#ifndef COIN_SETTLE_H
#define COIN_SETTLE_H

// Settling time after the relay powers the coin acceptor.
//
// Switching the relay on can make the acceptor emit a stray pulse that would be counted as a coin. For this long after
// a new session arms the slot, pulses are ignored, and so is the rest of any pulse train that started inside the window
// (a half-counted coin is worse than a lost one). Clients are told when the slot is really ready (settle_ms /
// ready_in_ms) so a customer is never invited to insert a coin that would be ignored.
#ifndef PISO_COIN_SETTLE_MS
#define PISO_COIN_SETTLE_MS 1000UL
#endif

// Wrap-safe: is `now` still before `until`? (until == 0 means no window)
inline bool coinSettleActive(unsigned long now, unsigned long until) {
    return until != 0 && (long)(until - now) > 0;
}

inline unsigned long coinSettleRemainingMs(unsigned long now, unsigned long until) {
    return coinSettleActive(now, until) ? (unsigned long)((long)(until - now)) : 0UL;
}

// A pulse arrived at `lastPulseMs` while the window was open: keep ignoring until the train has been silent for
// `interPulseMs`, so the tail of a train that began in the window is not counted as a new, shorter coin.
inline unsigned long coinSettleExtend(unsigned long until, unsigned long lastPulseMs, unsigned long interPulseMs) {
    unsigned long tail = lastPulseMs + interPulseMs;
    return (long)(tail - until) > 0 ? tail : until;
}

#endif // COIN_SETTLE_H
