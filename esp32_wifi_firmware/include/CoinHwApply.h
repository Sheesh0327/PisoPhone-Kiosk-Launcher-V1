#ifndef COIN_HW_APPLY_H
#define COIN_HW_APPLY_H

// When to re-apply coin hardware settings (coin pin, pulse guard, pulse width).
// Only apply when one changed and the slot is IDLE.
enum class CoinHwApply { NOTHING, NOW, LATER };

inline CoinHwApply coinHwApplyDecision(bool pinChanged, bool guardChanged, bool minMsChanged, bool alreadyPending,
                                       bool slotIdle) {
    if (!(pinChanged || guardChanged || minMsChanged || alreadyPending)) return CoinHwApply::NOTHING;
    return slotIdle ? CoinHwApply::NOW : CoinHwApply::LATER;
}

#endif // COIN_HW_APPLY_H
