// A coin transaction is shown only to the phone it belongs to, on a signed request, until acknowledged.
// Regression: the phone currently holding the coin slot used to see everyone's leftover transactions and credited them.
#include <cstdio>
#include <string>
#include "../include/CoinTxVisibility.h"

static int failures = 0;
#define CHECK(cond)                                                                                                    \
    do {                                                                                                               \
        if (!(cond)) {                                                                                                 \
            std::printf("FAIL line %d: %s\n", __LINE__, #cond);                                                        \
            failures++;                                                                                                \
        }                                                                                                              \
    } while (0)

int main() {
    const std::string phone1 = "ANDROID-AAAA", phone2 = "ANDROID-BBBB", none = "";
    CHECK(coinTxVisibleTo(phone1, phone1, true, false));   // the owner, signed, not yet acknowledged
    CHECK(!coinTxVisibleTo(phone1, phone2, true, false));  // the next customer's phone must not see it
    CHECK(!coinTxVisibleTo(phone1, phone1, false, false)); // unsigned: nobody (the id is easy to guess)
    CHECK(!coinTxVisibleTo(phone1, phone1, true, true));   // already acknowledged: gone
    CHECK(!coinTxVisibleTo(phone1, none, true, false));    // no device id in the request
    CHECK(!coinTxVisibleTo(none, none, true, false));      // a transaction without an owner belongs to nobody
    std::printf("coin_tx_visibility: %s\n", failures ? "FAILED" : "OK");
    return failures ? 1 : 0;
}
