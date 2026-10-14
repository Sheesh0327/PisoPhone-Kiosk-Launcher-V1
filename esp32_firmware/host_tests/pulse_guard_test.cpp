// Fake-pulse protection: the pure rules (include/PulseGuard.h).
#include <cstdio>
#include <cstring>

#include "../include/PulseGuard.h"

static int failures = 0;
#define CHECK(cond, msg)                                                                                               \
    do {                                                                                                               \
        if (!(cond)) {                                                                                                 \
            std::printf("FAIL: %s\n", msg);                                                                            \
            failures++;                                                                                                \
        }                                                                                                              \
    } while (0)

int main() {
    // the limit is kept sane whatever is typed
    CHECK(pulseMinMsClamp(12) == 12, "a normal limit is kept");
    CHECK(pulseMinMsClamp(0) == PULSE_MIN_MS_LOWEST, "zero is raised");
    CHECK(pulseMinMsClamp(-5) == PULSE_MIN_MS_LOWEST, "negative is raised");
    CHECK(pulseMinMsClamp(1000) == PULSE_MIN_MS_HIGHEST, "a huge value is lowered");
    CHECK(PULSE_MIN_MS_DEFAULT >= PULSE_MIN_MS_LOWEST && PULSE_MIN_MS_DEFAULT <= PULSE_MIN_MS_HIGHEST,
          "the default is inside the limits");

    // real or not: a spark of microseconds is not, a 20 ms pulse is
    CHECK(!pulseIsReal(50, 12000), "50 microseconds is a spark");
    CHECK(!pulseIsReal(3000, 12000), "3 ms is noise");
    CHECK(!pulseIsReal(11999, 12000), "just under the limit is not real");
    CHECK(pulseIsReal(12000, 12000), "exactly the limit is real");
    CHECK(pulseIsReal(20000, 12000), "a 20 ms pulse is real");
    CHECK(pulseIsReal(60000, 12000), "a 60 ms pulse is real");

    // the histogram edges
    CHECK(pulseWidthBucket(0) == 0 && pulseWidthBucket(1999) == 0, "under 2 ms");
    CHECK(pulseWidthBucket(2000) == 1 && pulseWidthBucket(7999) == 1, "2 to 8 ms");
    CHECK(pulseWidthBucket(8000) == 2 && pulseWidthBucket(14999) == 2, "8 to 15 ms");
    CHECK(pulseWidthBucket(15000) == 3 && pulseWidthBucket(29999) == 3, "15 to 30 ms");
    CHECK(pulseWidthBucket(30000) == 4 && pulseWidthBucket(79999) == 4, "30 to 80 ms");
    CHECK(pulseWidthBucket(80000) == 5 && pulseWidthBucket(4000000000u) == 5, "80 ms and longer");

    // a run of real coins and some noise
    PulseStats s;
    std::memset(&s, 0, sizeof s);
    CHECK(pulseStatsAvgUs(s) == 0, "no pulses, no average");
    for (int i = 0; i < 5; i++)
        pulseStatsAdd(s, 20000 + i * 500, 12000); // five coins of about 21 ms
    pulseStatsAdd(s, 80, 12000);                  // a spark
    pulseStatsAdd(s, 4000, 12000);                // a ringing spike
    CHECK(s.n == 7, "seven pulses measured");
    CHECK(s.shortN == 2, "two of them are short");
    CHECK(s.minUs == 80, "the shortest is the spark");
    CHECK(s.maxUs == 22000, "the longest is the last coin");
    CHECK(s.bucket[0] == 1 && s.bucket[1] == 1 && s.bucket[3] == 5,
          "the buckets say: one spark, one spike, five coins");
    CHECK(pulseStatsAvgUs(s) == (uint32_t)((20000 + 20500 + 21000 + 21500 + 22000 + 80 + 4000) / 7), "the average");

    // the first pulse sets the minimum, whatever it is
    PulseStats t;
    std::memset(&t, 0, sizeof t);
    pulseStatsAdd(t, 30000, 12000);
    CHECK(t.minUs == 30000 && t.maxUs == 30000, "one pulse is both the shortest and the longest");

    if (failures == 0) std::printf("pulse_guard_test: all passed\n");
    return failures == 0 ? 0 : 1;
}
