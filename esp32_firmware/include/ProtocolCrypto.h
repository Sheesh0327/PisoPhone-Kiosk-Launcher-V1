#ifndef PROTOCOL_CRYPTO_H
#define PROTOCOL_CRYPTO_H

// Pure (no Arduino types) box<->phone message crypto, compiled on the ESP32 and on a PC so both the
// firmware and the host test run the same code (host_tests/protocol_contract_test.cpp checks it against
// protocol/fixtures/box_phone_v1.json, which the phone's tests check too).
//
//   key       = SHA-256(secret)
//   encrypt   = hex(iv) + hex(AES-256-CBC with PKCS#7 padding)
//   signature = hex(HMAC-SHA256(secret, message))   (lower-case hex)

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include <mbedtls/aes.h>
#include <mbedtls/md.h>

namespace protocol {

inline std::string toHex(const uint8_t* data, size_t len) {
    static const char* d = "0123456789abcdef";
    std::string s;
    s.reserve(len * 2);
    for (size_t i = 0; i < len; i++) {
        s += d[data[i] >> 4];
        s += d[data[i] & 15];
    }
    return s;
}

inline bool deriveKey(const std::string& secret, uint8_t key[32]) {
    const mbedtls_md_info_t* info = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
    return info && mbedtls_md(info, (const unsigned char*)secret.data(), secret.size(), key) == 0;
}

inline std::string hmacHex(const std::string& message, const std::string& secret) {
    const mbedtls_md_info_t* info = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
    uint8_t out[32];
    if (!info || mbedtls_md_hmac(info, (const unsigned char*)secret.data(), secret.size(),
                                 (const unsigned char*)message.data(), message.size(), out) != 0) {
        return "";
    }
    return toHex(out, 32);
}

// Returns "" on failure.
inline std::string encryptHex(const std::string& plain, const std::string& secret, const uint8_t iv[16]) {
    uint8_t key[32];
    if (!deriveKey(secret, key)) return "";
    size_t pad = 16 - (plain.size() % 16);
    std::vector<uint8_t> in(plain.begin(), plain.end());
    in.insert(in.end(), pad, (uint8_t)pad);
    std::vector<uint8_t> out(in.size());
    uint8_t ivWork[16];
    memcpy(ivWork, iv, 16);

    mbedtls_aes_context ctx;
    mbedtls_aes_init(&ctx);
    bool ok = mbedtls_aes_setkey_enc(&ctx, key, 256) == 0 &&
              mbedtls_aes_crypt_cbc(&ctx, MBEDTLS_AES_ENCRYPT, in.size(), ivWork, in.data(), out.data()) == 0;
    mbedtls_aes_free(&ctx);
    if (!ok) return "";
    return toHex(iv, 16) + toHex(out.data(), out.size());
}

inline int hexNibble(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

// Inverse of encryptHex. Returns false on bad hex, bad length or bad padding.
inline bool decryptHex(const std::string& hex, const std::string& secret, std::string& plainOut) {
    if (hex.size() < 64 || hex.size() % 2 != 0) return false; // iv + at least one block
    std::vector<uint8_t> raw;
    for (size_t i = 0; i < hex.size(); i += 2) {
        int hi = hexNibble(hex[i]), lo = hexNibble(hex[i + 1]);
        if (hi < 0 || lo < 0) return false;
        raw.push_back((uint8_t)((hi << 4) | lo));
    }
    size_t cipherLen = raw.size() - 16;
    if (cipherLen % 16 != 0) return false;
    uint8_t key[32];
    if (!deriveKey(secret, key)) return false;
    uint8_t iv[16];
    memcpy(iv, raw.data(), 16);
    std::vector<uint8_t> out(cipherLen);

    mbedtls_aes_context ctx;
    mbedtls_aes_init(&ctx);
    bool ok = mbedtls_aes_setkey_dec(&ctx, key, 256) == 0 &&
              mbedtls_aes_crypt_cbc(&ctx, MBEDTLS_AES_DECRYPT, cipherLen, iv, raw.data() + 16, out.data()) == 0;
    mbedtls_aes_free(&ctx);
    if (!ok) return false;
    uint8_t pad = out.back();
    if (pad == 0 || pad > 16 || pad > out.size()) return false;
    for (size_t i = out.size() - pad; i < out.size(); i++)
        if (out[i] != pad) return false;
    plainOut.assign(out.begin(), out.end() - pad);
    return true;
}

} // namespace protocol

#endif // PROTOCOL_CRYPTO_H
