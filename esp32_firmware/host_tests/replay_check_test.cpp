#include "../include/ReplayCheck.h"

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

using namespace replaycheck;

int main() {
    const uint64_t M = 1790000000000ULL; // the box's master clock

    // an ordinary phone
    CHECK(accepts(M, M, 0));
    CHECK(accepts(M + 1000, M, M));
    CHECK(accepts(M - 10000, M, M));          // 10 s older than its newest: inside the jitter
    CHECK(!accepts(M - 60000, M, M));         // a minute older than its newest: a replay
    CHECK(!accepts(M - WINDOW_MS - 1, M, 0)); // outside the master window
    CHECK(!accepts(M + WINDOW_MS + 1, M, 0));
    CHECK(!accepts(0, M, 0));
    CHECK(accepts(M - WINDOW_MS - 1, 0, 0)); // no master clock yet: anything goes

    // a phone that once talked with a clock a day ahead, and now signs with the box's time
    const uint64_t day = 24ULL * 3600 * 1000;
    CHECK(storedIsBogus(M, M + day));
    CHECK(accepts(M, M, M + day));        // used to be refused for good
    CHECK(nextNonce(M, M, M + day) == M); // and its stored value is replaced
    CHECK(!accepts(M - 60000, M, M));     // replay protection is back on after that

    // a stored value just ahead of the master clock is not bogus (the master clock lags the newest phone a little)
    CHECK(!storedIsBogus(M, M + 1000));
    CHECK(!accepts(M - 60000, M, M + 1000));
    CHECK(nextNonce(M + 2000, M, M + 1000) == M + 2000);
    CHECK(nextNonce(M, M, M + 1000) == M + 1000); // never goes back while it is believable

    // without a master clock nothing is bogus
    CHECK(!storedIsBogus(0, M + day));

    printf("replay_check: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
