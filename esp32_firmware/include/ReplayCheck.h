#ifndef REPLAY_CHECK_H
#define REPLAY_CHECK_H

// The box's freshness rule for signed requests from phones, kept free of Arduino types so it is tested on a PC
// (host_tests/replay_check_test.cpp).
//
// A request is accepted when its timestamp is within WINDOW_MS of the box's master clock (once there is one), and is not
// older than the newest timestamp seen from the same phone (a captured request cannot be played again), allowing JITTER_MS.
//
// The "newest timestamp seen" is only meaningful while it is itself a believable time. A phone that talked to the box with
// a wrong clock (or before the box had a master clock) leaves a stored value far ahead of the master clock. Treating it as
// the newest timestamp would refuse that phone for good once its clock is right (it signs with the box's time now): so a
// stored value more than WINDOW_MS ahead of the master clock is ignored and replaced by the next accepted request.

#include <stdint.h>

namespace replaycheck {

static const uint64_t WINDOW_MS = 300000ULL;
static const uint64_t JITTER_MS = 30000ULL;

// The stored timestamp is from a wrong clock: it lies beyond anything the master clock could have produced.
inline bool storedIsBogus(uint64_t masterTs, uint64_t lastNonceTs) {
    return masterTs > WINDOW_MS && lastNonceTs > masterTs + WINDOW_MS;
}

inline bool accepts(uint64_t newTs, uint64_t masterTs, uint64_t lastNonceTs) {
    if (newTs == 0) return false;
    if (masterTs > WINDOW_MS && (newTs < masterTs - WINDOW_MS || newTs > masterTs + WINDOW_MS)) return false;
    if (lastNonceTs > JITTER_MS && !storedIsBogus(masterTs, lastNonceTs) && newTs + JITTER_MS < lastNonceTs)
        return false;
    return true;
}

// What to remember after an accepted request.
inline uint64_t nextNonce(uint64_t ts, uint64_t masterTs, uint64_t lastNonceTs) {
    return (ts > lastNonceTs || storedIsBogus(masterTs, lastNonceTs)) ? ts : lastNonceTs;
}

} // namespace replaycheck

#endif // REPLAY_CHECK_H
