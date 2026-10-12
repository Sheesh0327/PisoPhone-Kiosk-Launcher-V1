#ifndef ADMIN_QR_AUTH_H
#define ADMIN_QR_AUTH_H

// The owner's QR login (no Arduino types, so host_tests/admin_qr_test.cpp runs the exact code the box runs).
//
// The owner holds a QR code that contains a private key. The box holds only the matching PUBLIC key (SuperAdminPubKey.h).
// To log in the page asks the box for a challenge (a random nonce), scans the QR code in the browser, signs
// "pisophone-admin-v1|<nonce>|<box id>" with the key in it and sends the signature back. The key never leaves the phone; the
// signature is good for this box and this one nonce only. A box that accepts the signature starts a session (a random
// token the page keeps in a cookie) that ends after 15 idle minutes or 1 hour at most.

#include <cstdint>
#include <cstring>
#include <string>

#include "CredCrypto.h"

namespace adminqr {

static const unsigned long NONCE_TTL_MS = 60UL * 1000UL;  // a challenge can be answered for a minute, once
static const unsigned long NONCE_REUSE_MS = 5UL * 1000UL; // a repeated request within 5 s gets the same challenge
static const unsigned long SESSION_IDLE_MS = 15UL * 60UL * 1000UL;
static const unsigned long SESSION_MAX_MS = 60UL * 60UL * 1000UL;
static const size_t RANDOM_BYTES = 16;
static const int NONCE_SLOTS = 4;

inline std::string signedMessage(const std::string& nonce, const std::string& boxId) {
    return "pisophone-admin-v1|" + nonce + "|" + boxId;
}

enum class Result { Ok, UnknownNonce, BadSignature, NoKey };

class Auth {
public:
    // A new challenge (32 hex characters) from 16 random bytes. A request that follows another within NONCE_REUSE_MS gets the
    // same challenge back, so nobody can keep the owner's challenge from being valid by asking for new ones.
    std::string issueChallenge(const uint8_t random[RANDOM_BYTES], unsigned long now) {
        Slot& last = slots_[lastSlot_];
        if (last.used && now - last.issuedAt < NONCE_REUSE_MS) return last.nonce;
        lastSlot_ = (lastSlot_ + 1) % NONCE_SLOTS;
        Slot& s = slots_[lastSlot_];
        s.used = true;
        s.issuedAt = now;
        s.nonce = credcrypto::hexEncode(random, RANDOM_BYTES);
        return s.nonce;
    }

    // Checks the signature for a challenge that was issued here and is still valid. The challenge is used up whatever the
    // answer: one try per challenge. On Ok the session token (32 hex characters, from 16 random bytes) is in tokenOut.
    Result login(const std::string& nonce, const std::string& sigBase64, const std::string& boxId,
                 const uint8_t* pubKeyDer, size_t pubKeyLen, const uint8_t random[RANDOM_BYTES], unsigned long now,
                 std::string& tokenOut) {
        tokenOut.clear();
        Slot* found = nullptr;
        for (int i = 0; i < NONCE_SLOTS; i++) {
            if (slots_[i].used && now - slots_[i].issuedAt <= NONCE_TTL_MS && slots_[i].nonce == nonce)
                found = &slots_[i];
        }
        if (!found) return Result::UnknownNonce;
        found->used = false;
        if (!pubKeyDer || pubKeyLen == 0) return Result::NoKey;
        if (!credcrypto::verifySignature(pubKeyDer, pubKeyLen, signedMessage(nonce, boxId), sigBase64)) {
            return Result::BadSignature;
        }
        token_ = credcrypto::hexEncode(random, RANDOM_BYTES);
        startedAt_ = lastUsedAt_ = now;
        tokenOut = token_;
        return Result::Ok;
    }

    // True while the token is the current session's; every use keeps it alive (up to SESSION_MAX_MS from the login).
    bool sessionValid(const std::string& token, unsigned long now) {
        if (token_.empty() || token.size() != token_.size()) return false;
        if (!credcrypto::constantTimeEquals((const uint8_t*)token.data(), (const uint8_t*)token_.data(),
                                            token.size())) {
            return false;
        }
        if (now - lastUsedAt_ > SESSION_IDLE_MS || now - startedAt_ > SESSION_MAX_MS) {
            token_.clear();
            return false;
        }
        lastUsedAt_ = now;
        return true;
    }

    void end() { token_.clear(); }
    bool active() const { return !token_.empty(); }

private:
    struct Slot {
        bool used = false;
        unsigned long issuedAt = 0;
        std::string nonce;
    };
    Slot slots_[NONCE_SLOTS];
    int lastSlot_ = 0;
    std::string token_;
    unsigned long startedAt_ = 0;
    unsigned long lastUsedAt_ = 0;
};

} // namespace adminqr

#endif // ADMIN_QR_AUTH_H
