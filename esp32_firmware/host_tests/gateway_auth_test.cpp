// Host test for GatewayAuth.h: signature format, one-time nonces, expiry, tampering.
#include "../include/GatewayAuth.h"
#include <cstdio>

using namespace gatewayauth;
static int failures = 0, checks = 0;
#define CHECK(c)                                                                                                       \
    do {                                                                                                               \
        checks++;                                                                                                      \
        if (!(c)) {                                                                                                    \
            failures++;                                                                                                \
            printf("FAIL line %d: %s\n", __LINE__, #c);                                                                \
        }                                                                                                              \
    } while (0)

static const std::string KEY = "0123456789abcdef-key";

static void rnd(uint8_t* out, uint8_t seed) {
    for (size_t i = 0; i < NONCE_BYTES; i++)
        out[i] = (uint8_t)(seed + i);
}

int main() {
    // Signature format pinned to a value computed independently with Python hmac/sha256.
    CHECK(signRequest(KEY, "arm", "aa-bb-cc", "00112233445566778899aabbccddeeff") ==
          "b73b6b4b4ed7ae1d4663508cb338eea0b3d95f3d0efffcdbb4ec0c9cfea75834");

    // Session id alphabet.
    CHECK(validSessionId("aa:bb:cc:dd:ee:ff"));
    CHECK(validSessionId("Token_1.2-3"));
    CHECK(!validSessionId(""));
    CHECK(!validSessionId("has space"));
    CHECK(!validSessionId("semi;colon"));
    CHECK(!validSessionId(std::string(65, 'a')));
    CHECK(validSessionId(std::string(64, 'a')));

    // Happy path: nonce is single-use.
    NonceStore store;
    uint8_t r[NONCE_BYTES];
    rnd(r, 1);
    std::string n = store.issue(r, 1000);
    CHECK(n.size() == NONCE_BYTES * 2);
    std::string sig = signRequest(KEY, "arm", "sess1", n);
    CHECK(verifyRequest(KEY, "arm", "sess1", n, sig, store, 2000));
    CHECK(!verifyRequest(KEY, "arm", "sess1", n, sig, store, 2001)); // replay refused

    // Wrong signature burns the nonce so it cannot be guessed against repeatedly.
    rnd(r, 2);
    n = store.issue(r, 1000);
    CHECK(!verifyRequest(KEY, "arm", "sess1", n, std::string(64, '0'), store, 1100));
    CHECK(!verifyRequest(KEY, "arm", "sess1", n, signRequest(KEY, "arm", "sess1", n), store, 1200));

    // A signature is bound to action, session and key.
    rnd(r, 3);
    n = store.issue(r, 5000);
    CHECK(!verifyRequest(KEY, "release", "sess1", n, signRequest(KEY, "arm", "sess1", n), store, 5100));
    rnd(r, 4);
    n = store.issue(r, 5000);
    CHECK(!verifyRequest(KEY, "arm", "sess2", n, signRequest(KEY, "arm", "sess1", n), store, 5100));
    rnd(r, 5);
    n = store.issue(r, 5000);
    CHECK(!verifyRequest(KEY, "arm", "sess1", n, signRequest("another-key-123456", "arm", "sess1", n), store, 5100));

    // Expiry: valid at the TTL edge, refused after.
    rnd(r, 6);
    n = store.issue(r, 10000);
    CHECK(verifyRequest(KEY, "arm", "s", n, signRequest(KEY, "arm", "s", n), store, 10000 + NONCE_TTL_MS));
    rnd(r, 7);
    n = store.issue(r, 10000);
    CHECK(!verifyRequest(KEY, "arm", "s", n, signRequest(KEY, "arm", "s", n), store, 10000 + NONCE_TTL_MS + 1));

    // Unknown / malformed nonce and unusable key or session.
    CHECK(!verifyRequest(KEY, "arm", "s", "deadbeef", "x", store, 0));
    rnd(r, 8);
    n = store.issue(r, 20000);
    CHECK(!verifyRequest("short", "arm", "s", n, signRequest("short", "arm", "s", n), store, 20001));
    rnd(r, 9);
    n = store.issue(r, 20000);
    CHECK(!verifyRequest(KEY, "arm", "bad id!", n, signRequest(KEY, "arm", "bad id!", n), store, 20001));

    // Capacity: issuing more than NONCE_SLOTS overwrites the oldest, newest stay valid.
    NonceStore small;
    std::string first;
    for (int i = 0; i < NONCE_SLOTS + 1; i++) {
        rnd(r, (uint8_t)(100 + i));
        std::string x = small.issue(r, 30000 + i);
        if (i == 0) first = x;
    }
    CHECK(!small.consume(first, 30100));
    rnd(r, (uint8_t)(100 + NONCE_SLOTS));
    std::string last = credcrypto::hexEncode(r, NONCE_BYTES);
    CHECK(small.consume(last, 30100));

    // Signed answers (the same vectors are checked by the router program, tools/pisoportal/src/boxlink.rs).
    const std::string TKEY = "test-gateway-key-123456";
    const std::string TNONCE = "0123456789abcdef0123456789abcdef";
    CHECK(signResponse(TKEY, "status", "abc123", TNONCE, "armed:0:3:1003:true:0:6") ==
          "d157e75cb7dede9c1d465137ab7bd9fad325d1df9001bab95ad4da0b0a484441");
    CHECK(signResponse(TKEY, "ack", "abc123", TNONCE, "3") ==
          "19a2eb098dccbd7a39adcb42e3d5cc9361d19e23769345d443dc16cde21fc692");
    // an answer is bound to its request: another nonce, session, action, key or number gives another signature
    std::string base = signResponse(TKEY, "status", "abc123", TNONCE, "armed:0:3:1003:true:0:6");
    CHECK(signResponse(TKEY, "status", "abc123", TNONCE, "armed:0:4:1003:true:0:6") != base);
    CHECK(signResponse(TKEY, "status", "abc123", "f123456789abcdef0123456789abcdef", "armed:0:3:1003:true:0:6") !=
          base);
    CHECK(signResponse(TKEY, "status", "abc124", TNONCE, "armed:0:3:1003:true:0:6") != base);
    CHECK(signResponse(TKEY, "release", "abc123", TNONCE, "armed:0:3:1003:true:0:6") != base);
    CHECK(signResponse("another-key-0123456789", "status", "abc123", TNONCE, "armed:0:3:1003:true:0:6") != base);
    // and it is not the signature of a request
    CHECK(signResponse(TKEY, "status", "abc123", TNONCE, "") != signRequest(TKEY, "status", "abc123", TNONCE));

    printf("gateway_auth_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
