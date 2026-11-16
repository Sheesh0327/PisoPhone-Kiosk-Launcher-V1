// Host test for GatewayAuth.h and GatewayEvent.h against protocol/fixtures/gateway_v1.json.
#include "../include/GatewayAuth.h"
#include "../include/GatewayEvent.h"
#include "gateway_fixture.h"
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
    // Shared protocol/fixtures/gateway_v1.json vectors
    for (const GwReqVec& v : GW_REQ_VECTORS) {
        CHECK(signRequest(v.key, v.action, v.session, v.nonce) == v.sig);
    }
    for (const GwRespVec& v : GW_RESP_VECTORS) {
        CHECK(signResponse(v.key, v.action, v.session, v.nonce, v.facts) == v.rsig);
    }
    for (const GwEventVec& v : GW_EVENT_VECTORS) {
        CHECK(gatewayevent::body(v.session, v.wid, (uint32_t)v.seq, v.type, v.pulses) == v.body);
        CHECK(gatewayevent::line(v.key, v.session, v.wid, (uint32_t)v.seq, v.type, v.pulses) == v.line);
    }

    // Session id alphabet
    CHECK(validSessionId("aa:bb:cc:dd:ee:ff"));
    CHECK(validSessionId("Token_1.2-3"));
    CHECK(!validSessionId(""));
    CHECK(!validSessionId("has space"));
    CHECK(!validSessionId("semi;colon"));
    CHECK(!validSessionId(std::string(65, 'a')));
    CHECK(validSessionId(std::string(64, 'a')));

    // Single-use nonce verification & replay refusal
    NonceStore store;
    uint8_t r[NONCE_BYTES];
    rnd(r, 1);
    std::string n = store.issue(r, 1000);
    CHECK(n.size() == NONCE_BYTES * 2);
    std::string sig = signRequest(KEY, "arm", "sess1", n);
    CHECK(verifyRequest(KEY, "arm", "sess1", n, sig, store, 2000));
    CHECK(!verifyRequest(KEY, "arm", "sess1", n, sig, store, 2001));

    // Wrong signature burns the nonce
    rnd(r, 2);
    n = store.issue(r, 1000);
    CHECK(!verifyRequest(KEY, "arm", "sess1", n, std::string(64, '0'), store, 1100));
    CHECK(!verifyRequest(KEY, "arm", "sess1", n, signRequest(KEY, "arm", "sess1", n), store, 1200));

    // Action/session/key binding
    rnd(r, 3);
    n = store.issue(r, 5000);
    CHECK(!verifyRequest(KEY, "release", "sess1", n, signRequest(KEY, "arm", "sess1", n), store, 5100));
    rnd(r, 4);
    n = store.issue(r, 5000);
    CHECK(!verifyRequest(KEY, "arm", "sess2", n, signRequest(KEY, "arm", "sess1", n), store, 5100));
    rnd(r, 5);
    n = store.issue(r, 5000);
    CHECK(!verifyRequest(KEY, "arm", "sess1", n, signRequest("another-key-123456", "arm", "sess1", n), store, 5100));

    // Nonce TTL edge
    rnd(r, 6);
    n = store.issue(r, 10000);
    CHECK(verifyRequest(KEY, "arm", "s", n, signRequest(KEY, "arm", "s", n), store, 10000 + NONCE_TTL_MS));
    rnd(r, 7);
    n = store.issue(r, 10000);
    CHECK(!verifyRequest(KEY, "arm", "s", n, signRequest(KEY, "arm", "s", n), store, 10000 + NONCE_TTL_MS + 1));

    // Capacity overwrite
    NonceStore small;
    std::string first;
    for (int i = 0; i < NONCE_SLOTS + 1; i++) {
        rnd(r, (uint8_t)(100 + i));
        std::string x = small.issue(r, (uint32_t)(30000 + i));
        if (i == 0) first = x;
    }
    CHECK(!small.consume(first, 30100));
    rnd(r, (uint8_t)(100 + NONCE_SLOTS));
    std::string last = credcrypto::hexEncode(r, NONCE_BYTES);
    CHECK(small.consume(last, 30100));

    // GatewayEvent validation
    CHECK(gatewayevent::validWindowId("0123456789abcdef0123456789abcdef"));
    CHECK(!gatewayevent::validWindowId(""));
    CHECK(!gatewayevent::validWindowId("ABCDEF"));
    CHECK(!gatewayevent::validWindowId("12:34"));
    CHECK(!gatewayevent::validWindowId(std::string(41, 'a')));
    CHECK(gatewayevent::validPort(8101) && !gatewayevent::validPort(0) && !gatewayevent::validPort(70000));

    printf("gateway_protocol_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
