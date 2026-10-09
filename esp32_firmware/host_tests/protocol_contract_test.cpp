// The box side of the box<->phone contract: ProtocolCrypto.h must reproduce the shared known-answer vectors
// (protocol/fixtures/box_phone_v1.json). The phone's tests check the same file.
#include "../include/AccountProtocol.h"
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

    // Account calls: the box builds the very same signed message the vectors name, and opens the PIN the phone sent.
    for (const AcctVec& v : ACCT_VECTORS) {
        CHECK(acctproto::message(v.op, v.device, v.ts, v.bound) == v.message);
        CHECK(acctproto::signature(v.secret, v.op, v.device, v.ts, v.bound) == v.hmac);
        // changing any bound field, or the operation, breaks the signature
        CHECK(acctproto::signature(v.secret, v.op, v.device, v.ts, std::string(v.bound) + "0") != v.hmac);
        CHECK(acctproto::signature(v.secret, "info", v.device, v.ts, v.bound) != v.hmac || std::string(v.op) == "info");
    }
    {
        std::string pin;
        CHECK(acctproto::openPin(ACCT_PIN_CIPHER, ACCT_PIN_SECRET, pin) && pin == ACCT_PIN);
        CHECK(!acctproto::openPin(ACCT_PIN_CIPHER, std::string(ACCT_PIN_SECRET) + "x", pin) || pin != ACCT_PIN);
        // an ordinary encrypted string that is not a PIN envelope is refused
        uint8_t iv[16] = {0};
        CHECK(!acctproto::openPin(protocol::encryptHex("PIN:12", ACCT_PIN_SECRET, iv), ACCT_PIN_SECRET, pin));
        CHECK(!acctproto::openPin(protocol::encryptHex("PIN:12ab", ACCT_PIN_SECRET, iv), ACCT_PIN_SECRET, pin));
        CHECK(!acctproto::openPin(protocol::encryptHex("hello", ACCT_PIN_SECRET, iv), ACCT_PIN_SECRET, pin));
        CHECK(acctproto::openPin(protocol::encryptHex("PIN:123456", ACCT_PIN_SECRET, iv), ACCT_PIN_SECRET, pin) &&
              pin == "123456");
        std::string u, r;
        CHECK(acctproto::splitBound("alice:540", u, r) && u == "alice" && r == "540");
        CHECK(!acctproto::splitBound("alice", u, r));
        uint32_t n = 0;
        CHECK(acctproto::parseSeconds("540", n) && n == 540);
        CHECK(acctproto::parseSeconds("4294967295", n) && n == UINT32_MAX);
        CHECK(!acctproto::parseSeconds("4294967296", n));
        CHECK(!acctproto::parseSeconds("", n) && !acctproto::parseSeconds("-1", n) &&
              !acctproto::parseSeconds("5x", n));
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
