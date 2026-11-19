#ifndef COIN_CREDIT_H
#define COIN_CREDIT_H

#include <climits>
#include <cstdint>

// Minutes per coin is 1..1440 (up to one day per coin).
inline int clampMinutesPerCoin(int minutes) {
    if (minutes < 1) return 1;
    if (minutes > 1440) return 1440;
    return minutes;
}

inline int coinCreditSeconds(int pulses, int minutesPerCoin) {
    if (pulses <= 0) return 0;
    int64_t seconds = (int64_t)pulses * clampMinutesPerCoin(minutesPerCoin) * 60;
    return seconds > INT_MAX ? INT_MAX : (int)seconds;
}

#endif // COIN_CREDIT_H
