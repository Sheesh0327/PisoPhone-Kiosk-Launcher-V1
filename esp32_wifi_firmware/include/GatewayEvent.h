#ifndef GATEWAY_EVENT_H
#define GATEWAY_EVENT_H

// Coin events pushed from the box to the gateway (router) over UDP.
// Pure C++ (no Arduino types): host_tests/gateway_protocol_test.cpp tests it.
//
// Format:
//     gw1ev:<session>:<wid>:<seq>:<type>:<pulses>:<sig>\n      type = ready | coin | end
//     sig = HMAC-SHA256(gateway key, "gw1ev:<session>:<wid>:<seq>:<type>:<pulses>")

#include <cstdint>
#include <string>

#include "CredCrypto.h"

namespace gatewayevent {

static const size_t MAX_WID_LENGTH = 40;

// Window ids come from the router: lower-case hex only.
inline bool validWindowId(const std::string& wid) {
    if (wid.empty() || wid.size() > MAX_WID_LENGTH) return false;
    for (char c : wid) {
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
    }
    return true;
}

inline bool validPort(long port) {
    return port >= 1 && port <= 65535;
}

inline std::string body(const std::string& session, const std::string& wid, uint32_t seq, const std::string& type,
                        int pulses) {
    return "gw1ev:" + session + ":" + wid + ":" + std::to_string(seq) + ":" + type + ":" + std::to_string(pulses);
}

// The full line (with the trailing newline), or "" if it cannot be signed.
inline std::string line(const std::string& key, const std::string& session, const std::string& wid, uint32_t seq,
                        const std::string& type, int pulses) {
    std::string msg = body(session, wid, seq, type, pulses);
    uint8_t mac[32];
    if (!credcrypto::hmacSha256((const uint8_t*)key.data(), key.size(), (const uint8_t*)msg.data(), msg.size(), mac)) {
        return "";
    }
    return msg + ":" + credcrypto::hexEncode(mac, sizeof(mac)) + "\n";
}

} // namespace gatewayevent

#endif // GATEWAY_EVENT_H
