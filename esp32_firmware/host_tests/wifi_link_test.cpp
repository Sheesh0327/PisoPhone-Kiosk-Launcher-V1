#include "../include/WifiLink.h"

#include <cstdio>
#include <cstring>

static int checks = 0, failures = 0;
#define CHECK(c)                                                                                                       \
    do {                                                                                                               \
        checks++;                                                                                                      \
        if (!(c)) {                                                                                                    \
            failures++;                                                                                                \
            printf("FAIL line %d: %s\n", __LINE__, #c);                                                                \
        }                                                                                                              \
    } while (0)
using namespace wifilink;

int main() {
    // a retry is due once the interval has passed, and the check survives millis() wrapping around
    CHECK(!retryDue(1000, 1000));
    CHECK(!retryDue(1000 + RETRY_INTERVAL_MS, 1000));
    CHECK(retryDue(1000 + RETRY_INTERVAL_MS + 1, 1000));
    CHECK(retryDue(10, 0xFFFFFFFFU - RETRY_INTERVAL_MS));
    CHECK(!retryDue(10, 0xFFFFFFFFU - 100));
    CHECK(RAPID_BLINK_MS < RETRY_INTERVAL_MS); // the LED turns to the slow "failed" blink before the next attempt

    // the reasons an installer meets
    CHECK(std::strcmp(reasonName(201), "NO_AP_FOUND") == 0);
    CHECK(std::strcmp(reasonName(202), "AUTH_FAIL") == 0);
    CHECK(std::strcmp(reasonName(15), "4WAY_HANDSHAKE_TIMEOUT") == 0);
    CHECK(std::strcmp(reasonName(250), "OTHER") == 0);
    CHECK(std::strstr(reasonHint(201), "not found") != nullptr);
    CHECK(std::strstr(reasonHint(202), "password") != nullptr);
    CHECK(std::strstr(reasonHint(15), "password") != nullptr);
    CHECK(std::strstr(reasonHint(203), "refused") != nullptr);
    CHECK(reasonHint(250)[0] == '\0');

    printf("%d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
