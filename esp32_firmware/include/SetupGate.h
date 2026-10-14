#ifndef SETUP_GATE_H
#define SETUP_GATE_H

// The box ships with one known password so it can be set up and reset without a serial monitor. That makes
// changing it mandatory: until the operator has chosen their own admin password, no coin slot is armed.
// Pure (no Arduino types) so host_tests/setup_gate_test.cpp can test it.

#include <cstring>

namespace setupgate {

// Admin password of a box that has just been flashed or factory reset.
static const char* const DEFAULT_PASSWORD = "Coinslot@Setup";

// The box has no access point of its own. A fresh or factory-reset box joins this hidden Wi-Fi, which the router's
// setup script creates (it admits only this box's MAC address, so the published password is not a way in for anyone else).
static const char* const DEFAULT_WIFI_SSID = "PisoCoinBox";
static const char* const DEFAULT_WIFI_PASSWORD = "PisoCoinBox@Setup";

static const unsigned MIN_PASSWORD_LENGTH = 8;

// A new admin password must be long enough and must not be the published default.
inline bool passwordAcceptable(const char* candidate) {
    if (!candidate) return false;
    return std::strlen(candidate) >= MIN_PASSWORD_LENGTH && std::strcmp(candidate, DEFAULT_PASSWORD) != 0;
}

// The password of the rental phones' hidden Wi-Fi (PisoKiosk), which the router hands to the box so the "Set up a phone"
// link can carry it. Wi-Fi allows 8-63 printable characters; nothing else is stored.
inline bool kioskWifiPasswordValid(const char* candidate) {
    if (!candidate) return false;
    size_t n = std::strlen(candidate);
    if (n < 8 || n > 63) return false;
    for (size_t i = 0; i < n; i++) {
        unsigned char c = static_cast<unsigned char>(candidate[i]);
        if (c < 0x20 || c > 0x7E) return false;
    }
    return true;
}

// Coins may only be accepted once the operator has chosen their own admin password.
inline bool usageAllowed(bool adminPasswordChanged) {
    return adminPasswordChanged;
}

} // namespace setupgate

#endif // SETUP_GATE_H
