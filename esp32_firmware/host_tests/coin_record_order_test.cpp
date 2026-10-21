// FW-03: a coin is recorded in one fixed order: queue the payment first, then revenue, then the LED. A power cut between
// two steps must never leave revenue counted for a payment the phone can not collect.
#include <cstdio>
#include <string>
#include "../include/CoinRecordOrder.h"

static int failures = 0;
#define CHECK(cond)                                                                                                    \
    do {                                                                                                               \
        if (!(cond)) {                                                                                                 \
            std::printf("FAIL line %d: %s\n", __LINE__, #cond);                                                        \
            failures++;                                                                                                \
        }                                                                                                              \
    } while (0)

int main() {
    std::string trace;
    bool retained = recordCoinInOrder(
        [&] {
            trace += "Q";
            return true;
        },
        [&] { trace += "R"; }, [&] { trace += "L"; });
    CHECK(trace == "QRL");
    CHECK(retained);

    // a queue that refuses the payment is reported, and the revenue and LED steps still follow (the coin is real money)
    trace.clear();
    retained = recordCoinInOrder(
        [&] {
            trace += "Q";
            return false;
        },
        [&] { trace += "R"; }, [&] { trace += "L"; });
    CHECK(trace == "QRL");
    CHECK(!retained);

    if (failures) {
        std::printf("%d failure(s)\n", failures);
        return 1;
    }
    std::printf("coin_record_order_test OK\n");
    return 0;
}
