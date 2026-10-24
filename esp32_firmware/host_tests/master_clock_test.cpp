#include "../include/MasterClock.h"

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
    masterclock::Stamp s;
    CHECK(masterclock::now(s, 123456) == 0); // never set
    s.ts = 1790000000000ULL;
    s.ms = 1000;
    CHECK(masterclock::now(s, 1000) == 1790000000000ULL);
    CHECK(masterclock::now(s, 6000) == 1790000005000ULL);
    // millis() wraps every 49.7 days: the elapsed part must stay small and positive
    s.ms = 4294967000UL; // 296 ms before the wrap
    CHECK(masterclock::now(s, 4) == 1790000000000ULL + 300);
    CHECK(masterclock::now(s, 4294967000UL) == 1790000000000ULL);
    // it never goes backwards while millis only goes forward, and never mixes the halves of two stamps
    uint64_t prev = 0;
    for (unsigned long t = 5000; t < 5000 + 100000; t += 137) {
        uint64_t v = masterclock::now({1790000000000ULL, 5000}, t);
        CHECK(v >= prev);
        prev = v;
    }
    masterclock::Stamp a{1790000000000ULL, 100}, b{1790000900000ULL, 900000};
    // a copy of one whole stamp: reading A's time with B's millis (the torn read) is 14 minutes off; each whole stamp is consistent
    CHECK(masterclock::now(a, 900100) == 1790000900000ULL);
    CHECK(masterclock::now(b, 900100) == 1790000900100ULL);
    printf("%d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
