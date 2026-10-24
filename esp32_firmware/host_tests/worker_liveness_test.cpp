#include "../include/HealthPolicy.h"
#include "../include/WorkerLiveness.h"

#include <cstdio>
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

int main() {
    liveness::Watch w(60000);
    // an idle worker (nothing waiting) is never stalled, however long the counter stays the same
    CHECK(!w.stalled(0, 5, false));
    CHECK(!w.stalled(10UL * 60000, 5, false));
    // work waits and the counter moves: fine
    CHECK(!w.stalled(10UL * 60000 + 10000, 6, true));
    CHECK(!w.stalled(10UL * 60000 + 20000, 7, true));
    // work waits and the counter does not move: stalled only after the full 60 s
    CHECK(!w.stalled(10UL * 60000 + 30000, 7, true));
    CHECK(!w.stalled(10UL * 60000 + 79000, 7, true));
    CHECK(w.stalled(10UL * 60000 + 80000, 7, true));
    CHECK(w.stalled(10UL * 60000 + 90000, 7, true));
    // it recovers the moment the counter moves, or the queue empties
    CHECK(!w.stalled(10UL * 60000 + 100000, 8, true));
    CHECK(!w.stalled(10UL * 60000 + 100001, 8, false));
    CHECK(!w.stalled(10UL * 60000 + 200000, 8, false));
    // the first look never reports a stall (no history yet), even with work waiting
    liveness::Watch fresh(60000);
    CHECK(!fresh.stalled(123456, 0, true));
    // millis() wrap-around: 50 s before the wrap to 20 s after is 70 s
    liveness::Watch wrap(60000);
    unsigned long before = (unsigned long)-50000L;
    CHECK(!wrap.stalled(before, 1, true));
    CHECK(!wrap.stalled(before + 40000, 1, true));
    CHECK(wrap.stalled(before + 70000, 1, true));

    // a stalled worker restarts the box only through the same guard as every other restart
    CHECK(health::workerStallAction(true, true) == health::Action::RebootWorkerStall);
    CHECK(health::workerStallAction(true, false) == health::Action::None); // a coin is in flight: wait
    CHECK(health::workerStallAction(false, true) == health::Action::None);
    CHECK(std::string(health::actionName(health::Action::RebootWorkerStall)) == "worker-stall");

    printf("%d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
