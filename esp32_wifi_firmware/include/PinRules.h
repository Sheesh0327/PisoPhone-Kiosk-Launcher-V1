#ifndef PIN_RULES_H
#define PIN_RULES_H

// Valid GPIO numbers for coin input, relay, and LED. Pure functions tested on a PC
// (host_tests/coin_pulse_test.cpp).

#if __has_include(<sdkconfig.h>)
#include <sdkconfig.h>
#endif

namespace pinrules {

enum class Chip { Classic, C3 };
enum class Use { INPUT_PIN, OUTPUT_PIN };

inline bool usable(Chip chip, int pin, Use use) {
    if (chip == Chip::C3) {
        if (pin < 0 || pin > 21) return false;
        if (pin >= 11 && pin <= 17) return false; // flash bus and supply pin
        if (pin == 18 || pin == 19) return false; // USB CDC flashing port
        return true;
    }
    if (pin < 0 || pin > 39) return false;
    if (pin >= 6 && pin <= 11) return false;                              // flash bus
    if (pin == 20 || pin == 24 || (pin >= 28 && pin <= 31)) return false; // reserved/unavailable
    if (use == Use::OUTPUT_PIN && pin >= 34) return false;                // 34-39 are input-only
    return true;
}

inline const char* problem(Chip chip, int coinPin, int relayPin, int ledPin, int resetPin) {
    if (!usable(chip, coinPin, Use::INPUT_PIN)) return "The coin pin cannot be used on this board.";
    if (!usable(chip, relayPin, Use::OUTPUT_PIN)) return "The relay pin cannot be used on this board.";
    if (!usable(chip, ledPin, Use::OUTPUT_PIN)) return "The LED pin cannot be used on this board.";
    if (coinPin == relayPin || coinPin == ledPin || relayPin == ledPin) return "Each pin can only have one job.";
    if (coinPin == resetPin || relayPin == resetPin || ledPin == resetPin)
        return "That pin is the reset button's: choose another.";
    return nullptr;
}

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

#endif // PIN_RULES_H
