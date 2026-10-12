#include "../include/OtaConfirm.h"

#include <cstdio>
#include <cstring>

static int failures = 0, checks = 0;
#define CHECK(c)                                                                                                       \
    do {                                                                                                               \
        checks++;                                                                                                      \
        if (!(c)) {                                                                                                    \
            failures++;                                                                                                \
            printf("FAIL line %d: %s\n", __LINE__, #c);                                                                \
        }                                                                                                              \
    } while (0)

using namespace otaconfirm;

static Health h(bool storage, bool wifiRequired, bool wifiUp) {
    Health x;
    x.storageReady = storage;
    x.wifiRequired = wifiRequired;
    x.wifiUp = wifiUp;
    return x;
}

int main() {
    // never confirmed before it has run for the minimum time, however healthy it looks (a crash seconds in must roll back)
    CHECK(decide(0, h(true, true, true)) == Action::Wait);
    CHECK(decide(MIN_STABLE_MS - 1, h(true, true, true)) == Action::Wait);
    // and never rolled back before then either, even when unhealthy: Wi-Fi takes a while to come up
    CHECK(decide(5000, h(false, true, false)) == Action::Wait);

    // healthy once the minimum time has passed: confirmed
    CHECK(decide(MIN_STABLE_MS, h(true, true, true)) == Action::Confirm);
    CHECK(decide(MIN_STABLE_MS, h(true, false, false)) ==
          Action::Confirm); // no Wi-Fi was needed (update came by another route)
    CHECK(decide(GIVE_UP_MS * 10, h(true, true, true)) == Action::Confirm); // late is fine, as long as it got there

    // unhealthy after the minimum time: wait for it, then give up and roll back
    CHECK(decide(MIN_STABLE_MS, h(true, true, false)) == Action::Wait);
    CHECK(decide(GIVE_UP_MS - 1, h(true, true, false)) == Action::Wait);
    CHECK(decide(GIVE_UP_MS, h(true, true, false)) == Action::Rollback);
    CHECK(decide(GIVE_UP_MS, h(false, false, false)) == Action::Rollback);
    CHECK(decide(GIVE_UP_MS, h(false, true, true)) ==
          Action::Rollback); // Wi-Fi alone does not make a broken storage healthy

    // it recovers just before giving up: still confirmed
    CHECK(decide(GIVE_UP_MS - 1000, h(true, true, true)) == Action::Confirm);

    // the reason named in the log
    CHECK(strcmp(firstFailure(h(false, true, false)), "payment-storage") == 0);
    CHECK(strcmp(firstFailure(h(true, true, false)), "wifi") == 0);
    CHECK(strcmp(firstFailure(h(true, false, false)), "") == 0);
    CHECK(strcmp(firstFailure(h(true, true, true)), "") == 0);

    // the constants the field behaviour depends on
    CHECK(MIN_STABLE_MS == 60000UL && GIVE_UP_MS == 300000UL && MIN_STABLE_MS < GIVE_UP_MS);

    printf("ota_confirm_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
