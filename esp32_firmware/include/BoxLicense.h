#ifndef BOX_LICENSE_H
#define BOX_LICENSE_H

// How many phones a box may pair, by the box's TYPE (host_tests/box_license_test.cpp).
//
//   purchased  a box the shop owner bought: up to 6 phones
//   rental     a box placed in a shop that the vendor keeps and rents out: up to 12 phones
//
// The type can be changed only by the owner: either with a token signed by the offline owner key
// (scripts/sign_box_type.py; it names the box's own MAC address, so it cannot be copied to another box) or while signed
// in with the admin QR code. A box with neither starts as "purchased" (the smaller number). Phones already paired
// above the limit (a box that had 10 before this rule existed) keep working; only pairing one more is refused.
//
// Signed text: pisohub-box-v1|<mac, 12 lowercase hex digits>|<purchased|rental>
// Token the owner pastes into the dashboard: <type>:<mac>:<signature, base64>

#include "CredCrypto.h"

#include <cstddef>
#include <cstdint>
#include <string>

namespace boxlicense {

static const int CAP_PURCHASED = 6;
static const int CAP_RENTAL = 12;

enum class Type { Purchased, Rental };
enum class Check { Ok, NoKey, BadFormat, WrongBox, BadSignature };

inline const char* name(Type t) {
    return t == Type::Rental ? "rental" : "purchased";
}

inline int capFor(Type t) {
    return t == Type::Rental ? CAP_RENTAL : CAP_PURCHASED;
}

inline bool parseType(const std::string& s, Type& out) {
    if (s == "purchased") {
        out = Type::Purchased;
        return true;
    }
    if (s == "rental") {
        out = Type::Rental;
        return true;
    }
    return false;
}

// "AA:BB:CC:DD:EE:FF", "aa-bb-cc-dd-ee-ff" or "aabbccddeeff" -> "aabbccddeeff"; empty when it is not a MAC address.
inline std::string normalizeMac(const std::string& mac) {
    std::string out;
    for (char c : mac) {
        if (c == ':' || c == '-') continue;
        if (c >= 'A' && c <= 'F') c = static_cast<char>(c - 'A' + 'a');
        bool hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
        if (!hex) return "";
        out += c;
    }
    return out.size() == 12 ? out : "";
}

inline std::string canonicalMessage(const std::string& macHex, Type t) {
    return "pisohub-box-v1|" + macHex + "|" + name(t);
}

// Checks a token against THIS box's address and the owner's public key; on Ok, `out` is the type it grants.
inline Check verifyToken(const std::string& token, const std::string& thisMac, const uint8_t* pubKeyDer,
                         size_t pubKeyLen, Type& out) {
    if (!pubKeyDer || pubKeyLen == 0) return Check::NoKey;
    size_t a = token.find(':');
    size_t b = (a == std::string::npos) ? std::string::npos : token.find(':', a + 1);
    if (a == std::string::npos || b == std::string::npos) return Check::BadFormat;
    Type t;
    std::string mac = normalizeMac(token.substr(a + 1, b - a - 1));
    std::string own = normalizeMac(thisMac);
    std::string sig = token.substr(b + 1);
    while (!sig.empty() && (sig.back() == '\r' || sig.back() == '\n' || sig.back() == ' '))
        sig.pop_back();
    if (!parseType(token.substr(0, a), t) || mac.empty() || own.empty() || sig.size() < 40 || sig.size() > 200)
        return Check::BadFormat;
    if (mac != own) return Check::WrongBox;
    if (!credcrypto::verifySignature(pubKeyDer, pubKeyLen, canonicalMessage(mac, t), sig)) return Check::BadSignature;
    out = t;
    return Check::Ok;
}

// May this pairing go ahead? `pairedAfter` is how many phones would be paired once it is done. A pairing that adds no phone
// (the phone is already paired, or takes over an occupied slot) is always allowed, even on a box that already holds more
// than its type allows (a box that had up to 10 before this rule existed).
inline bool mayPair(Type t, int pairedNow, int pairedAfter) {
    return pairedAfter <= pairedNow || pairedAfter <= capFor(t);
}

} // namespace boxlicense

#endif // BOX_LICENSE_H
