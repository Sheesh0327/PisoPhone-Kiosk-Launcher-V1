#ifndef WIFI_LINK_H
#define WIFI_LINK_H

// Wi-Fi link policy and the plain-language reason a connection attempt failed. Pure (no Arduino types) so
// host_tests/wifi_link_test.cpp can test it. The numbers are ESP-IDF's wifi_err_reason_t.

#include <cstdint>

namespace wifilink {

static const uint32_t BOOT_CONNECT_MS = 10000U;   // how long setup() waits for the first connection
static const uint32_t RETRY_INTERVAL_MS = 15000U; // a new attempt this long after the previous one started
static const uint32_t RAPID_BLINK_MS = 8000U;     // the LED blinks fast for this long after an attempt starts

// Time-wrap safe: true when a new attempt is due.
inline bool retryDue(uint32_t now, uint32_t lastAttempt) {
    return (now - lastAttempt) > RETRY_INTERVAL_MS;
}

// Short name for a disconnect reason (what ESP-IDF reports).
inline const char* reasonName(uint8_t reason) {
    switch (reason) {
    case 2:
        return "AUTH_EXPIRE";
    case 3:
        return "AUTH_LEAVE";
    case 4:
        return "ASSOC_EXPIRE";
    case 5:
        return "ASSOC_TOOMANY";
    case 6:
        return "NOT_AUTHED";
    case 7:
        return "NOT_ASSOCED";
    case 8:
        return "ASSOC_LEAVE";
    case 14:
        return "MIC_FAILURE";
    case 15:
        return "4WAY_HANDSHAKE_TIMEOUT";
    case 16:
        return "GROUP_KEY_UPDATE_TIMEOUT";
    case 23:
        return "802_1X_AUTH_FAILED";
    case 200:
        return "BEACON_TIMEOUT";
    case 201:
        return "NO_AP_FOUND";
    case 202:
        return "AUTH_FAIL";
    case 203:
        return "ASSOC_FAIL";
    case 204:
        return "HANDSHAKE_TIMEOUT";
    case 205:
        return "CONNECTION_FAIL";
    default:
        return "OTHER";
    }
}

// What the reason usually means for this box, in words an installer can act on.
inline const char* reasonHint(uint8_t reason) {
    switch (reason) {
    case 201:
        return "network not found: router off, out of range, hidden network not answering, or on a channel the box cannot use";
    case 15:
    case 202:
    case 204:
    case 14:
        return "wrong Wi-Fi password (router and box disagree)";
    case 203:
    case 5:
        return "router refused the box (MAC filter or too many stations)";
    case 200:
        return "signal lost";
    case 8:
    case 3:
        return "router disconnected the box (Wi-Fi reload or MAC filter change)";
    default:
        return "";
    }
}

} // namespace wifilink

#endif // WIFI_LINK_H
