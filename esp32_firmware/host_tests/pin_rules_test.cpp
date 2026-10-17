// The pins the owner may choose in Settings (include/PinRules.h).
#include <cstdio>

#include "../include/PinRules.h"

using pinrules::Chip;
using pinrules::Use;

static int failures = 0;
#define CHECK(cond, msg)                                                                                               \
    do {                                                                                                               \
        if (!(cond)) {                                                                                                 \
            std::printf("FAIL: %s\n", msg);                                                                            \
            failures++;                                                                                                \
        }                                                                                                              \
    } while (0)

int main() {
    // the boards' own defaults are always accepted (coin 3, LED 2 or 8, relay 4, reset 15 or 2)
    CHECK(pinrules::problem(Chip::Classic, 3, 4, 2, 15) == nullptr, "ESP32 defaults");
    CHECK(pinrules::problem(Chip::C3, 3, 4, 8, 2) == nullptr, "C3 defaults");

    // the flash bus would crash the box at every start
    for (int p = 6; p <= 11; p++)
        CHECK(!pinrules::usable(Chip::Classic, p, Use::OUTPUT_PIN), "ESP32 flash pins refused");
    for (int p = 11; p <= 17; p++)
        CHECK(!pinrules::usable(Chip::C3, p, Use::OUTPUT_PIN), "C3 flash pins refused");
    // the C3's USB port is how it is repaired
    CHECK(!pinrules::usable(Chip::C3, 18, Use::OUTPUT_PIN) && !pinrules::usable(Chip::C3, 19, Use::INPUT_PIN),
          "C3 USB pins refused");
    // pins that do not exist
    CHECK(!pinrules::usable(Chip::Classic, 40, Use::INPUT_PIN) && !pinrules::usable(Chip::Classic, -1, Use::INPUT_PIN),
          "out of range refused");
    CHECK(!pinrules::usable(Chip::C3, 22, Use::INPUT_PIN), "C3 has no pin 22");
    CHECK(!pinrules::usable(Chip::Classic, 24, Use::INPUT_PIN), "ESP32 has no pin 24");
    // input-only pins: fine for the coin, not for the relay or the LED
    CHECK(pinrules::usable(Chip::Classic, 34, Use::INPUT_PIN), "34 can read a coin");
    CHECK(!pinrules::usable(Chip::Classic, 34, Use::OUTPUT_PIN), "34 cannot drive a relay");
    CHECK(pinrules::problem(Chip::Classic, 3, 35, 2, 15) != nullptr, "a relay on an input-only pin is refused");

    // one job per pin, and never the reset button's pin
    CHECK(pinrules::problem(Chip::Classic, 4, 4, 2, 15) != nullptr, "coin and relay on one pin refused");
    CHECK(pinrules::problem(Chip::Classic, 3, 4, 4, 15) != nullptr, "relay and LED on one pin refused");
    CHECK(pinrules::problem(Chip::Classic, 15, 4, 2, 15) != nullptr, "the reset pin refused");
    CHECK(pinrules::problem(Chip::C3, 3, 2, 8, 2) != nullptr, "the C3 reset pin refused");

    // what the form sends
    CHECK(pinrules::parsePin("12") == 12, "a number");
    CHECK(pinrules::parsePin("0") == 0, "zero is a number");
    CHECK(pinrules::parsePin("") == -1, "empty is not a pin (it used to become GPIO 0)");
    CHECK(pinrules::parsePin("12a") == -1 && pinrules::parsePin("-3") == -1 && pinrules::parsePin(" 4") == -1,
          "junk refused");
    CHECK(pinrules::parsePin("12345") == -1, "too long refused");
    CHECK(pinrules::parsePin(nullptr) == -1, "nothing refused");

    std::printf("pin rules: %s\n", failures ? "FAILED" : "OK");
    return failures ? 1 : 0;
}
