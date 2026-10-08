#ifndef FW_MANIFEST_H
#define FW_MANIFEST_H

// Pure (no Arduino types) checks for signed firmware updates, compiled and tested on a PC
// (host_tests/fw_manifest_test.cpp).
//
// The owner signs a small manifest for each firmware image with the same offline owner key
// (scripts/sign_firmware.py). Before an upload the box checks the manifest signature; while
// the image streams in, it hashes the bytes; before the new image is committed, the hash and size must
// match the signed manifest. So only an image the owner signed can ever be flashed.
//
// Signed text: pisophone-fw-v1|<chip>|<version>|<sha256 hex>|<size in bytes>

#include "CredCrypto.h"

#include <mbedtls/md.h>

namespace fwmanifest {

struct Manifest {
    std::string chip;      // "esp32c3" or "esp32"
    std::string version;   // "3.1.0"
    std::string sha256Hex; // of the whole image file
    uint32_t size = 0;
};

enum class Check { Ok, NoKey, BadFormat, WrongChip, BadSignature, NotNewer };

inline std::string canonicalMessage(const Manifest& m) {
    return "pisophone-fw-v1|" + m.chip + "|" + m.version + "|" + m.sha256Hex + "|" + std::to_string(m.size);
}

// "3.1.0" -> {3,1,0}. Exactly three numeric parts of at most 5 digits each.
inline bool parseVersion(const std::string& v, uint32_t out[3]) {
    size_t pos = 0;
    for (int part = 0; part < 3; part++) {
        size_t start = pos;
        uint32_t n = 0;
        while (pos < v.size() && v[pos] >= '0' && v[pos] <= '9') {
            n = n * 10 + (uint32_t)(v[pos] - '0');
            pos++;
            if (pos - start > 5) return false;
        }
        if (pos == start) return false;
        out[part] = n;
        if (part < 2) {
            if (pos >= v.size() || v[pos] != '.') return false;
            pos++;
        }
    }
    return pos == v.size();
}

// <0 when a is older than b, 0 when equal, >0 when newer. Unparseable versions compare as older than everything.
inline int compareVersions(const std::string& a, const std::string& b) {
    uint32_t x[3], y[3];
    bool okA = parseVersion(a, x), okB = parseVersion(b, y);
    if (!okA || !okB) return (okA ? 1 : 0) - (okB ? 1 : 0);
    for (int i = 0; i < 3; i++) {
        if (x[i] != y[i]) return x[i] < y[i] ? -1 : 1;
    }
    return 0;
}

// Decides whether a manifest may start an update on this chip, given the version running now.
inline Check checkManifest(const Manifest& m, const std::string& sigBase64, const uint8_t* pubKeyDer, size_t pubKeyLen,
                           const std::string& thisChip, const std::string& runningVersion, bool allowDowngrade) {
    if (!pubKeyDer || pubKeyLen == 0) return Check::NoKey;
    std::vector<uint8_t> digest;
    uint32_t ver[3];
    if (!credcrypto::hexDecode(m.sha256Hex, digest) || digest.size() != 32 || !parseVersion(m.version, ver) ||
        m.size < 1024 || m.size > 4 * 1024 * 1024) {
        return Check::BadFormat;
    }
    if (m.chip != thisChip) return Check::WrongChip;
    if (!credcrypto::verifySignature(pubKeyDer, pubKeyLen, canonicalMessage(m), sigBase64)) {
        return Check::BadSignature;
    }
    if (!allowDowngrade && compareVersions(m.version, runningVersion) <= 0) return Check::NotNewer;
    return Check::Ok;
}

// Hashes the image as it streams in and compares with an approved manifest at the end.
class ImageVerifier {
public:
    ImageVerifier() { mbedtls_md_init(&ctx_); }
    ~ImageVerifier() { mbedtls_md_free(&ctx_); }
    ImageVerifier(const ImageVerifier&) = delete;
    ImageVerifier& operator=(const ImageVerifier&) = delete;

    bool begin(const Manifest& approved) {
        end();
        std::vector<uint8_t> want;
        if (!credcrypto::hexDecode(approved.sha256Hex, want) || want.size() != 32) return false;
        expected_ = want;
        expectedSize_ = approved.size;
        received_ = 0;
        const mbedtls_md_info_t* info = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
        if (!info || mbedtls_md_setup(&ctx_, info, 0) != 0 || mbedtls_md_starts(&ctx_) != 0) return false;
        active_ = true;
        return true;
    }

    // False as soon as more bytes arrive than the manifest allows.
    bool update(const uint8_t* data, size_t len) {
        if (!active_) return false;
        if ((uint64_t)received_ + len > expectedSize_) return false;
        if (mbedtls_md_update(&ctx_, data, len) != 0) return false;
        received_ += (uint32_t)len;
        return true;
    }

    // True only when exactly the approved number of bytes arrived and they hash to the approved digest.
    bool finish() {
        if (!active_) return false;
        uint8_t got[32];
        bool ok = received_ == expectedSize_ && mbedtls_md_finish(&ctx_, got) == 0 &&
                  credcrypto::constantTimeEquals(got, expected_.data(), 32);
        end();
        return ok;
    }

    void end() {
        if (active_) mbedtls_md_free(&ctx_);
        mbedtls_md_init(&ctx_);
        active_ = false;
    }

    bool active() const { return active_; }
    uint32_t received() const { return received_; }

private:
    mbedtls_md_context_t ctx_;
    std::vector<uint8_t> expected_;
    uint32_t expectedSize_ = 0;
    uint32_t received_ = 0;
    bool active_ = false;
};

} // namespace fwmanifest

#endif // FW_MANIFEST_H
