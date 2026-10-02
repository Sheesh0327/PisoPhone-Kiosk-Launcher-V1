#include "../include/TxId.h"

#include <cstdio>
#include <set>
#include <string>

static int failures = 0, checks = 0;
#define CHECK(c)                                                                                                       \
    do {                                                                                                               \
        checks++;                                                                                                      \
        if (!(c)) {                                                                                                    \
            failures++;                                                                                                \
            printf("FAIL line %d: %s\n", __LINE__, #c);                                                                \
        }                                                                                                              \
    } while (0)

static uint32_t rngState = 7;
static uint32_t rnd() {
    rngState = rngState * 1664525u + 1013904223u;
    return rngState;
}

int main() {
    CHECK(txid::make("tx-", 1, 1, 0xABCDEF01) == "tx-b1-1-abcdef01");
    CHECK(txid::make("gw-", 255, 42, 0) == "gw-bff-42-00000000");

    // many reboots, many coins per boot: every id is distinct, even with a constant "random" salt
    std::set<std::string> seen;
    size_t made = 0;
    for (uint32_t boot = 1; boot <= 300; boot++) {
        for (uint32_t seq = 1; seq <= 100; seq++) {
            seen.insert(txid::make("tx-", boot, seq, 0));
            made++;
        }
    }
    CHECK(seen.size() == made);

    // after a factory reset the boot counter restarts, so only the salt tells ids apart: distinct in practice
    std::set<std::string> afterReset;
    for (int run = 0; run < 2; run++)
        for (uint32_t seq = 1; seq <= 5000; seq++)
            afterReset.insert(txid::make("tx-", 1, seq, rnd()));
    CHECK(afterReset.size() == 10000);

    // long enough for the largest values and short enough for the 64-byte record field
    std::string longest = txid::make("tx-adj-", 0xFFFFFFFFu, 0xFFFFFFFFu, 0xFFFFFFFFu);
    CHECK(longest.size() < 63);

    printf("tx_id_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
