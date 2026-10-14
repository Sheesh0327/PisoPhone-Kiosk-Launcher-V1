#ifndef OTA_CONFIRM_H
#define OTA_CONFIRM_H

// When a freshly updated firmware has earned its place (host_tests/ota_confirm_test.cpp). Pure C++, no Arduino types.
//
// After an over-the-air update the bootloader starts the new image as "pending verification". If the box resets before the
// image is confirmed (a crash, a watchdog reset, a power cut), the bootloader goes back to the previous image by itself.
// That only protects anything if the new image is NOT confirmed the moment it starts, so the Arduino core's automatic
// confirmation is switched off (OtaRollback.cpp) and this rule decides instead:
//   - never before MIN_STABLE_MS of running: a crash that comes a few seconds in must still roll back;
//   - then confirm as soon as the checks pass: the payment storage can be written and, when the box was on Wi-Fi at the time
//     of the update, it is on Wi-Fi again;
//   - if they have not passed after GIVE_UP_MS, the new image is judged bad and the previous one is restored.
// Whether it is safe to restart for a rollback (no coin session open, no payment only in RAM) is the caller's concern.

namespace otaconfirm {

static const unsigned long MIN_STABLE_MS = 60UL * 1000UL;
static const unsigned long GIVE_UP_MS = 5UL * 60UL * 1000UL;

struct Health {
    bool storageReady = false; // payments can be written to flash
    bool wifiRequired = false; // the box was on Wi-Fi when this update arrived, so a working update gets back on it
    bool wifiUp = false;
};

enum class Action { Wait, Confirm, Rollback };

inline bool healthy(const Health& h) {
    return h.storageReady && (!h.wifiRequired || h.wifiUp);
}

// The first check that is failing, for the log ("" when none is).
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
