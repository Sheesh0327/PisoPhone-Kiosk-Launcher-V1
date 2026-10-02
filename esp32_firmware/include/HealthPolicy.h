#ifndef HEALTH_POLICY_H
#define HEALTH_POLICY_H

// Pure (no Arduino types) decision of when the box may restart itself (host_tests/health_policy_test.cpp).
//
// There is no unconditional daily reboot: it hid memory leaks and could cut a customer's coin session.
// The box restarts itself only when
//   - free memory (or the largest free block) has stayed low for several minutes, or
//   - it has been up a very long time and it is the quiet hour,
// and in both cases only while no coin session is open and no payment exists only in RAM.

#include <cstdint>

namespace health {

struct Thresholds {
    uint32_t minFreeHeap = 15000;             // bytes
    uint32_t minLargestBlock = 16000;         // bytes: heap this fragmented cannot serve a TLS handshake or a big page
    unsigned long lowPersistMs = 5UL * 60000; // how long memory must stay low before acting
    unsigned long maintenanceUptimeMs = 7UL * 24 * 3600 * 1000;
    unsigned long unknownClockUptimeMs = 14UL * 24 * 3600 * 1000; // without a synced clock the quiet hour is unknown
    int quietStartHour = 3;                                       // local hours, start inclusive, end exclusive
    int quietEndHour = 5;
    int localUtcOffsetHours = 8; // the Philippines
};

enum class Action { None, RebootLowMemory, RebootMaintenance };

inline const char* actionName(Action a) {
    switch (a) {
    case Action::RebootLowMemory:
        return "low-memory";
    case Action::RebootMaintenance:
        return "weekly-maintenance";
    default:
        return "none";
    }
}

// Local hour 0-23 from an epoch in milliseconds.
inline int localHour(uint64_t epochMs, int utcOffsetHours) {
    int64_t hours = (int64_t)(epochMs / 3600000ULL) + utcOffsetHours;
    int h = (int)(hours % 24);
    return h < 0 ? h + 24 : h;
}

class Monitor {
public:
    explicit Monitor(const Thresholds& t = Thresholds()) : t_(t) {}

    // Call every few seconds. `epochMs` is 0 when the clock has not been synced by a phone yet.
    Action evaluate(unsigned long nowMs, uint32_t freeHeap, uint32_t largestBlock, unsigned long uptimeMs,
                    uint64_t epochMs, bool safeToRestart) {
        bool low = freeHeap < t_.minFreeHeap || largestBlock < t_.minLargestBlock;
        if (!low) {
            lowActive_ = false;
        } else if (!lowActive_) {
            lowActive_ = true;
            lowSinceMs_ = nowMs;
        }

        if (!safeToRestart) return Action::None;
        if (lowActive_ && (unsigned long)(nowMs - lowSinceMs_) >= t_.lowPersistMs) return Action::RebootLowMemory;

        if (uptimeMs >= t_.maintenanceUptimeMs) {
            bool quiet;
            if (epochMs > 0) {
                int h = localHour(epochMs, t_.localUtcOffsetHours);
                quiet = h >= t_.quietStartHour && h < t_.quietEndHour;
            } else {
                quiet = uptimeMs >= t_.unknownClockUptimeMs;
            }
            if (quiet) return Action::RebootMaintenance;
        }
        return Action::None;
    }

    bool lowMemoryNow() const { return lowActive_; }

private:
    Thresholds t_;
    bool lowActive_ = false;
    unsigned long lowSinceMs_ = 0;
};

} // namespace health

#endif // HEALTH_POLICY_H
