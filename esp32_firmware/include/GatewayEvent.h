#ifndef GATEWAY_EVENT_H
#define GATEWAY_EVENT_H

// Coin events pushed from the box to the gateway (router), so a coin shows up and grants access at once instead of
// waiting for the router's next poll. Pure C++ (no Arduino types): host_tests/gateway_event_test.cpp tests it.
//
// When the router arms the slot it may add  wid=<window id>&evport=<udp port> . The box then sends one UDP line to the
// router (the address the arm request came from) on every change of that window:
//     gw1ev:<session>:<wid>:<seq>:<type>:<pulses>:<sig>\n      type = ready | coin | end
//     sig = HMAC-SHA256(gateway key, "gw1ev:<session>:<wid>:<seq>:<type>:<pulses>")
// pulses is the running total for the window (never a delta), so a lost or repeated line does no harm; seq only
// orders them. The window id ties a line to one coin window: a line recorded earlier cannot be replayed into a later
// window. The router's signed status/release/ack calls stay the authority for acknowledging coins.

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
