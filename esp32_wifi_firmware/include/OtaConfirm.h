#ifndef OTA_CONFIRM_H
#define OTA_CONFIRM_H

// Pure C++ decision rule for OTA trial confirmation vs automatic rollback.

namespace otaconfirm {

static const unsigned long MIN_STABLE_MS = 60UL * 1000UL;
static const unsigned long GIVE_UP_MS = 5UL * 60UL * 1000UL;

struct Health {
    bool storageReady = false;
    bool wifiRequired = false;
    bool wifiUp = false;
};

enum class Action { Wait, Confirm, Rollback };

inline bool healthy(const Health& h) {
    return h.storageReady && (!h.wifiRequired || h.wifiUp);
}

inline const char* firstFailure(const Health& h) {
    if (!h.storageReady) return "payment-storage";
    if (h.wifiRequired && !h.wifiUp) return "wifi";
    return "";
}

inline Action decide(unsigned long uptimeMs, const Health& h) {
    if (uptimeMs < MIN_STABLE_MS) return Action::Wait;
    if (healthy(h)) return Action::Confirm;
    return uptimeMs >= GIVE_UP_MS ? Action::Rollback : Action::Wait;
}

} // namespace otaconfirm

#endif // OTA_CONFIRM_H
