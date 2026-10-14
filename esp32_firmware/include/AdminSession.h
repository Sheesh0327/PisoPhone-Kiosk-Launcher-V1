#ifndef ADMIN_SESSION_H
#define ADMIN_SESSION_H

// Browser sessions after a correct admin password (no Arduino types, so host_tests/admin_session_test.cpp runs the exact code the
// box runs).
//
// The browser's own password cache is not reliable for the dashboard's background requests (some browsers do not send it on
// scripted requests), and a request without it looks like a sign-out. So when the admin password has been accepted for a page, the
// box also hands the browser a random token in a cookie, and the polls carry that instead. The password itself is still what
// starts a session; the token only proves "this browser already gave it". A session ends after 30 idle minutes or 12 hours at
// most, when the admin password is changed (endAll), or when the box restarts. A few browsers can be signed in at once.

#include <cstdint>
#include <string>

#include "CredCrypto.h"

namespace adminsession {

static const unsigned long IDLE_MS = 30UL * 60UL * 1000UL;
static const unsigned long MAX_MS = 12UL * 60UL * 60UL * 1000UL;
static const size_t RANDOM_BYTES = 16;
static const int SLOTS = 6;

class Sessions {
public:
    // A new session (32 hex characters from 16 random bytes). When all slots are in use the one idle the longest is replaced.
    std::string start(const uint8_t random[RANDOM_BYTES], unsigned long now) {
        int pick = 0;
        for (int i = 0; i < SLOTS; i++) {
            if (!live(slots_[i], now)) {
                pick = i;
                break;
            }
            if (now - slots_[i].lastUsedAt > now - slots_[pick].lastUsedAt) pick = i;
        }
        slots_[pick].token = credcrypto::hexEncode(random, RANDOM_BYTES);
        slots_[pick].startedAt = slots_[pick].lastUsedAt = now;
        return slots_[pick].token;
    }

    // True while the token belongs to a live session; every use keeps it alive (up to MAX_MS from when it started).
    bool valid(const std::string& token, unsigned long now) {
        if (token.empty()) return false;
        for (int i = 0; i < SLOTS; i++) {
            Slot& s = slots_[i];
            if (s.token.size() != token.size() ||
                !credcrypto::constantTimeEquals((const uint8_t*)token.data(), (const uint8_t*)s.token.data(),
                                                token.size())) {
                continue;
            }
            if (!live(s, now)) {
                s.token.clear();
                return false;
            }
            s.lastUsedAt = now;
            return true;
        }
        return false;
    }

    void end(const std::string& token) {
        for (int i = 0; i < SLOTS; i++) {
            if (!token.empty() && slots_[i].token == token) slots_[i].token.clear();
        }
    }

    void endAll() {
        for (int i = 0; i < SLOTS; i++)
            slots_[i].token.clear();
    }

private:
    struct Slot {
        std::string token;
        unsigned long startedAt = 0;
        unsigned long lastUsedAt = 0;
    };
    static bool live(const Slot& s, unsigned long now) {
        return !s.token.empty() && now - s.lastUsedAt <= IDLE_MS && now - s.startedAt <= MAX_MS;
    }
    Slot slots_[SLOTS];
};

} // namespace adminsession

#endif // ADMIN_SESSION_H
