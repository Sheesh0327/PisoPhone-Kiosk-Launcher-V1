// FW-01: reboot, OTA, factory reset and the reset pin must not restart the box while a coin may be in flight or a payment
// is still only in RAM. Only a restart with an IDLE slot, no counted-but-unhandled pulses and nothing unpersisted is safe.
#include <cstdio>
#include "../include/RestartGuard.h"

static int failures = 0;
#define CHECK(cond)                                                                                                    \
    do {                                                                                                               \
        if (!(cond)) {                                                                                                 \
            std::printf("FAIL line %d: %s\n", __LINE__, #cond);                                                        \
            failures++;                                                                                                \
        }                                                                                                              \
    } while (0)

int main() {
    CHECK(mayRestart(false, true, 0));   // idle, nothing counted, everything persisted
    CHECK(!mayRestart(true, true, 0));   // a payment only in RAM
    CHECK(!mayRestart(false, false, 0)); // slot armed or draining: a coin may be about to drop
    CHECK(!mayRestart(false, true, 1));  // a pulse counted but not yet turned into a payment
    CHECK(!mayRestart(false, true, 3));
    CHECK(!mayRestart(true, false, 2));

    // the reset pin: a safe moment is taken as soon as the 5 s hold is done
    CHECK(!resetPinMayRestart(true, 4999));
    CHECK(resetPinMayRestart(true, 5000));
    // an unsafe moment waits (the slot drains), but a stuck box can still be reset after a further 30 s
    CHECK(!resetPinMayRestart(false, 5000));
    CHECK(!resetPinMayRestart(false, 34999));
    CHECK(resetPinMayRestart(false, 35000));

    if (failures) {
        std::printf("%d failure(s)\n", failures);
        return 1;
    }
    std::printf("restart_guard_test OK\n");
    return 0;
}
