#ifndef COIN_CREDIT_H
#define COIN_CREDIT_H

#include <climits>
#include <cstdint>

// Minutes per peso is 1..1440 (one day per peso; the phone profiles use the same limit, PhoneProfiles.h).
inline int clampMinutesPerCoin(int minutes) {
    if (minutes < 1) return 1;
    if (minutes > 1440) return 1440;
    return minutes;
}

// The seconds of phone time for `pulses` coins at `minutesPerCoin`. Computed in 64 bits and clamped to INT_MAX, so a large
// count or setting can not wrap into a negative or tiny credit. No pulses, no credit.
inline int coinCreditSeconds(int pulses, int minutesPerCoin) {
    if (pulses <= 0) return 0;
    int64_t seconds = (int64_t)pulses * clampMinutesPerCoin(minutesPerCoin) * 60;
    return seconds > INT_MAX ? INT_MAX : (int)seconds;
}

#endif
