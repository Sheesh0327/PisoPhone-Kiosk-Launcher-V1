#ifndef ACCOUNT_PROTOCOL_H
#define ACCOUNT_PROTOCOL_H

// The wire format of the player-account calls between phone and box (pure, so the phone and the box are tested against
// the same vectors in protocol/fixtures/box_phone_v1.json). See docs/api/gateway-coinslot.md, "Accounts".
//
// Every call is signed: sig = HMAC-SHA256(box secret, "v1:acct_<op>:<deviceId>:<ts>:<bound>"). <bound> carries every
// parameter that matters, so a captured request cannot be changed and replayed:
//   create / signin  bound = "<user>:<hex AES of 'PIN:<pin>'>"      the PIN never travels in clear
//   signout          bound = "<user>:<seconds left>"                final report, then signed out
//   report           bound = "<user>:<seconds left>"                sent with every heartbeat while signed in
//   info             bound = "<user>"

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

inline std::string boundWithPin(const std::string& user, const std::string& cipherHex) {
    return user + ":" + cipherHex;
}

inline std::string boundWithSeconds(const std::string& user, uint32_t seconds) {
    return user + ":" + std::to_string(seconds);
}

// Opens the PIN envelope ("PIN:<digits>" encrypted with the box secret). False if it is damaged or not a valid PIN.
inline bool openPin(const std::string& cipherHex, const std::string& secret, std::string& pinOut) {
    std::string plain;
    if (!protocol::decryptHex(cipherHex, secret, plain)) return false;
    if (plain.compare(0, 4, "PIN:") != 0) return false;
    std::string pin = plain.substr(4);
    if (!accounts::validPin(pin)) return false;
    pinOut = pin;
    return true;
}

// Splits "<user>:<rest>" at the first colon. False if there is no colon.
inline bool splitBound(const std::string& bound, std::string& user, std::string& rest) {
    size_t c = bound.find(':');
    if (c == std::string::npos) return false;
    user = bound.substr(0, c);
    rest = bound.substr(c + 1);
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
