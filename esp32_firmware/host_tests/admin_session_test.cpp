// Host test for AdminSession.h (browser sessions after a correct admin password).
#include "../include/AdminSession.h"
#include <cstdio>
#include <cstring>

using namespace adminsession;
static int failures = 0, checks = 0;
#define CHECK(c)                                                                                                       \
    do {                                                                                                               \
        checks++;                                                                                                      \
        if (!(c)) {                                                                                                    \
            failures++;                                                                                                \
            printf("FAIL line %d: %s\n", __LINE__, #c);                                                                \
        }                                                                                                              \
    } while (0)

static std::string startWith(Sessions& s, uint8_t fill, unsigned long now) {
    uint8_t r[RANDOM_BYTES];
    memset(r, fill, sizeof(r));
    return s.start(r, now);
}

int main() {
    Sessions s;
    std::string a = startWith(s, 0xA1, 1000);
    CHECK(a.size() == 32);
    CHECK(s.valid(a, 1000));
    CHECK(!s.valid("", 1000));
    CHECK(!s.valid(std::string(32, '0'), 1000));
    CHECK(!s.valid(a.substr(0, 31), 1000));
    CHECK(!s.valid(a + "0", 1000));

    // every use keeps it alive, up to 12 hours from the start
    unsigned long t = 1000;
    for (int i = 0; i < 20; i++) {
        t += 20UL * 60UL * 1000UL;
        CHECK(s.valid(a, t));
    }
    CHECK(!s.valid(a, 1000 + MAX_MS + 1));

    // idle for more than 30 minutes ends it, and it stays ended
    std::string b = startWith(s, 0xB2, 5000);
    CHECK(s.valid(b, 5000 + IDLE_MS));
    CHECK(!s.valid(b, 5000 + IDLE_MS + (IDLE_MS + 1)));
    CHECK(!s.valid(b, 5000 + IDLE_MS + 5));

    // several browsers at once; ending one leaves the others
    Sessions m;
    std::string t1 = startWith(m, 1, 0), t2 = startWith(m, 2, 0), t3 = startWith(m, 3, 0);
    CHECK(m.valid(t1, 10) && m.valid(t2, 10) && m.valid(t3, 10));
    m.end(t2);
    CHECK(m.valid(t1, 20) && !m.valid(t2, 20) && m.valid(t3, 20));
    m.end("");
    m.end("not a token");
    CHECK(m.valid(t1, 30));
    m.endAll();
    CHECK(!m.valid(t1, 40) && !m.valid(t3, 40));

    // a full table replaces the session idle the longest, never one in use
    Sessions f;
    std::string tok[SLOTS + 1];
    for (int i = 0; i < SLOTS; i++)
        tok[i] = startWith(f, (uint8_t)(10 + i), (unsigned long)i);
    CHECK(f.valid(tok[0], 100)); // slot 0 is used now, so slot 1 is the one idle the longest
    tok[SLOTS] = startWith(f, 99, 101);
    CHECK(f.valid(tok[SLOTS], 102) && f.valid(tok[0], 102));
    CHECK(!f.valid(tok[1], 102));
    CHECK(f.valid(tok[2], 102));

    // an expired slot is reused before a live one is thrown out
    Sessions g;
    std::string old = startWith(g, 7, 0);
    std::string keep = startWith(g, 8, 10);
    unsigned long later = IDLE_MS + 5; // old is idle past the limit, keep (used just now) is not
    CHECK(g.valid(keep, later));
    std::string fresh = startWith(g, 9, later);
    CHECK(g.valid(fresh, later) && g.valid(keep, later) && !g.valid(old, later));

    printf("%d checks, %d failed\n", checks, failures);
    return failures ? 1 : 0;
}
