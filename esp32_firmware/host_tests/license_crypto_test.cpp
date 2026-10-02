// Host test for LicenseCrypto.h using tokens made by the real scripts/generate_license.py.
#include "../include/LicenseCrypto.h"
#include "license_fixture.h"
#include <cstdio>

using namespace licensecrypto;
static int failures = 0, checks = 0;
#define CHECK(c)                                                                                                       \
    do {                                                                                                               \
        checks++;                                                                                                      \
        if (!(c)) {                                                                                                    \
            failures++;                                                                                                \
            printf("FAIL line %d: %s\n", __LINE__, #c);                                                                \
        }                                                                                                              \
    } while (0)

static LicenseCheck run(const std::string& tok, const std::string& mac, uint32_t& slots, uint32_t maxSlots = 6) {
    return checkToken(tok, mac, LX_PUB, sizeof(LX_PUB), maxSlots, slots);
}

int main() {
    uint32_t slots = 0;
    CHECK(looksSigned(LX_GOOD3));
    CHECK(!looksSigned("8F3A1B2C"));

    CHECK(run(LX_GOOD3, LX_MAC, slots) == LicenseCheck::Ok && slots == 3);
    CHECK(run(LX_GOOD6, LX_MAC, slots) == LicenseCheck::Ok && slots == 6);
    // the box MAC may be written with colons, in lower case, or with the token wrapped in spaces
    CHECK(run(LX_GOOD3, "aa:bb:cc:dd:ee:01", slots) == LicenseCheck::Ok);
    CHECK(run(std::string(" ") + LX_GOOD3 + "\n", LX_MAC, slots) == LicenseCheck::Ok);

    // another box's license, a license from another key, no key built in
    CHECK(run(LX_OTHER_BOX, LX_MAC, slots) == LicenseCheck::WrongBox);
    CHECK(run(LX_FORGED, LX_MAC, slots) == LicenseCheck::BadSignature);
    CHECK(checkToken(LX_GOOD3, LX_MAC, nullptr, 0, 6, slots) == LicenseCheck::NoKey);

    // editing the slot count invalidates the signature
    std::string t = LX_GOOD3;
    size_t p = t.find(".3.");
    t.replace(p, 3, ".5.");
    CHECK(run(t, LX_MAC, slots) == LicenseCheck::BadSignature);

    // malformed input
    CHECK(run("", LX_MAC, slots) == LicenseCheck::BadFormat);
    CHECK(run("PISOLIC1.AABBCCDDEE01.3", LX_MAC, slots) == LicenseCheck::BadFormat);
    CHECK(run("PISOLIC1.AABBCCDDEE01.0.abcd", LX_MAC, slots) == LicenseCheck::BadFormat);
    CHECK(run("PISOLIC1.AABBCCDDEE01.x.abcd", LX_MAC, slots) == LicenseCheck::BadFormat);
    CHECK(run("PISOLIC1.AABBCCDDEE0Z.3.abcd", LX_MAC, slots) == LicenseCheck::BadFormat);
    CHECK(run(LX_GOOD6, LX_MAC, slots, 4) == LicenseCheck::BadFormat); // more slots than the box supports
    CHECK(run("PISOLIC1.AABBCCDDEE01.3.!!!!", LX_MAC, slots) == LicenseCheck::BadSignature);

    printf("license_crypto_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
