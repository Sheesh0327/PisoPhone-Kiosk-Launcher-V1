#ifndef CRED_CRYPTO_H
#define CRED_CRYPTO_H

// Pure (no Arduino types) crypto helpers, built only on mbedtls so the exact code that runs on the ESP32 is also compiled and
// tested on a PC (host_tests/cred_crypto_test.cpp): hex and base64, constant-time compare, HMAC-SHA256 and the check of an
// ECDSA P-256 / SHA-256 signature against a DER public key (firmware manifests, time cards, the owner's admin QR login).
// (The file name is historical: it once also held the remotely published super-admin password.)

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include <mbedtls/base64.h>
#include <mbedtls/md.h>
#include <mbedtls/pk.h>

namespace credcrypto {

inline bool hexDecode(const std::string& hex, std::vector<uint8_t>& out) {
    out.clear();
    if (hex.empty() || hex.size() % 2 != 0) return false;
    auto nib = [](char c) -> int {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    };
    for (size_t i = 0; i < hex.size(); i += 2) {
        int hi = nib(hex[i]), lo = nib(hex[i + 1]);
        if (hi < 0 || lo < 0) {
            out.clear();
            return false;
        }
        out.push_back((uint8_t)((hi << 4) | lo));
    }
    return true;
}

inline std::string hexEncode(const uint8_t* data, size_t len) {
    static const char* d = "0123456789abcdef";
    std::string s;
    s.reserve(len * 2);
    for (size_t i = 0; i < len; i++) {
        s += d[data[i] >> 4];
        s += d[data[i] & 15];
    }
    return s;
}

inline bool base64Decode(const std::string& in, std::vector<uint8_t>& out) {
    out.assign(in.size() + 4, 0);
    size_t n = 0;
    if (mbedtls_base64_decode(out.data(), out.size(), &n, (const unsigned char*)in.data(), in.size()) != 0) {
        out.clear();
        return false;
    }
    out.resize(n);
    return n > 0;
}

// Compares without leaking where the first difference is.
inline bool constantTimeEquals(const uint8_t* a, const uint8_t* b, size_t len) {
    uint8_t diff = 0;
    for (size_t i = 0; i < len; i++)
        diff |= (uint8_t)(a[i] ^ b[i]);
    return diff == 0;
}

inline bool hmacSha256(const uint8_t* key, size_t keyLen, const uint8_t* msg, size_t msgLen, uint8_t out[32]) {
    const mbedtls_md_info_t* info = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
    if (!info) return false;
    return mbedtls_md_hmac(info, key, keyLen, msg, msgLen, out) == 0;
}

// Verifies a DER ECDSA P-256 signature (base64) over `message` against a DER
// SubjectPublicKeyInfo public key.
inline bool verifySignature(const uint8_t* pubKeyDer, size_t pubKeyLen, const std::string& message,
                            const std::string& sigBase64) {
    if (!pubKeyDer || pubKeyLen == 0) return false;
    std::vector<uint8_t> sig;
    if (!base64Decode(sigBase64, sig)) return false;

    const mbedtls_md_info_t* info = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
    uint8_t digest[32];
    if (!info || mbedtls_md(info, (const unsigned char*)message.data(), message.size(), digest) != 0) return false;

    mbedtls_pk_context pk;
    mbedtls_pk_init(&pk);
    bool ok = false;
    if (mbedtls_pk_parse_public_key(&pk, pubKeyDer, pubKeyLen) == 0 && mbedtls_pk_can_do(&pk, MBEDTLS_PK_ECDSA)) {
        ok = mbedtls_pk_verify(&pk, MBEDTLS_MD_SHA256, digest, sizeof(digest), sig.data(), sig.size()) == 0;
    }
    mbedtls_pk_free(&pk);
    return ok;
}

} // namespace credcrypto

#endif // CRED_CRYPTO_H
