#include "../include/PushBackoff.h"

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
    pushbackoff::Table t;
    CHECK(!t.shouldSkip("10.0.0.51", 1000));
    t.failed("10.0.0.51", 1000);
    CHECK(!t.shouldSkip("10.0.0.51", 1001)); // one failure is not enough
    t.failed("10.0.0.51", 2000);
    CHECK(t.shouldSkip("10.0.0.51", 2001));  // two in a row: skipped
    CHECK(!t.shouldSkip("10.0.0.52", 2001)); // another phone is not
    CHECK(t.shouldSkip("10.0.0.51", 31999));
    CHECK(!t.shouldSkip("10.0.0.51", 32000)); // 30 s after the last failure it is tried again
    t.succeeded("10.0.0.51");
    CHECK(!t.shouldSkip("10.0.0.51", 2002)); // a success clears it
    // after the skip ran out, one more failure starts a new count (it is tried once, not skipped at once)
    t.failed("10.0.0.51", 100000);
    t.failed("10.0.0.51", 100001);
    CHECK(t.shouldSkip("10.0.0.51", 100002));
    t.failed("10.0.0.51", 140000);
    CHECK(!t.shouldSkip("10.0.0.51", 140001));
    // more phones than table slots: the oldest entry makes room, nothing crashes, the newest are remembered
    for (int i = 0; i < 20; i++) {
        char ip[24];
        snprintf(ip, sizeof(ip), "10.0.1.%d", i);
        t.failed(ip, 200000 + i);
        t.failed(ip, 200000 + i);
    }
    CHECK(t.shouldSkip("10.0.1.19", 200100));
    CHECK(!t.shouldSkip("10.0.1.0", 200100));
    printf("%d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
