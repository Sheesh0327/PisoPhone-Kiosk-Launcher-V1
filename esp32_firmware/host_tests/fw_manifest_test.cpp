// Host test for FwManifest.h using a manifest signed by the real scripts/sign_firmware.py.
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
static Check run(const Manifest& m, const char* sig, const char* running = "3.1.0", bool down = false,
                 const char* chip = "esp32c3") {
    return checkManifest(m, sig, FW_PUB, sizeof(FW_PUB), chip, running, down);
}

int main() {
    // versions
    uint32_t v[3];
    CHECK(parseVersion("3.1.0", v) && v[0] == 3 && v[1] == 1 && v[2] == 0);
    CHECK(!parseVersion("3.1", v) && !parseVersion("3.1.0.1", v) && !parseVersion("a.b.c", v) && !parseVersion("", v));
    CHECK(!parseVersion("3..0", v) && !parseVersion("3.1.", v) && !parseVersion("3.1.0-beta", v));
    CHECK(compareVersions("3.1.0", "3.1.0") == 0);
    CHECK(compareVersions("3.2.0", "3.1.9") > 0);
    CHECK(compareVersions("3.10.0", "3.9.0") > 0); // numeric, not text, comparison
    CHECK(compareVersions("2.9.9", "3.0.0") < 0);
    CHECK(compareVersions("junk", "3.0.0") < 0);

    // manifest checks
    CHECK(run(good(), FW_SIG) == Check::Ok);
    CHECK(run(good(), FW_SIG_OTHER_KEY) == Check::BadSignature);
    CHECK(checkManifest(good(), FW_SIG, nullptr, 0, "esp32c3", "3.1.0", false) == Check::NoKey);
    CHECK(run(good(), FW_SIG, "3.1.0", false, "esp32") == Check::WrongChip);

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
    m = good();
    m.version = "x";
    CHECK(run(m, FW_SIG) == Check::BadFormat);

    // downgrades and reinstalling the running version need the explicit flag
    CHECK(run(good(), FW_SIG, "3.2.0") == Check::NotNewer);
    CHECK(run(good(), FW_SIG, "3.3.0") == Check::NotNewer);
    CHECK(run(good(), FW_SIG, "3.3.0", true) == Check::Ok);

    // streaming verification
    ImageVerifier ver;
    CHECK(ver.begin(good()));
    CHECK(ver.update(FW_IMAGE, 100));
    CHECK(ver.update(FW_IMAGE + 100, sizeof(FW_IMAGE) - 100));
    CHECK(ver.finish());

    // one flipped byte
    uint8_t bad[sizeof(FW_IMAGE)];
    memcpy(bad, FW_IMAGE, sizeof(bad));
    bad[2000] ^= 1;
    CHECK(ver.begin(good()) && ver.update(bad, sizeof(bad)));
    CHECK(!ver.finish());

    // too short, too long, no begin
    CHECK(ver.begin(good()) && ver.update(FW_IMAGE, sizeof(FW_IMAGE) - 1));
    CHECK(!ver.finish());
    CHECK(ver.begin(good()) && ver.update(FW_IMAGE, sizeof(FW_IMAGE)));
    CHECK(!ver.update(FW_IMAGE, 1)); // a trailing byte beyond the signed size is refused at once
    ver.end();
    CHECK(!ver.finish());
    CHECK(!ver.begin(Manifest()));

    printf("fw_manifest_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
