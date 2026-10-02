#include "../include/HealthPolicy.h"

#include <cstdio>

static int failures = 0, checks = 0;
#define CHECK(c)                                                                                                       \
    do {                                                                                                               \
        checks++;                                                                                                      \
        if (!(c)) {                                                                                                    \
            failures++;                                                                                                \
            printf("FAIL line %d: %s\n", __LINE__, #c);                                                                \
        }                                                                                                              \
    } while (0)

using namespace health;
static const unsigned long MIN = 60000UL;
static const unsigned long DAY = 24UL * 3600 * 1000;
// 2026-01-05 03:30 in UTC+8 is 2026-01-04 19:30 UTC
static const uint64_t QUIET_EPOCH = 1767555000000ULL;

int main() {
    // healthy: never restarts, however long it runs, while it is not yet a week old
    {
        Monitor m;
        for (unsigned long t = 0; t < 6 * DAY; t += 10 * MIN)
            CHECK(m.evaluate(t, 120000, 60000, t, 0, true) == Action::None);
    }

    // low memory must persist for 5 minutes, then restarts only when it is safe
    {
        Monitor m;
        CHECK(m.evaluate(0, 10000, 9000, 0, 0, true) == Action::None);
        CHECK(m.lowMemoryNow());
        CHECK(m.evaluate(4 * MIN, 10000, 9000, 0, 0, true) == Action::None);
        CHECK(m.evaluate(5 * MIN, 10000, 9000, 0, 0, false) == Action::None); // coin session open: wait
        CHECK(m.evaluate(6 * MIN, 10000, 9000, 0, 0, true) == Action::RebootLowMemory);
    }

    // a dip that recovers resets the timer
    {
        Monitor m;
        m.evaluate(0, 10000, 9000, 0, 0, true);
        m.evaluate(3 * MIN, 90000, 50000, 0, 0, true);
        CHECK(!m.lowMemoryNow());
        m.evaluate(4 * MIN, 10000, 9000, 0, 0, true);
        CHECK(m.evaluate(8 * MIN, 10000, 9000, 0, 0, true) == Action::None); // only 4 min since the new dip
        CHECK(m.evaluate(9 * MIN, 10000, 9000, 0, 0, true) == Action::RebootLowMemory);
    }

    // fragmentation alone (plenty of memory but no big block) counts as low
    {
        Monitor m;
        m.evaluate(0, 90000, 4000, 0, 0, true);
        CHECK(m.evaluate(5 * MIN, 90000, 4000, 0, 0, true) == Action::RebootLowMemory);
    }

    // weekly maintenance: only after a week, only in the quiet hour, only when safe
    {
        Monitor m;
        CHECK(m.evaluate(0, 120000, 60000, 6 * DAY, QUIET_EPOCH, true) == Action::None);
        CHECK(m.evaluate(0, 120000, 60000, 8 * DAY, QUIET_EPOCH, true) == Action::RebootMaintenance);
        CHECK(m.evaluate(0, 120000, 60000, 8 * DAY, QUIET_EPOCH, false) == Action::None);
        CHECK(m.evaluate(0, 120000, 60000, 8 * DAY, QUIET_EPOCH + 6 * 3600000ULL, true) == Action::None); // 09:30
    }

    // no synced clock: wait two weeks instead of guessing the hour
    {
        Monitor m;
        CHECK(m.evaluate(0, 120000, 60000, 10 * DAY, 0, true) == Action::None);
        CHECK(m.evaluate(0, 120000, 60000, 15 * DAY, 0, true) == Action::RebootMaintenance);
    }

    CHECK(localHour(QUIET_EPOCH, 8) == 3);
    CHECK(localHour(0, 8) == 8);
    CHECK(localHour(0, -3) == 21);
    printf("health_policy_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
