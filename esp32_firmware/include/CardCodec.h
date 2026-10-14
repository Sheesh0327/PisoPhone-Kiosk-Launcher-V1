#ifndef CARD_CODEC_H
#define CARD_CODEC_H

// Reads a QR card (see scripts/card_format.py, which writes the same format):
//
//     PISO1.<box>.<serial>.<seconds>.<signature>
//
// A card is accepted only when it is well formed, is meant for THIS box, and its signature (ECDSA P-256 over
// "pisophone-card-v1|<box>|<serial>|<seconds>", made with the card key) checks out against the public key built into
// CardPubKey.h. Pure and host-tested (host_tests/card_codec_test.cpp, which uses cards made by the Python tools).

#include "CredCrypto.h"

#include <cstdint>
#include <string>

namespace card {

static const uint32_t MAX_SERIAL = 65535;
static const uint32_t MIN_SECONDS = 60;
static const uint32_t MAX_SECONDS = 240UL * 3600;
static const size_t MAX_TEXT = 200;
static const size_t MAX_SIG_B64 = 100;

struct Card {
    std::string box; // 12 lower-case hex digits
    uint32_t serial = 0;
    uint32_t seconds = 0;
    std::string sigBase64;
};

enum class Check : uint8_t { OK, MALFORMED, WRONG_BOX, BAD_SIGNATURE, CARDS_OFF };

// 'AA:BB:CC:DD:EE:FF' or 'aabbccddeeff' -> 'aabbccddeeff'. Anything else comes back unchanged, and then never matches a box id.
inline std::string normalizeBox(const std::string& mac) {
    std::string out;
    for (char c : mac) {
        if (c == ':' || c == '-') continue;
        out += (c >= 'A' && c <= 'F') ? (char)(c - 'A' + 'a') : c;
    }
    return out;
}

inline bool isLowerHex12(const std::string& s) {
    if (s.size() != 12) return false;
    for (char c : s)
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
    return true;
}

inline bool parseDecimal(const std::string& s, uint32_t& out) {
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

inline bool isBase64(const std::string& s) {
    if (s.empty() || s.size() > MAX_SIG_B64 || s.size() % 4 != 0) return false;
    for (size_t i = 0; i < s.size(); i++) {
        char c = s[i];
        bool ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/';
        if (c == '=' && i + 2 >= s.size()) ok = true; // padding only in the last two places
        if (!ok) return false;
    }
    return true;
}

// Splits and range-checks the text. Does not look at the signature or the box.
inline bool parse(const std::string& text, Card& out) {
    if (text.empty() || text.size() > MAX_TEXT) return false;
    std::string parts[5];
    size_t n = 0, start = 0;
    for (size_t i = 0; i <= text.size(); i++) {
        if (i == text.size() || text[i] == '.') {
            if (n >= 5) return false;
            parts[n++] = text.substr(start, i - start);
            start = i + 1;
        }
    }
    if (n != 5 || parts[0] != "PISO1") return false;
    Card c;
    if (!isLowerHex12(parts[1])) return false;
    c.box = parts[1];
    if (!parseDecimal(parts[2], c.serial) || c.serial < 1 || c.serial > MAX_SERIAL) return false;
    if (!parseDecimal(parts[3], c.seconds) || c.seconds < MIN_SECONDS || c.seconds > MAX_SECONDS) return false;
    if (!isBase64(parts[4])) return false;
    c.sigBase64 = parts[4];
    out = c;
    return true;
}

inline std::string signedMessage(const Card& c) {
    return "pisophone-card-v1|" + c.box + "|" + std::to_string(c.serial) + "|" + std::to_string(c.seconds);
}

// The whole check. The cheap tests come first, so a card for another box is refused before any signature work is done.
// `myBox` is this box's MAC (any common spelling); `pubKeyDer` may be empty, which means no card key is installed.
inline Check verify(const std::string& text, const std::string& myBox, const uint8_t* pubKeyDer, size_t pubKeyLen,
                    Card& out) {
    Card c;
    if (!parse(text, c)) return Check::MALFORMED;
    if (!pubKeyDer || pubKeyLen <= 1) return Check::CARDS_OFF; // the placeholder key is one zero byte
    if (c.box != normalizeBox(myBox)) return Check::WRONG_BOX;
    if (!credcrypto::verifySignature(pubKeyDer, pubKeyLen, signedMessage(c), c.sigBase64)) return Check::BAD_SIGNATURE;
    out = c;
    return Check::OK;
}

} // namespace card

#endif // CARD_CODEC_H
