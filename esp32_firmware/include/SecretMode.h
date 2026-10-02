#ifndef SECRET_MODE_H
#define SECRET_MODE_H

// Pure (no Arduino types) rules for the box<->phone secret (host_tests/secret_mode_test.cpp).
//
// Every box owns a random secret that only it and its paired phones know. Boxes already in the field
// were running on the old shared key, so on the first start of this firmware they stay in "legacy
// mode" (old key, nothing changes for their phones) until the operator switches them to their own
// key and re-provisions the phones. A box that has never been configured starts on its own key.

#include <cstddef>
#include <string>

namespace secretmode {

// hasFlag/flagValue: the stored "legacy mode" setting. wasConfigured: the box already had settings
// saved before this firmware first ran (Wi-Fi set), i.e. it is an upgrade, not a fresh box.
inline bool startInLegacyMode(bool hasFlag, bool flagValue, bool wasConfigured) {
    if (hasFlag) return flagValue;
    return wasConfigured;
}

static const size_t MIN_SECRET_LEN = 16;
static const size_t MAX_SECRET_LEN = 128;

// Same character rule as the provisioning website, so a secret can travel in a link and an adb extra.
inline bool validSecret(const std::string& s) {
    if (s.size() < MIN_SECRET_LEN || s.size() > MAX_SECRET_LEN) return false;
    for (char c : s) {
        bool ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' ||
                  c == '+' || c == '=' || c == '-';
        if (!ok) return false;
    }
    return true;
}

} // namespace secretmode

#endif // SECRET_MODE_H
