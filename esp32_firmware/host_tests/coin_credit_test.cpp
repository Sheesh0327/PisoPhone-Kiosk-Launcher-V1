// FW-05: minutes per coin is kept in 1..1440, and the seconds of credit for a coin are computed in 64 bits and clamped, so
// no setting and no pulse count can wrap into a negative or tiny credit.
#include <climits>
#include <cstdio>
#include "../include/CoinCredit.h"

static int failures = 0;
#define CHECK(cond)                                                                                                    \
    do {                                                                                                               \
        if (!(cond)) {                                                                                                 \
            std::printf("FAIL line %d: %s\n", __LINE__, #cond);                                                        \
            failures++;                                                                                                \
        }                                                                                                              \
    } while (0)

int main() {
    CHECK(clampMinutesPerCoin(0) == 1);
    CHECK(clampMinutesPerCoin(-7) == 1);
    CHECK(clampMinutesPerCoin(1) == 1);
    CHECK(clampMinutesPerCoin(30) == 30);
    CHECK(clampMinutesPerCoin(1440) == 1440);
    CHECK(clampMinutesPerCoin(1441) == 1440);
    CHECK(clampMinutesPerCoin(INT_MAX) == 1440);

    CHECK(coinCreditSeconds(1, 1) == 60);
    CHECK(coinCreditSeconds(5, 2) == 600);
    CHECK(coinCreditSeconds(20, 1440) == 20 * 1440 * 60); // 1,728,000
    CHECK(coinCreditSeconds(0, 10) == 0);
    CHECK(coinCreditSeconds(-3, 10) == 0);
    // 32-bit wrap: 100,000 pulses * 1,440 min * 60 is far past INT_MAX
    CHECK(coinCreditSeconds(100000, 1440) == INT_MAX);
    CHECK(coinCreditSeconds(INT_MAX, INT_MAX) == INT_MAX);
    // an out-of-range rate is clamped, not trusted
    CHECK(coinCreditSeconds(1, 0) == 60);
    CHECK(coinCreditSeconds(1, 1000000) == 1440 * 60);

    if (failures) {
        std::printf("%d failure(s)\n", failures);
        return 1;
    }
    std::printf("coin_credit_test OK\n");
    return 0;
}
