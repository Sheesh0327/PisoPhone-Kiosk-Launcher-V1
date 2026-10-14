// The settling window after the relay powers the coin acceptor: stray pulses inside it are ignored, including the rest
// of a pulse train that began inside it; real coins after it count normally; clocks that wrap are handled.
#include <cstdio>
#include "../include/CoinSettle.h"

static int failures = 0;
#define CHECK(cond)                                                                                                    \
    do {                                                                                                               \
        if (!(cond)) {                                                                                                 \
            std::printf("FAIL line %d: %s\n", __LINE__, #cond);                                                        \
            failures++;                                                                                                \
        }                                                                                                              \
    } while (0)

int main() {
    const unsigned long armedAt = 50000, until = armedAt + PISO_COIN_SETTLE_MS;
    CHECK(PISO_COIN_SETTLE_MS == 1000UL);
    CHECK(coinSettleActive(armedAt, until));         // right after power-on
    CHECK(coinSettleActive(armedAt + 999, until));   // last millisecond of the window
    CHECK(!coinSettleActive(armedAt + 1000, until)); // settled
    CHECK(!coinSettleActive(armedAt + 5000, until));
    CHECK(!coinSettleActive(1234, 0));                         // no window at all
    CHECK(coinSettleRemainingMs(armedAt + 400, until) == 600); // what clients are told
    CHECK(coinSettleRemainingMs(armedAt + 1000, until) == 0);
    // a pulse that arrived inside the window but is read after it closed (late main loop) is still a stray pulse
    CHECK(coinSettleCovers(armedAt + 990, until));
    CHECK(!coinSettleCovers(armedAt + 1000, until));
    CHECK(!coinSettleCovers(armedAt + 1500, until));
    CHECK(!coinSettleCovers(armedAt + 100, 0));
    // a train whose last pulse was at +990 ms keeps the window open until it has been silent for 280 ms
    CHECK(coinSettleExtend(until, armedAt + 990, 280) == armedAt + 990 + 280);
    CHECK(coinSettleExtend(until, armedAt + 100, 280) == until); // an early stray pulse does not shorten it
    // millis() wrapping around 2^32 in the middle of the window
    const unsigned long nearWrap = 0xFFFFFF00UL, wrapUntil = nearWrap + PISO_COIN_SETTLE_MS; // wraps to a small number
    CHECK(coinSettleActive(nearWrap + 10, wrapUntil));
    CHECK(coinSettleActive(wrapUntil - 1, wrapUntil));
    CHECK(!coinSettleActive(wrapUntil + 1, wrapUntil));
    CHECK(coinSettleRemainingMs(nearWrap + 100, wrapUntil) == 900);
    std::printf("coin_settle: %s\n", failures ? "FAILED" : "OK");
    return failures ? 1 : 0;
}
