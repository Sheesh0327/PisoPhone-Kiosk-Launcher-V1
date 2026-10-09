#ifndef ACCOUNT_PROTOCOL_H
#define ACCOUNT_PROTOCOL_H

// The wire format of the player-account calls between phone and box (pure, so the phone and the box are tested against the same
// vectors in protocol/fixtures/box_phone_v1.json). See docs/api/gateway-coinslot.md, "Accounts".
//
// A player's account is a QR card (CardCodec.h); the phone scans it and sends the card text to the box. Every call is signed:
//     sig = HMAC-SHA256(box secret, "v1:acct_<op>:<deviceId>:<ts>:<bound>")
// where <bound> carries every parameter that matters, so a captured request cannot be changed and replayed:
//     scan     bound = "<card text>"          sign in with a card (the first scan of a card also gives its starter time)
//     name     bound = "<id>:<name>"          give the account a display name
//     signout  bound = "<id>:<seconds left>"  final report, then signed out
//     report   bound = "<id>:<seconds left>"  sent with every heartbeat while signed in
//     info     bound = "<id>"
// <id> is the card's serial number in decimal. Nothing secret travels except the card text itself, which is the credential.

#include "Accounts.h"
#include "ProtocolCrypto.h"

#include <string>

namespace acctproto {

inline std::string message(const std::string& op, const std::string& deviceId, const std::string& ts,
                           const std::string& bound) {
    return "v1:acct_" + op + ":" + deviceId + ":" + ts + ":" + bound;
}

// Lower-case hex of the signature the phone must send.
inline std::string signature(const std::string& secret, const std::string& op, const std::string& deviceId,
                             const std::string& ts, const std::string& bound) {
    return protocol::hmacHex(message(op, deviceId, ts, bound), secret);
}

inline std::string boundWithSeconds(uint32_t id, uint32_t seconds) {
    return std::to_string(id) + ":" + std::to_string(seconds);
}

inline std::string boundWithName(uint32_t id, const std::string& name) {
    return std::to_string(id) + ":" + name;
}

// A card number: digits only, 1 to 65535.
inline bool parseId(const std::string& s, uint32_t& out) {
    if (s.empty() || s.size() > 5) return false;
    uint32_t v = 0;
    for (char c : s) {
        if (c < '0' || c > '9') return false;
        v = v * 10 + (uint32_t)(c - '0');
    }
    if (!accounts::validId(v)) return false;
    out = v;
    return true;
}

// Whole non-negative decimal number that fits in 32 bits; anything else (empty, sign, letters, overflow) is refused.
inline bool parseSeconds(const std::string& s, uint32_t& out) {
    if (s.empty() || s.size() > 10) return false;
    uint64_t v = 0;
    for (char c : s) {
        if (c < '0' || c > '9') return false;
        v = v * 10 + (uint64_t)(c - '0');
    }
    if (v > UINT32_MAX) return false;
    out = (uint32_t)v;
    return true;
}

} // namespace acctproto

#endif // ACCOUNT_PROTOCOL_H
