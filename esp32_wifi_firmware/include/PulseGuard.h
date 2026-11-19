#ifndef PULSE_GUARD_H
#define PULSE_GUARD_H

// Protection of the coin pulse input against electrical noise or sparks.
// Pure functions tested on a PC (host_tests/coin_pulse_test.cpp) and inlined for IRAM ISR execution.

#include <stdint.h>

#ifndef PULSE_GUARD_INLINE
#define PULSE_GUARD_INLINE inline __attribute__((always_inline))
#endif

constexpr int PULSE_MIN_MS_DEFAULT = 12;
constexpr int PULSE_MIN_MS_LOWEST = 5;
constexpr int PULSE_MIN_MS_HIGHEST = 40;
constexpr int PULSE_MAX_MS = 180;
constexpr uint32_t PULSE_MAX_US = (uint32_t)PULSE_MAX_MS * 1000UL;
constexpr int PULSE_BUCKETS = 6;

PULSE_GUARD_INLINE int pulseMinMsClamp(int ms) {
    if (ms < PULSE_MIN_MS_LOWEST) return PULSE_MIN_MS_LOWEST;
    if (ms > PULSE_MIN_MS_HIGHEST) return PULSE_MIN_MS_HIGHEST;
    return ms;
}

PULSE_GUARD_INLINE bool pulseIsReal(uint32_t widthUs, uint32_t minUs) {
    return widthUs >= minUs && widthUs <= PULSE_MAX_US;
}

// Histogram buckets: <2 ms, 2-8 ms, 8-15 ms, 15-30 ms, 30-80 ms, >=80 ms.
PULSE_GUARD_INLINE int pulseWidthBucket(uint32_t widthUs) {
    if (widthUs < 2000) return 0;
    if (widthUs < 8000) return 1;
    if (widthUs < 15000) return 2;
    if (widthUs < 30000) return 3;
    if (widthUs < 80000) return 4;
    return 5;
}

struct PulseStats {
    uint32_t n;
    uint32_t shortN;
    uint32_t longN;
    uint32_t minUs;
    uint32_t maxUs;
    uint64_t sumUs;
    uint32_t bucket[PULSE_BUCKETS];
};

PULSE_GUARD_INLINE void pulseStatsAdd(PulseStats& s, uint32_t widthUs, uint32_t minRealUs) {
    if (s.n == 0 || widthUs < s.minUs) s.minUs = widthUs;
    if (widthUs > s.maxUs) s.maxUs = widthUs;
    s.n++;
    s.sumUs += widthUs;
    if (widthUs < minRealUs)
        s.shortN++;
    else if (widthUs > PULSE_MAX_US)
        s.longN++;
    s.bucket[pulseWidthBucket(widthUs)]++;
}

PULSE_GUARD_INLINE uint32_t pulseStatsAvgUs(const PulseStats& s) {
    return s.n == 0 ? 0 : (uint32_t)(s.sumUs / s.n);
}

#endif // PULSE_GUARD_H
