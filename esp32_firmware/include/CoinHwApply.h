#ifndef COIN_HW_APPLY_H
#define COIN_HW_APPLY_H

// When to re-apply the coin hardware settings (coin pin, pulse guard, pulse width).
//
// Applying them re-attaches the coin interrupt and zeroes the pulse counter, so a coin that is still arriving would be lost.
// Do it only when one of the three changed, and only while the slot is IDLE. A change made while the slot is in use is
// kept and applied as soon as the slot is IDLE again.
enum class CoinHwApply { NOTHING, NOW, LATER };

inline CoinHwApply coinHwApplyDecision(bool pinChanged, bool guardChanged, bool minMsChanged, bool alreadyPending,
                                       bool slotIdle) {
    if (!(pinChanged || guardChanged || minMsChanged || alreadyPending)) return CoinHwApply::NOTHING;
    return slotIdle ? CoinHwApply::NOW : CoinHwApply::LATER;
}

#endif
