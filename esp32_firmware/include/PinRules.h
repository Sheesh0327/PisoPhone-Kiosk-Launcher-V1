#ifndef PIN_RULES_H
#define PIN_RULES_H

// Which GPIO numbers the owner may give the coin input, the relay and the LED in Settings. Pure functions, tested on a PC
// (host_tests/pin_rules_test.cpp).
//
// A wrong pin used to be saved as typed and applied at once: a pin on the flash bus (6-11 on an ESP32, 11-17 on a C3) crashes the
// box, and because the same pin is applied again at every start it never comes back without a USB flash. On a C3, 18 and 19 are
// the USB port used to flash it, so a relay there would also take away the way to repair it. These are refused; so is the same pin
// for two jobs (and the reset button's pin, which the box watches for a factory reset).

#if __has_include(<sdkconfig.h>)
#include <sdkconfig.h> // tells which chip this firmware is built for
#endif

namespace pinrules {

// (not named ESP32: the build flags define ESP32 as a macro, which broke every firmware build)
enum class Chip { Classic, C3 };
enum class Use { INPUT_PIN, OUTPUT_PIN };

// Can this pin do this job on this chip without harming the box?
inline bool usable(Chip chip, int pin, Use use) {
    if (chip == Chip::C3) {
        if (pin < 0 || pin > 21) return false;
        if (pin >= 11 && pin <= 17) return false; // the flash bus (and its supply pin, 11)
        if (pin == 18 || pin == 19) return false; // the USB port the box is flashed through
        return true;
    }
    if (pin < 0 || pin > 39) return false;
    if (pin >= 6 && pin <= 11) return false;                              // the flash bus
    if (pin == 20 || pin == 24 || (pin >= 28 && pin <= 31)) return false; // no such pin
    if (use == Use::OUTPUT_PIN && pin >= 34) return false;                // 34-39 are inputs only
    return true;
}

// The reason a set of pins cannot be used, or nullptr when they are fine.
inline const char* problem(Chip chip, int coinPin, int relayPin, int ledPin, int resetPin) {
    if (!usable(chip, coinPin, Use::INPUT_PIN)) return "The coin pin cannot be used on this board.";
    if (!usable(chip, relayPin, Use::OUTPUT_PIN)) return "The relay pin cannot be used on this board.";
    if (!usable(chip, ledPin, Use::OUTPUT_PIN)) return "The LED pin cannot be used on this board.";
    if (coinPin == relayPin || coinPin == ledPin || relayPin == ledPin) return "Each pin can only have one job.";
    if (coinPin == resetPin || relayPin == resetPin || ledPin == resetPin)
        return "That pin is the reset button's: choose another.";
    return nullptr;
}

// A whole number typed in a form ("12", not "", "12a" or "-3"); -1 when it is not one.
inline int parsePin(const char* text) {
    if (text == nullptr || *text == '\0') return -1;
    int value = 0;
    for (const char* p = text; *p; p++) {
        if (*p < '0' || *p > '9') return -1;
        value = value * 10 + (*p - '0');
        if (value > 99) return -1;
    }
    return value;
}

#if defined(CONFIG_IDF_TARGET_ESP32C3)
constexpr Chip THIS_CHIP = Chip::C3;
#else
constexpr Chip THIS_CHIP = Chip::Classic;
#endif

} // namespace pinrules

#endif
