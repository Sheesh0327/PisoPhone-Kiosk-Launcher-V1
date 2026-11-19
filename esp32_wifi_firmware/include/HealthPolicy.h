#ifndef HEALTH_POLICY_H
#define HEALTH_POLICY_H

// Pure (no Arduino types) decision of when the box may restart itself for low heap or maintenance.

#include <cstdint>

namespace health {

struct Thresholds {
    uint32_t minFreeHeap = 15000;
    uint32_t minLargestBlock = 16000;
    unsigned long lowPersistMs = 5UL * 60000;
    unsigned long maintenanceUptimeMs = 7UL * 24 * 3600 * 1000;
    unsigned long unknownClockUptimeMs = 14UL * 24 * 3600 * 1000;
    int quietStartHour = 3;
    int quietEndHour = 5;
    int localUtcOffsetHours = 8;
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

inline int localHour(uint64_t epochMs, int utcOffsetHours) {
    int64_t hours = (int64_t)(epochMs / 3600000ULL) + utcOffsetHours;
    int h = (int)(hours % 24);
    return h < 0 ? h + 24 : h;
}

class Monitor {
public:
    explicit Monitor(const Thresholds& t = Thresholds()) : t_(t) {}

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
