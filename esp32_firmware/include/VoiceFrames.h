#ifndef VOICE_FRAMES_H
#define VOICE_FRAMES_H

// Pure (no Arduino types) framing of a voice clip for the box -> phone message, compiled on the ESP32 and on a PC so the firmware and
// host_tests/voice_frames_test.cpp run the same code (protocol/fixtures/box_phone_voice_v1.json is the vector file; the phone's tests
// check it too). Feature 056.
//
// The box must not hold or store the clip, so it makes the frames one at a time: take at most 1024 clip bytes, encrypt and sign
// them in the one buffer they arrived in, write the frame to the phone, take the next. The phone plays only after the last frame.
//   frame = flags (bit 0 = last) | seq u16 | plain length u16 | iv 16 | AES-256-CBC (PKCS#7) | mac 32       (big-endian)
//   mac   = HMAC-SHA256(secret, "pisovoice1" | previous mac | the 5 header bytes | iv | ciphertext)
//   the first "previous mac" is the 32 raw bytes of the signed request's hmac, so frames belong to that one request.
//   key   = SHA-256(secret), as for every box message (ProtocolCrypto.h).
// A frame's length depends only on its plain length, so the box can announce the whole body's length (Content-Length) up front.

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>

#include <mbedtls/aes.h>
#include <mbedtls/md.h>

namespace voiceframes {

static const size_t kPlainMax = 1024;
static const size_t kHeadBytes = 5;
static const size_t kIvBytes = 16;
static const size_t kMacBytes = 32;
static const size_t kFrameMax = kHeadBytes + kIvBytes + (kPlainMax + 16) + kMacBytes; // 1024 plain bytes pad to 1040
static const char* const kMacTag = "pisovoice1";

inline size_t frameCountFor(size_t clipBytes) {
    return (clipBytes + kPlainMax - 1) / kPlainMax;
}

inline size_t frameWireBytes(size_t plainLen) {
    return kHeadBytes + kIvBytes + 16 * (plainLen / 16 + 1) + kMacBytes;
}

inline size_t wireBytesFor(size_t clipBytes) {
    size_t full = clipBytes / kPlainMax, rest = clipBytes % kPlainMax;
    return full * frameWireBytes(kPlainMax) + (rest ? frameWireBytes(rest) : 0);
}

class Framer {
public:
    // secret: the phone's box key; envelopeMac: the 32 raw bytes of the signed request's hmac. False if the key cannot be made.
    bool begin(const std::string& secret, const uint8_t envelopeMac[32]) {
        ready_ = false;
        done_ = false;
        seq_ = 0;
        if (secret.empty() || !envelopeMac) return false;
        const mbedtls_md_info_t* info = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
        if (!info || mbedtls_md(info, (const unsigned char*)secret.data(), secret.size(), key_) != 0) return false;
        macKey_ = secret;
        memcpy(prev_, envelopeMac, 32);
        ready_ = true;
        return true;
    }

    // plain: 1..1024 clip bytes (copied to out, so out may be the buffer they arrived in); last: the final piece; iv: 16 random bytes;
    // out: room for kFrameMax bytes. Returns the length of the frame written, or 0 and nothing changed.
    size_t next(const uint8_t* plain, size_t plainLen, bool last, const uint8_t iv[16], uint8_t* out) {
        if (!ready_ || done_ || !plain || !iv || !out) return 0;
        if (plainLen < 1 || plainLen > kPlainMax || seq_ == 0xFFFF) return 0;

        size_t padded = 16 * (plainLen / 16 + 1);
        uint8_t* cipher = out + kHeadBytes + kIvBytes;
        out[0] = last ? 1 : 0;
        out[1] = (uint8_t)(seq_ >> 8);
        out[2] = (uint8_t)seq_;
        out[3] = (uint8_t)(plainLen >> 8);
        out[4] = (uint8_t)plainLen;
        memcpy(out + kHeadBytes, iv, kIvBytes);
        if (plain != cipher) memmove(cipher, plain, plainLen);
        memset(cipher + plainLen, (int)(padded - plainLen), padded - plainLen); // PKCS#7

        uint8_t ivWork[16];
        memcpy(ivWork, iv, 16);
        mbedtls_aes_context aes;
        mbedtls_aes_init(&aes);
        bool ok = mbedtls_aes_setkey_enc(&aes, key_, 256) == 0 &&
                  mbedtls_aes_crypt_cbc(&aes, MBEDTLS_AES_ENCRYPT, padded, ivWork, cipher, cipher) == 0;
        mbedtls_aes_free(&aes);
        if (!ok) return 0;

        uint8_t* mac = cipher + padded;
        mbedtls_md_context_t md;
        mbedtls_md_init(&md);
        ok = mbedtls_md_setup(&md, mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), 1) == 0 &&
             mbedtls_md_hmac_starts(&md, (const unsigned char*)macKey_.data(), macKey_.size()) == 0 &&
             mbedtls_md_hmac_update(&md, (const unsigned char*)kMacTag, strlen(kMacTag)) == 0 &&
             mbedtls_md_hmac_update(&md, prev_, 32) == 0 &&
             mbedtls_md_hmac_update(&md, out, kHeadBytes + kIvBytes) == 0 &&
             mbedtls_md_hmac_update(&md, cipher, padded) == 0 && mbedtls_md_hmac_finish(&md, mac) == 0;
        mbedtls_md_free(&md);
        if (!ok) return 0;

        memcpy(prev_, mac, 32);
        seq_++;
        if (last) done_ = true;
        return kHeadBytes + kIvBytes + padded + kMacBytes;
    }

    uint16_t seq() const { return seq_; }

private:
    uint8_t key_[32];
    std::string macKey_;
    uint8_t prev_[32];
    uint16_t seq_ = 0;
    bool ready_ = false;
    bool done_ = false;
};

} // namespace voiceframes

#endif // VOICE_FRAMES_H
