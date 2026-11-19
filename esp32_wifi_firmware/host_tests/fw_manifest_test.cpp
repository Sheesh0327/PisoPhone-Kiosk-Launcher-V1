// Host test for FwManifest.h using manifests signed by scripts/sign_firmware.py (--product wifi).
#include "../include/FwManifest.h"
#include "fw_fixture.h"
#include <cstdio>
#include <cstring>

using namespace fwmanifest;
static int failures = 0, checks = 0;
#define CHECK(c)                                                                                                       \
    do {                                                                                                               \
        checks++;                                                                                                      \
        if (!(c)) {                                                                                                    \
            failures++;                                                                                                \
            printf("FAIL line %d: %s\n", __LINE__, #c);                                                                \
        }                                                                                                              \
    } while (0)

static Manifest good() {
    Manifest m;
    m.chip = "esp32c3";
    m.version = FW_VERSION;
    m.sha256Hex = FW_SHA;
    m.size = FW_SIZE;
    return m;
}
static Check run(const Manifest& m, const char* sig, const char* running = "3.12.6", bool down = false,
                 const char* chip = "esp32c3") {
    return checkManifest(m, sig, FW_PUB, sizeof(FW_PUB), chip, running, down);
}

int main() {
    uint32_t v[3];
    CHECK(parseVersion("3.12.7", v) && v[0] == 3 && v[1] == 12 && v[2] == 7);
    CHECK(!parseVersion("3.12", v) && !parseVersion("3.12.7.1", v) && !parseVersion("a.b.c", v));
    CHECK(compareVersions("3.12.7", "3.12.6") > 0);
    CHECK(compareVersions("3.12.7", "3.12.7") == 0);
    CHECK(compareVersions("3.10.0", "3.9.0") > 0);

    CHECK(run(good(), FW_SIG) == Check::Ok);
    CHECK(run(good(), FW_SIG_OTHER_KEY) == Check::BadSignature);
    CHECK(run(good(), FW_SIG_WRONG_PRODUCT) == Check::BadSignature);
    CHECK(checkManifest(good(), FW_SIG, nullptr, 0, "esp32c3", "3.12.6", false) == Check::NoKey);
    CHECK(run(good(), FW_SIG, "3.12.6", false, "esp32") == Check::WrongChip);

    Manifest m = good();
    m.size += 1;
    CHECK(run(m, FW_SIG) == Check::BadSignature);
    m = good();
    m.version = "9.9.9";
    CHECK(run(m, FW_SIG) == Check::BadSignature);
    m = good();
    m.sha256Hex[0] = m.sha256Hex[0] == 'a' ? 'b' : 'a';
    CHECK(run(m, FW_SIG) == Check::BadSignature);
    m = good();
    m.sha256Hex = "abcd";
    CHECK(run(m, FW_SIG) == Check::BadFormat);

    // Same or older version refused unless allowDowngrade=true
    CHECK(run(good(), FW_SIG, "3.12.7") == Check::NotNewer);
    CHECK(run(good(), FW_SIG, "3.13.0") == Check::NotNewer);
    CHECK(run(good(), FW_SIG, "3.13.0", true) == Check::Ok);

    // Streaming verification
    ImageVerifier ver;
    CHECK(ver.begin(good()));
    CHECK(ver.update(FW_IMAGE, 100));
    CHECK(ver.update(FW_IMAGE + 100, sizeof(FW_IMAGE) - 100));
    CHECK(ver.finish());

    uint8_t bad[sizeof(FW_IMAGE)];
    memcpy(bad, FW_IMAGE, sizeof(bad));
    bad[2000] ^= 1;
    CHECK(ver.begin(good()) && ver.update(bad, sizeof(bad)));
    CHECK(!ver.finish());

    CHECK(ver.begin(good()) && ver.update(FW_IMAGE, sizeof(FW_IMAGE) - 1));
    CHECK(!ver.finish());
    CHECK(ver.begin(good()) && ver.update(FW_IMAGE, sizeof(FW_IMAGE)));
    CHECK(!ver.update(FW_IMAGE, 1));
    ver.end();
    CHECK(!ver.finish());

    printf("fw_manifest_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
