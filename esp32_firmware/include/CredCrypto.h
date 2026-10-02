#ifndef CRED_CRYPTO_H
#define CRED_CRYPTO_H

// Pure (no Arduino types) helpers for remotely managed super-admin credentials, built only on
// mbedtls so the exact code that runs on the ESP32 is also compiled and tested on a PC
// (host_tests/cred_crypto_test.cpp).
//
// The website publishes {"version","iter","salt","hash","sig"}. "hash" is
// PBKDF2-HMAC-SHA256(password, salt, iter) and "sig" is an ECDSA P-256/SHA-256 signature over
// canonicalMessage(). The ESP32 only trusts a file whose signature verifies against the public
// key built into the firmware and whose version is higher than the one it already holds.

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include <mbedtls/base64.h>
#include <mbedtls/md.h>
#include <mbedtls/pk.h>

namespace credcrypto {

static const uint32_t MAX_ITERATIONS = 200000;
static const size_t HASH_BYTES = 32;

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
        if (hi < 0 || lo < 0) { out.clear(); return false; }
        out.push_back((uint8_t)((hi << 4) | lo));
    }
    return true;
}

inline std::string hexEncode(const uint8_t* data, size_t len) {
    static const char* d = "0123456789abcdef";
    std::string s;
    s.reserve(len * 2);
    for (size_t i = 0; i < len; i++) { s += d[data[i] >> 4]; s += d[data[i] & 15]; }
    return s;
}

inline bool base64Decode(const std::string& in, std::vector<uint8_t>& out) {
    out.assign(in.size() + 4, 0);
    size_t n = 0;
    if (mbedtls_base64_decode(out.data(), out.size(), &n,
                              (const unsigned char*)in.data(), in.size()) != 0) {
        out.clear();
        return false;
    }
    out.resize(n);
    return n > 0;
}

// Compares without leaking where the first difference is.
inline bool constantTimeEquals(const uint8_t* a, const uint8_t* b, size_t len) {
    uint8_t diff = 0;
    for (size_t i = 0; i < len; i++) diff |= (uint8_t)(a[i] ^ b[i]);
    return diff == 0;
}

inline bool hmacSha256(const uint8_t* key, size_t keyLen, const uint8_t* msg, size_t msgLen,
                       uint8_t out[32]) {
    const mbedtls_md_info_t* info = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
    if (!info) return false;
    return mbedtls_md_hmac(info, key, keyLen, msg, msgLen, out) == 0;
}

// RFC 8018 PBKDF2 with HMAC-SHA256 and a single 32-byte output block.
inline bool pbkdf2Sha256(const std::string& password, const std::vector<uint8_t>& salt,
                         uint32_t iterations, uint8_t out[32]) {
    if (iterations == 0 || iterations > MAX_ITERATIONS) return false;
    const uint8_t* key = (const uint8_t*)password.data();
    size_t keyLen = password.size();

    std::vector<uint8_t> first(salt);
    first.push_back(0); first.push_back(0); first.push_back(0); first.push_back(1);

    uint8_t u[32], t[32];
    if (!hmacSha256(key, keyLen, first.data(), first.size(), u)) return false;
    memcpy(t, u, 32);
    for (uint32_t i = 1; i < iterations; i++) {
        if (!hmacSha256(key, keyLen, u, 32, u)) return false;
        for (int j = 0; j < 32; j++) t[j] ^= u[j];
    }
    memcpy(out, t, 32);
    return true;
}

// True when `password` produces the stored hash for the given salt/iterations.
inline bool passwordMatches(const std::string& password, const std::string& saltHex,
                            uint32_t iterations, const std::string& hashHex) {
    std::vector<uint8_t> salt, expected;
    if (!hexDecode(saltHex, salt) || !hexDecode(hashHex, expected)) return false;
    if (expected.size() != HASH_BYTES) return false;
    uint8_t got[32];
    if (!pbkdf2Sha256(password, salt, iterations, got)) return false;
    return constantTimeEquals(got, expected.data(), HASH_BYTES);
}

// The exact text that is signed. A fixed tag keeps this signature from being valid for any other
// purpose should the same key ever sign something else.
inline std::string canonicalMessage(uint32_t version, uint32_t iterations,
                                    const std::string& saltHex, const std::string& hashHex) {
    return "pisophone-superadmin-v1|" + std::to_string(version) + "|" +
           std::to_string(iterations) + "|" + saltHex + "|" + hashHex;
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
    if (!info || mbedtls_md(info, (const unsigned char*)message.data(), message.size(), digest) != 0)
        return false;

    mbedtls_pk_context pk;
    mbedtls_pk_init(&pk);
    bool ok = false;
    if (mbedtls_pk_parse_public_key(&pk, pubKeyDer, pubKeyLen) == 0 &&
        mbedtls_pk_can_do(&pk, MBEDTLS_PK_ECDSA)) {
        ok = mbedtls_pk_verify(&pk, MBEDTLS_MD_SHA256, digest, sizeof(digest), sig.data(),
                               sig.size()) == 0;
    }
    mbedtls_pk_free(&pk);
    return ok;
}

struct SignedCredentials {
    uint32_t version = 0;
    uint32_t iterations = 0;
    std::string saltHex;
    std::string hashHex;
};

enum class CredCheck { Ok, BadFormat, BadSignature, NotNewer };

// Decides whether a downloaded credentials file may replace the stored one.
inline CredCheck checkCredentials(const SignedCredentials& c, const std::string& sigBase64,
                                  const uint8_t* pubKeyDer, size_t pubKeyLen,
                                  uint32_t currentVersion) {
    std::vector<uint8_t> salt, hash;
    if (c.version == 0 || c.iterations == 0 || c.iterations > MAX_ITERATIONS ||
        !hexDecode(c.saltHex, salt) || salt.size() < 8 || salt.size() > 64 ||
        !hexDecode(c.hashHex, hash) || hash.size() != HASH_BYTES) {
        return CredCheck::BadFormat;
    }
    if (!verifySignature(pubKeyDer, pubKeyLen,
                         canonicalMessage(c.version, c.iterations, c.saltHex, c.hashHex),
                         sigBase64)) {
        return CredCheck::BadSignature;
    }
    if (c.version <= currentVersion) return CredCheck::NotNewer;
    return CredCheck::Ok;
}

}  // namespace credcrypto

#endif  // CRED_CRYPTO_H
