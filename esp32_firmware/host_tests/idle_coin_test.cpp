// FW-06: a coin that arrives while no phone owns the slot is written to the orphan list, but a stray pulse train at an idle
// slot must not wear the flash: at most one record every 5 s.
#include <climits>
#include <cstdio>
#include "../include/IdleCoin.h"

static int failures = 0;
#define CHECK(cond)                                                                                                    \
    do {                                                                                                               \
        if (!(cond)) {                                                                                                 \
            std::printf("FAIL line %d: %s\n", __LINE__, #cond);                                                        \
            failures++;                                                                                                \
        }                                                                                                              \
    } while (0)

int main() {
    // Coins counted: what was just counted plus what was already accumulated, before the counters are zeroed.
    CHECK(idleCoinPulses(1, 0) == 1);
    CHECK(idleCoinPulses(2, 3) == 5);
    CHECK(idleCoinPulses(0, 0) == 0);

    // The first idle coin is always recorded.
    CHECK(mayRecordIdleCoin(0, 1234));
    // Then at most one record every 5 s.
    CHECK(!mayRecordIdleCoin(1000, 1000));
    CHECK(!mayRecordIdleCoin(1000, 5999));
    CHECK(mayRecordIdleCoin(1000, 6000));
    // The millisecond counter wraps after about 49 days: the elapsed time is still right.
    CHECK(!mayRecordIdleCoin(ULONG_MAX - 1000, 2000));
    CHECK(mayRecordIdleCoin(ULONG_MAX - 1000, 4000));

    if (failures) return 1;
    std::printf("idle_coin_test OK\n");
    return 0;
}
