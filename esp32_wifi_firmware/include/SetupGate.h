#ifndef SETUP_GATE_H
#define SETUP_GATE_H

// Mandatory first-time setup gate: a fresh or factory-reset coin box starts with a known default
// admin password and refuses to arm the coin slot until the router replaces it.
// Pure C++ (no Arduino types), tested in host_tests/coin_pulse_test.cpp.

#include <cstring>

namespace setupgate {

static const char* const DEFAULT_PASSWORD = "Coinslot@Setup";
static const char* const DEFAULT_WIFI_SSID = "PisoCoinBox";
static const char* const DEFAULT_WIFI_PASSWORD = "PisoCoinBox@Setup";

static const unsigned MIN_PASSWORD_LENGTH = 8;

inline bool passwordAcceptable(const char* candidate) {
    if (!candidate) return false;
    return std::strlen(candidate) >= MIN_PASSWORD_LENGTH && std::strcmp(candidate, DEFAULT_PASSWORD) != 0;
}

inline bool wifiPasswordValid(const char* candidate) {
    if (!candidate) return false;
    size_t n = std::strlen(candidate);
    if (n < 8 || n > 63) return false;
    for (size_t i = 0; i < n; i++) {
        unsigned char c = static_cast<unsigned char>(candidate[i]);
        if (c < 0x20 || c > 0x7E) return false;
    }
    return true;
}

inline bool wifiSsidValid(const char* candidate) {
    if (!candidate) return false;
    size_t n = std::strlen(candidate);
    if (n < 1 || n > 32) return false;
    for (size_t i = 0; i < n; i++) {
        unsigned char c = static_cast<unsigned char>(candidate[i]);
        if (c < 0x20 || c > 0x7E) return false;
    }
    return true;
}

inline bool usageAllowed(bool adminPasswordChanged) {
    return adminPasswordChanged;
}

} // namespace setupgate

#endif // SETUP_GATE_H
