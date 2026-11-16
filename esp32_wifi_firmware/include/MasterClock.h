#ifndef MASTER_CLOCK_H
#define MASTER_CLOCK_H

// Pure (no Arduino types) monotonic master clock derived from router/SNTP timestamps.

#include <cstdint>

namespace masterclock {

struct Stamp {
    uint64_t ts = 0; // synced epoch in ms; 0 = unsynced
    uint32_t ms = 0; // millis() when ts was recorded
};

inline uint64_t now(const Stamp& s, uint32_t nowMs) {
    if (s.ts == 0) return 0;
    return s.ts + (uint64_t)(uint32_t)(nowMs - s.ms);
}

} // namespace masterclock

#endif // MASTER_CLOCK_H
