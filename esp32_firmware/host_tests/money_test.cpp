#include "../include/Money.h"

#include <cstdio>
#include <cstring>

static int failures = 0;
#define CHECK(c)                                                                                                       \
    do {                                                                                                               \
        if (!(c)) {                                                                                                    \
            printf("FAIL line %d: %s\n", __LINE__, #c);                                                                \
            failures++;                                                                                                \
        }                                                                                                              \
    } while (0)

int main() {
    // 10,000 one-peso coins add up to exactly 10,000.00
    uint32_t total = 0;
    for (int i = 0; i < 10000; i++)
        total = money::saturatingAdd(total, money::centavosFromPulses(1));
    char buf[24];
    CHECK(strcmp(money::formatPesos(total, buf, sizeof(buf)), "10000.00") == 0);

    // A float total stops counting exactly above 2^24; integers do not.
    float f = 16777216.0f;
    CHECK(f + 1.0f == f);
    uint32_t big = 1677721600; // 16,777,216 pesos
    big = money::saturatingAdd(big, money::centavosFromPulses(1));
    CHECK(strcmp(money::formatPesos(big, buf, sizeof(buf)), "16777217.00") == 0);

    CHECK(money::centavosFromPulses(0) == 0);
    CHECK(money::centavosFromPulses(-5) == 0);
    CHECK(money::centavosFromPulses(20) == 2000);
    CHECK(money::centavosFromPulses(2000000000) == UINT32_MAX);

    CHECK(money::saturatingAdd(UINT32_MAX - 10, 100) == UINT32_MAX);
    CHECK(money::saturatingAdd(UINT32_MAX, 1) == UINT32_MAX);

    CHECK(money::centavosFromLegacyPesos(1234.5f) == 123450);
    CHECK(money::centavosFromLegacyPesos(0.0f) == 0);
    CHECK(money::centavosFromLegacyPesos(-3.0f) == 0);
    CHECK(money::centavosFromLegacyPesos(0.0f / 0.0f) == 0);
    CHECK(money::centavosFromLegacyPesos(1e20f) == UINT32_MAX);

    CHECK(strcmp(money::formatPesos(5, buf, sizeof(buf)), "0.05") == 0);
    CHECK(strcmp(money::formatPesos(0, buf, sizeof(buf)), "0.00") == 0);

    if (failures == 0) printf("money_test: all passed\n");
    return failures ? 1 : 0;
}
