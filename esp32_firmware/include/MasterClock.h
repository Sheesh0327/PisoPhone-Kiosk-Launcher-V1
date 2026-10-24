#ifndef MASTER_CLOCK_H
#define MASTER_CLOCK_H

// Pure (no Arduino types) arithmetic of the box's master clock (host_tests/master_clock_test.cpp).
//
// The clock is the time a phone last reported plus the milliseconds that passed on the box since. On the ESP32 the 64-bit time and
// the 32-bit millis stamp are two writes, so a read that lands between them mixes an old time with a new stamp (a jump of minutes
// in the signature window). The firmware copies the pair inside a critical section and calls this on the copy.
#include <cstdint>

namespace masterclock {

struct Stamp {
    uint64_t ts = 0; // the phone's time in ms; 0 = never set
    uint32_t ms = 0; // millis() when it was set (32 bits, like millis() on the ESP32)
};

inline uint64_t now(const Stamp& s, uint32_t nowMs) {
    if (s.ts == 0) return 0;
    return s.ts + (uint64_t)(uint32_t)(nowMs - s.ms); // unsigned wrap-around of millis() is handled by the subtraction
}

} // namespace masterclock

#endif // MASTER_CLOCK_H
