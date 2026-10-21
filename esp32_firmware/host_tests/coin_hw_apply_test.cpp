// FW-02: saving Settings must not re-attach the coin interrupt (which zeroes the pulse counter) while a slot is in use.
// The coin pin, pulse guard and pulse width are applied only when one of them changed, and only when the slot is IDLE;
// otherwise the change waits until it is.
#include <cstdio>
#include "../include/CoinHwApply.h"

static int failures = 0;
#define CHECK(cond)                                                                                                    \
    do {                                                                                                               \
        if (!(cond)) {                                                                                                 \
            std::printf("FAIL line %d: %s\n", __LINE__, #cond);                                                        \
            failures++;                                                                                                \
        }                                                                                                              \
    } while (0)

int main() {
    // nothing changed: never touch the hardware, idle or not
    CHECK(coinHwApplyDecision(false, false, false, false, true) == CoinHwApply::NOTHING);
    CHECK(coinHwApplyDecision(false, false, false, false, false) == CoinHwApply::NOTHING);

    // each field on its own, slot idle: apply now
    CHECK(coinHwApplyDecision(true, false, false, false, true) == CoinHwApply::NOW);
    CHECK(coinHwApplyDecision(false, true, false, false, true) == CoinHwApply::NOW);
    CHECK(coinHwApplyDecision(false, false, true, false, true) == CoinHwApply::NOW);

    // slot armed, draining or otherwise not idle: a coin may be in flight, so wait
    CHECK(coinHwApplyDecision(true, false, false, false, false) == CoinHwApply::LATER);
    CHECK(coinHwApplyDecision(false, true, false, false, false) == CoinHwApply::LATER);
    CHECK(coinHwApplyDecision(false, false, true, false, false) == CoinHwApply::LATER);

    // a change that was deferred earlier is still owed on a later save that changes nothing
    CHECK(coinHwApplyDecision(false, false, false, true, false) == CoinHwApply::LATER);
    CHECK(coinHwApplyDecision(false, false, false, true, true) == CoinHwApply::NOW);

    if (failures) {
        std::printf("%d failure(s)\n", failures);
        return 1;
    }
    std::printf("coin_hw_apply_test OK\n");
    return 0;
}
