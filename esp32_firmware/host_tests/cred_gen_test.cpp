#include "../include/CredGen.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <set>

static int failures = 0, checks = 0;
#define CHECK(c)                                                                                                       \
    do {                                                                                                               \
        checks++;                                                                                                      \
        if (!(c)) {                                                                                                    \
            failures++;                                                                                                \
            printf("FAIL line %d: %s\n", __LINE__, #c);                                                                \
        }                                                                                                              \
    } while (0)

static uint32_t state = 12345;
static void lcg(uint8_t* buf, size_t len) {
    for (size_t i = 0; i < len; i++) {
        state = state * 1664525u + 1013904223u;
        buf[i] = (uint8_t)(state >> 24);
    }
}
// A source that only ever returns bytes the generator must reject, then valid ones.
static int calls = 0;
static void rejectFirst(uint8_t* buf, size_t len) {
    memset(buf, calls++ == 0 ? 255 : 7, len);
}

int main() {
    std::string p = credgen::password(12, lcg);
    CHECK(p.size() == 12);
    for (char c : p)
        CHECK(strchr(credgen::ALPHABET, c) != nullptr);

    // look-alike characters are not in the alphabet
    CHECK(strchr(credgen::ALPHABET, '0') == nullptr && strchr(credgen::ALPHABET, 'O') == nullptr);
    CHECK(strchr(credgen::ALPHABET, '1') == nullptr && strchr(credgen::ALPHABET, 'l') == nullptr);
    CHECK(strchr(credgen::ALPHABET, 'I') == nullptr);

    // successive passwords differ, and every alphabet character shows up in a large sample
    std::set<std::string> seen;
    std::set<char> chars;
    for (int i = 0; i < 200; i++) {
        std::string s = credgen::password(10, lcg);
        seen.insert(s);
        chars.insert(s.begin(), s.end());
    }
    CHECK(seen.size() == 200);
    CHECK(chars.size() == credgen::ALPHABET_LEN);

    // biased bytes (>= limit) are discarded, not folded into the alphabet
    calls = 0;
    std::string r = credgen::password(4, rejectFirst);
    CHECK(r.size() == 4 && r == std::string(4, credgen::ALPHABET[7]));

    CHECK(credgen::password(0, lcg).empty());
    printf("cred_gen_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
