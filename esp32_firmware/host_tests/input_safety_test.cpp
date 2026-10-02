// Host test for InputSafety.h. Build and run with host_tests/run.sh (no hardware needed).
#include "../include/InputSafety.h"
#include <stdio.h>

using namespace inputsafety;

static int checks = 0;
#define CHECK(c) do { checks++; if (!(c)) { printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); return 1; } } while (0)

int main() {
    CHECK(jsonEscape("plain") == "plain");
    CHECK(jsonEscape("a\"b") == "a\\\"b");
    CHECK(jsonEscape("a\\b") == "a\\\\b");
    CHECK(jsonEscape("l1\nl2\t") == "l1\\nl2\\t");
    CHECK(jsonEscape(std::string("x\x01y")) == "x\\u0001y");
    CHECK(jsonEscape("caf\xC3\xA9") == "caf\xC3\xA9");

    CHECK(sanitizeName("Phone <b>1</b>") == "Phone b1/b");
    CHECK(sanitizeName("  Kiosk \"A\"  ") == "Kiosk A");
    CHECK(sanitizeName("a&b'c`d\\e") == "abcde");
    CHECK(sanitizeName(std::string("t\x01\x7f" "x")) == "tx");
    CHECK(sanitizeName(std::string(100, 'z')).size() == 32);
    // 31 ASCII + a 2-byte char straddling the limit must not be cut in half.
    std::string edge = std::string(31, 'a') + "\xC3\xA9" + "tail";
    std::string cut = sanitizeName(edge);
    CHECK(cut == std::string(31, 'a'));
    CHECK(sanitizeName("") == "");

    LoginThrottle t;
    const uint32_t A = 1, B = 2;
    unsigned long now = 1000;
    CHECK(t.lockedForMs(A, now) == 0);
    for (int i = 0; i < 4; i++) { t.recordFailure(A, now); now += 100; }
    CHECK(t.lockedForMs(A, now) == 0);          // four failures: still allowed
    t.recordFailure(A, now);                    // fifth locks
    CHECK(t.lockedForMs(A, now) > 59000);
    CHECK(t.lockedForMs(B, now) == 0);          // other clients unaffected
    now += 59000;
    CHECK(t.lockedForMs(A, now) > 0);
    now += 2000;
    CHECK(t.lockedForMs(A, now) == 0);          // lock expires
    t.recordFailure(A, now);                    // counting restarts from zero
    CHECK(t.lockedForMs(A, now) == 0);
    t.recordSuccess(A);
    for (int i = 0; i < 4; i++) t.recordFailure(A, now);
    CHECK(t.lockedForMs(A, now) == 0);          // success cleared the earlier failure

    // Old failures are forgotten after the window.
    LoginThrottle w;
    now = 5000;
    for (int i = 0; i < 4; i++) w.recordFailure(A, now);
    now += LoginThrottle::WINDOW_MS + 1;
    w.recordFailure(A, now);
    CHECK(w.lockedForMs(A, now) == 0);

    // More clients than slots must not crash or lock the wrong one.
    LoginThrottle m;
    for (uint32_t c = 10; c < 20; c++) m.recordFailure(c, now + c);
    CHECK(m.lockedForMs(99, now) == 0);

    // millis() wrap-around.
    LoginThrottle r;
    unsigned long nearWrap = (unsigned long)-2000;
    for (int i = 0; i < 5; i++) r.recordFailure(A, nearWrap);
    CHECK(r.lockedForMs(A, nearWrap + 1000) > 0);
    CHECK(r.lockedForMs(A, nearWrap + 70000UL) == 0);

    printf("input_safety_test: %d checks passed\n", checks);
    return 0;
}
