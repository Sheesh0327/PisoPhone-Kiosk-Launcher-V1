// Host test for DiagRing. Build and run with host_tests/run.sh (no hardware needed).
#include "../include/DiagRing.h"
#include <assert.h>
#include <string>

static int checks = 0;
#define CHECK(c)                                                                                                       \
    do {                                                                                                               \
        checks++;                                                                                                      \
        if (!(c)) {                                                                                                    \
            printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #c);                                                        \
            return 1;                                                                                                  \
        }                                                                                                              \
    } while (0)

static bool validUtf8(const char* s) {
    const unsigned char* p = (const unsigned char*)s;
    while (*p) {
        size_t need = *p < 0x80 ? 1 : (*p >> 5) == 0x6 ? 2 : (*p >> 4) == 0xE ? 3 : (*p >> 3) == 0x1E ? 4 : 0;
        if (need == 0) return false;
        for (size_t i = 1; i < need; i++)
            if ((p[i] & 0xC0) != 0x80) return false;
        p += need;
    }
    return true;
}

int main() {
    { // empty
        DiagRing<4, 64> r;
        CHECK(r.size() == 0 && r.total() == 0);
    }
    { // order and timestamp format
        DiagRing<4, 64> r;
        r.push(1234, "first");
        r.push(5678, "second");
        CHECK(r.size() == 2);
        CHECK(std::string(r.at(0)) == "1.234 first");
        CHECK(std::string(r.at(1)) == "5.678 second");
    }
    { // wraps and keeps the newest, oldest first
        DiagRing<3, 64> r;
        for (int i = 1; i <= 7; i++) {
            char m[16];
            snprintf(m, sizeof m, "m%d", i);
            r.push((unsigned long)i * 1000, m);
        }
        CHECK(r.size() == 3 && r.total() == 7);
        CHECK(std::string(r.at(0)) == "5.000 m5");
        CHECK(std::string(r.at(1)) == "6.000 m6");
        CHECK(std::string(r.at(2)) == "7.000 m7");
    }
    { // line breaks removed
        DiagRing<2, 64> r;
        r.push(0, "\n[+] hello\r\n");
        CHECK(std::string(r.at(0)) == "0.000 [+] hello");
    }
    { // long lines are truncated, never overflow, and stay NUL-terminated
        DiagRing<2, 16> r;
        r.push(0, "0123456789012345678901234567890123456789");
        CHECK(strlen(r.at(0)) == 15);
    }
    { // truncation never splits a UTF-8 character (emoji is 4 bytes)
        for (size_t keep = 0; keep < 8; keep++) {
            DiagRing<2, 20> r;
            std::string msg(keep, 'a');
            msg += "\xF0\x9F\xAA\x99 coin"; // coin emoji
            r.push(0, msg.c_str());
            CHECK(validUtf8(r.at(0)));
        }
        DiagRing<2, 12> r;
        r.push(0, "\xE2\x82\xB1\xE2\x82\xB1\xE2\x82\xB1\xE2\x82\xB1"); // peso sign x4
        CHECK(validUtf8(r.at(0)));
    }
    { // null message and clear
        DiagRing<2, 32> r;
        r.push(0, nullptr);
        CHECK(std::string(r.at(0)) == "0.000 ");
        r.clear();
        CHECK(r.size() == 0);
        r.push(1000, "x");
        CHECK(std::string(r.at(0)) == "1.000 x");
    }
    printf("OK: %d checks\n", checks);
    return 0;
}
