#ifndef GATEWAY_AUTH_H
#define GATEWAY_AUTH_H

// Request authentication for the network coin-slot gateway (e.g. an OpenNDS router verifying a
// payment). Pure C++ with no Arduino types so the exact code is unit-tested on a PC
// (host_tests/gateway_auth_test.cpp).
//
// Scheme: challenge-response with a key shared only between the box and the gateway.
//   1. The gateway asks for a nonce:      GET /api/gateway/challenge
//   2. It signs one request with it:      sig = HMAC-SHA256(key, "gw1:<action>:<session>:<nonce>")
//   3. The box accepts the request once; the nonce is then burnt (and expires after 30 s).
// A sniffed request therefore cannot be replayed, and no clock is needed on either side.

#include <cstdint>
#include <string>

#include "CredCrypto.h"

namespace gatewayauth {

static const int NONCE_SLOTS = 8;
static const uint32_t NONCE_TTL_MS = 30000;
static const size_t NONCE_BYTES = 16;
static const size_t MIN_KEY_LENGTH = 16;
static const size_t MAX_SESSION_ID_LENGTH = 64;

// Session ids come from the gateway (a client MAC, token or hash). Keep them to a safe alphabet.
inline bool validSessionId(const std::string& id) {
    if (id.empty() || id.size() > MAX_SESSION_ID_LENGTH) return false;
    for (char c : id) {
        bool ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_' ||
                  c == '.' || c == ':';
        if (!ok) return false;
    }
    return true;
}

inline std::string signRequest(const std::string& key, const std::string& action, const std::string& session,
                               const std::string& nonce) {
    std::string msg = "gw1:" + action + ":" + session + ":" + nonce;
    uint8_t mac[32];
    if (!credcrypto::hmacSha256((const uint8_t*)key.data(), key.size(), (const uint8_t*)msg.data(), msg.size(), mac)) {
        return "";
    }
    return credcrypto::hexEncode(mac, sizeof(mac));
}

// Fixed-size set of outstanding nonces. Issuing beyond capacity overwrites the oldest one.
class NonceStore {
public:
    // random: NONCE_BYTES bytes from a hardware RNG. Returns the hex nonce to hand to the client.
    std::string issue(const uint8_t* random, uint32_t nowMs) {
        int slot = -1;
        for (int i = 0; i < NONCE_SLOTS && slot < 0; i++) {
            if (!used_[i] || expired(i, nowMs)) slot = i; // reuse a free or stale slot first
        }
        if (slot < 0) { // all live: overwrite the oldest
            slot = 0;
            for (int i = 1; i < NONCE_SLOTS; i++) {
                if ((uint32_t)(nowMs - issuedAt_[i]) > (uint32_t)(nowMs - issuedAt_[slot])) slot = i;
            }
        }
        nonces_[slot] = credcrypto::hexEncode(random, NONCE_BYTES);
        issuedAt_[slot] = nowMs;
        used_[slot] = true;
        return nonces_[slot];
    }

    // True exactly once for a live nonce; the nonce is burnt either way a match is found.
    bool consume(const std::string& nonce, uint32_t nowMs) {
        if (nonce.size() != NONCE_BYTES * 2) return false;
        for (int i = 0; i < NONCE_SLOTS; i++) {
            if (used_[i] && nonces_[i] == nonce) {
                used_[i] = false;
                return !expiredAt(issuedAt_[i], nowMs);
            }
        }
        return false;
    }

private:
    static bool expiredAt(uint32_t issued, uint32_t now) { return (uint32_t)(now - issued) > NONCE_TTL_MS; }
    bool expired(int i, uint32_t now) const { return expiredAt(issuedAt_[i], now); }

    std::string nonces_[NONCE_SLOTS];
    uint32_t issuedAt_[NONCE_SLOTS] = {0};
    bool used_[NONCE_SLOTS] = {false};
};

// Checks a signed request. Burns the nonce on any attempt that names a live nonce, so a wrong
// signature cannot be retried against the same nonce.
inline bool verifyRequest(const std::string& key, const std::string& action, const std::string& session,
                          const std::string& nonce, const std::string& sigHex, NonceStore& store, uint32_t nowMs) {
    if (key.size() < MIN_KEY_LENGTH || !validSessionId(session)) return false;
    if (!store.consume(nonce, nowMs)) return false;
    std::string expected = signRequest(key, action, session, nonce);
    if (expected.empty() || expected.size() != sigHex.size()) return false;
    return credcrypto::constantTimeEquals((const uint8_t*)expected.data(), (const uint8_t*)sigHex.data(),
                                          expected.size());
}

} // namespace gatewayauth

#endif // GATEWAY_AUTH_H
