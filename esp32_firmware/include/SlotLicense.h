#ifndef SLOT_LICENSE_H
#define SLOT_LICENSE_H

// How many phones a box may pair (host_tests/slot_license_test.cpp).
//
//   Every box includes FREE_SLOTS phone slots. Each further slot needs one license key (PRICE_PHP pesos), up to MAX_SLOTS.
//
// A license key is a line the owner signed with the offline owner key (scripts/make_slot_licenses.py). It names ONE box (its MAC
// address, the "box code" the dashboard shows) and carries its own number, so:
//   - it opens a slot on that box only, not on any other box;
//   - pasting the same key twice opens one slot, not two (the box remembers the numbers it has used);
//   - the box checks it with the public key built into the firmware, so it works offline and cannot be forged.
// A key cannot be taken back once used, and it survives an operator's factory reset (OwnerData.h). Phones already paired above the
// limit keep working; only pairing one more is refused.
//
// Boxes already in shops when licensing arrived are grandfathered: on its first start with licensing a box records how many phones it
// had paired, and never offers fewer slots than that (`floor` below), so a shop can still replace a broken phone. Licenses add to the
// slots above that floor only when the box has more keys than it needs: cap = max(FREE_SLOTS + keys, floor), at most MAX_SLOTS.
//
// Signed text: pisophone-slot-v1|<mac, 12 lowercase hex digits>|<id, 8 lowercase hex digits>
// Key the owner pastes into the dashboard: PSLOT1.<mac>.<id>.<signature, base64>

#include "CredCrypto.h"

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace slotlicense {

static const int FREE_SLOTS = 2;
static const int MAX_SLOTS = 12; // the most phones any box can hold (MAX_SUPPORTED_SLOTS in Config.h)
static const int MAX_KEYS = MAX_SLOTS - FREE_SLOTS;
static const int PRICE_PHP =
    500; // the price of one extra slot (the dashboard says so; the owner may charge what they like)
static const char* const PREFIX = "PSLOT1";

enum class Check { Ok, NoKey, BadFormat, WrongBox, BadSignature, AlreadyUsed, NoRoom };

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

// 8 hex digits (any case) -> lowercase; empty when it is anything else.
inline std::string normalizeId(const std::string& id) {
    std::string out;
    for (char c : id) {
        if (c >= 'A' && c <= 'F') c = static_cast<char>(c - 'A' + 'a');
        bool hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
        if (!hex) return "";
        out += c;
    }
    return out.size() == 8 ? out : "";
}

inline std::string canonicalMessage(const std::string& macHex, const std::string& idHex) {
    return std::string("pisophone-slot-v1|") + macHex + "|" + idHex;
}

// The numbers of the keys already used, kept as "id,id,id" (at most MAX_KEYS of them, so it always fits a settings entry).
inline std::vector<std::string> splitIds(const std::string& csv) {
    std::vector<std::string> ids;
    size_t start = 0;
    while (start <= csv.size()) {
        size_t comma = csv.find(',', start);
        if (comma == std::string::npos) comma = csv.size();
        std::string id = normalizeId(csv.substr(start, comma - start));
        bool seen = false;
        for (const std::string& have : ids)
            if (have == id) seen = true;
        if (!id.empty() && !seen && static_cast<int>(ids.size()) < MAX_KEYS) ids.push_back(id);
        start = comma + 1;
    }
    return ids;
}

inline std::string joinIds(const std::vector<std::string>& ids) {
    std::string out;
    for (const std::string& id : ids)
        out += (out.empty() ? "" : ",") + id;
    return out;
}

inline bool hasId(const std::string& csv, const std::string& id) {
    for (const std::string& have : splitIds(csv))
        if (have == id) return true;
    return false;
}

// How many phones a box with `used` license keys may pair.
inline int capFor(int used) {
    if (used < 0) used = 0;
    if (used > MAX_KEYS) used = MAX_KEYS;
    return FREE_SLOTS + used;
}

// The most phones a box may pair: what its keys give, or the number it had paired before licensing (the grandfathered floor).
inline int capWithFloor(int used, int floor) {
    int cap = capFor(used);
    if (floor > cap) cap = floor > MAX_SLOTS ? MAX_SLOTS : floor;
    return cap;
}

inline int usedKeys(const std::string& csv) {
    return static_cast<int>(splitIds(csv).size());
}

// Checks a key against THIS box's address, the owner's public key and the numbers already used. On Ok, `idOut` is the key's
// number: the caller saves it (and so opens one slot).
inline Check verifyToken(const std::string& token, const std::string& thisMac, const uint8_t* pubKeyDer,
                         size_t pubKeyLen, const std::string& usedCsv, std::string& idOut) {
    if (!pubKeyDer || pubKeyLen == 0) return Check::NoKey;
    size_t first = token.find_first_not_of(" \t\r\n");
    size_t last = token.find_last_not_of(" \t\r\n");
    if (first == std::string::npos) return Check::BadFormat;
    std::string t = token.substr(first, last - first + 1);
    size_t d1 = t.find('.');
    size_t d2 = d1 == std::string::npos ? d1 : t.find('.', d1 + 1);
    size_t d3 = d2 == std::string::npos ? d2 : t.find('.', d2 + 1);
    if (d3 == std::string::npos || t.find('.', d3 + 1) != std::string::npos) return Check::BadFormat;
    std::string mac = normalizeMac(t.substr(d1 + 1, d2 - d1 - 1));
    std::string id = normalizeId(t.substr(d2 + 1, d3 - d2 - 1));
    std::string sig = t.substr(d3 + 1);
    std::string own = normalizeMac(thisMac);
    if (t.substr(0, d1) != PREFIX || mac.empty() || id.empty() || own.empty() || sig.size() < 40 || sig.size() > 200)
        return Check::BadFormat;
    if (mac != own) return Check::WrongBox;
    if (!credcrypto::verifySignature(pubKeyDer, pubKeyLen, canonicalMessage(mac, id), sig)) return Check::BadSignature;
    if (hasId(usedCsv, id)) return Check::AlreadyUsed;
    if (usedKeys(usedCsv) >= MAX_KEYS) return Check::NoRoom;
    idOut = id;
    return Check::Ok;
}

// May this pairing go ahead? `pairedAfter` is how many phones would be paired once it is done. A pairing that adds no phone
// (the phone is already paired, or takes over an occupied slot) is always allowed, even on a box that already holds more
// than its licenses allow.
inline bool mayPair(int cap, int pairedNow, int pairedAfter) {
    return pairedAfter <= pairedNow || pairedAfter <= cap;
}

} // namespace slotlicense

#endif // SLOT_LICENSE_H
