#ifndef LICENSE_CRYPTO_H
#define LICENSE_CRYPTO_H

// Pure (no Arduino types) slot-license check. A license is signed offline with the owner's private
// key (scripts/generate_license.py) and verified here with the public key built into the firmware,
// so a box can verify licenses but never make one. Compiled and tested on a PC
// (host_tests/license_crypto_test.cpp).
//
// Token: PISOLIC1.<MAC, 12 hex>.<slots>.<base64 DER ECDSA P-256 signature>
// Signed text: pisophone-license-v1|<MAC upper-case hex>|<slots>

#include "CredCrypto.h"

namespace licensecrypto {

enum class LicenseCheck { Ok, NoKey, BadFormat, WrongBox, BadSignature };

inline std::string canonicalMessage(const std::string& macClean, uint32_t slots) {
    return "pisophone-license-v1|" + macClean + "|" + std::to_string(slots);
}

inline std::string cleanMac(const std::string& mac) {
    std::string out;
    for (char c : mac) {
        if (c == ':' || c == '-' || c == ' ') continue;
        out += (char)((c >= 'a' && c <= 'f') ? c - 'a' + 'A' : c);
    }
    return out;
}

// True when the text is a signed-license token (as opposed to an old-style key).
inline bool looksSigned(const std::string& token) {
    return token.rfind("PISOLIC1.", 0) == 0;
}

// Checks a token against this box's MAC. On Ok, `slotsOut` holds the licensed slot count.
inline LicenseCheck checkToken(const std::string& tokenRaw, const std::string& boxMac, const uint8_t* pubKeyDer,
                               size_t pubKeyLen, uint32_t maxSlots, uint32_t& slotsOut) {
    if (!pubKeyDer || pubKeyLen == 0) return LicenseCheck::NoKey;

    std::string token;
    for (char c : tokenRaw)
        if (c != ' ' && c != '\r' && c != '\n' && c != '\t') token += c;

    // PISOLIC1 . MAC . slots . signature (the base64 signature never contains '.')
    size_t p1 = token.find('.');
    size_t p2 = p1 == std::string::npos ? p1 : token.find('.', p1 + 1);
    size_t p3 = p2 == std::string::npos ? p2 : token.find('.', p2 + 1);
    if (p3 == std::string::npos || token.compare(0, p1, "PISOLIC1") != 0) return LicenseCheck::BadFormat;

    std::string mac = token.substr(p1 + 1, p2 - p1 - 1);
    std::string slotText = token.substr(p2 + 1, p3 - p2 - 1);
    std::string sig = token.substr(p3 + 1);
    if (mac.size() != 12 || slotText.empty() || slotText.size() > 2 || sig.empty()) return LicenseCheck::BadFormat;
    for (char c : mac)
        if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F'))) return LicenseCheck::BadFormat;
    uint32_t slots = 0;
    for (char c : slotText) {
        if (c < '0' || c > '9') return LicenseCheck::BadFormat;
        slots = slots * 10 + (uint32_t)(c - '0');
    }
    if (slots == 0 || slots > maxSlots) return LicenseCheck::BadFormat;

    if (mac != cleanMac(boxMac)) return LicenseCheck::WrongBox;
    if (!credcrypto::verifySignature(pubKeyDer, pubKeyLen, canonicalMessage(mac, slots), sig)) {
        return LicenseCheck::BadSignature;
    }
    slotsOut = slots;
    return LicenseCheck::Ok;
}

} // namespace licensecrypto

#endif // LICENSE_CRYPTO_H
