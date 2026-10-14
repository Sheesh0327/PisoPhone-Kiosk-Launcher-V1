#ifndef PULSE_GUARD_H
#define PULSE_GUARD_H

// Protection of the coin pulse input against fake pulses (a lighter or piezo igniter held near the coin acceptor, loose wiring,
// electrical noise). Pure functions, so they are tested on a PC (host_tests/pulse_guard_test.cpp) and cannot stall the ISR.
//
// A real coin acceptor holds its output line low for a fixed time per pulse (20 ms or more); a spark or a noise spike pulls it low
// for microseconds up to a few milliseconds. So every pulse is MEASURED, and the owner can switch on the guard, which counts a pulse
// only when the line stayed low for at least the "shortest real pulse" the owner set. With the guard off, pulses are counted exactly
// as before and the measurements are only shown (a histogram), so the right setting can be read off real coins first.

#include <stdint.h>

#ifndef PULSE_GUARD_INLINE
// the ISR runs from IRAM: these must be inlined into it, never called from flash
#define PULSE_GUARD_INLINE inline __attribute__((always_inline))
#endif

constexpr int PULSE_MIN_MS_DEFAULT = 12;
constexpr int PULSE_MIN_MS_LOWEST = 5;
constexpr int PULSE_MIN_MS_HIGHEST = 40;
constexpr int PULSE_BUCKETS = 6;

// the shortest real pulse in milliseconds, kept within sane limits
PULSE_GUARD_INLINE int pulseMinMsClamp(int ms) {
    if (ms < PULSE_MIN_MS_LOWEST) return PULSE_MIN_MS_LOWEST;
    if (ms > PULSE_MIN_MS_HIGHEST) return PULSE_MIN_MS_HIGHEST;
    return ms;
}

// Is a pulse that held the line low for widthUs a real one?
PULSE_GUARD_INLINE bool pulseIsReal(uint32_t widthUs, uint32_t minUs) {
    return widthUs >= minUs;
}

// The histogram: <2 ms, 2-8 ms, 8-15 ms, 15-30 ms, 30-80 ms, 80 ms and longer. Real coins fall in one bucket; noise falls in the first three.
PULSE_GUARD_INLINE int pulseWidthBucket(uint32_t widthUs) {
    if (widthUs < 2000) return 0;
    if (widthUs < 8000) return 1;
    if (widthUs < 15000) return 2;
    if (widthUs < 30000) return 3;
    if (widthUs < 80000) return 4;
    return 5;
}

struct PulseStats {
    uint32_t n;      // pulses measured
    uint32_t shortN; // of those, shorter than the owner's limit
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
    if (!pulseIsReal(widthUs, minRealUs)) s.shortN++;
    s.bucket[pulseWidthBucket(widthUs)]++;
}

PULSE_GUARD_INLINE uint32_t pulseStatsAvgUs(const PulseStats& s) {
    return s.n == 0 ? 0 : (uint32_t)(s.sumUs / s.n);
}

#endif // PULSE_GUARD_H
