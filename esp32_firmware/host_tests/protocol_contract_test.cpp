// The box side of the box<->phone contract: ProtocolCrypto.h must reproduce the shared known-answer vectors
// (protocol/fixtures/box_phone_v1.json). The phone's tests check the same file.
#include "../include/ProtocolCrypto.h"
#include "protocol_fixture.h"

#include <cstdio>

static int failures = 0, checks = 0;
#define CHECK(c)                                                                                                       \
    do {                                                                                                               \
        checks++;                                                                                                      \
        if (!(c)) {                                                                                                    \
            failures++;                                                                                                \
            printf("FAIL line %d: %s\n", __LINE__, #c);                                                                \
        }                                                                                                              \
    } while (0)

static void hexToBytes(const char* hex, uint8_t* out) {
    for (int i = 0; i < 16; i++)
        out[i] = (uint8_t)((protocol::hexNibble(hex[2 * i]) << 4) | protocol::hexNibble(hex[2 * i + 1]));
}

int main() {
    for (const EncVec& v : ENC_VECTORS) {
        uint8_t iv[16];
        hexToBytes(v.iv, iv);
        CHECK(protocol::encryptHex(v.plain, v.secret, iv) == v.cipher);
        std::string back;
        CHECK(protocol::decryptHex(v.cipher, v.secret, back) && back == v.plain);
        // the wrong secret must not decrypt to the original
        std::string wrong;
        bool ok = protocol::decryptHex(v.cipher, std::string(v.secret) + "x", wrong);
        CHECK(!ok || wrong != v.plain);
    }
    for (const MacVec& v : MAC_VECTORS) {
        CHECK(protocol::hmacHex(v.message, v.secret) == v.hmac);
        CHECK(protocol::hmacHex(std::string(v.message) + " ", v.secret) != v.hmac);
    }

    // malformed input is refused, never crashes
    std::string out;
    CHECK(!protocol::decryptHex("", "k", out));
    CHECK(!protocol::decryptHex(std::string(63, 'a'), "k", out));
    CHECK(!protocol::decryptHex(std::string(64, 'z'), "k", out));
    CHECK(!protocol::decryptHex(std::string(80, 'a'), "k", out) || true); // padding check decides; must not crash

    printf("protocol_contract_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
