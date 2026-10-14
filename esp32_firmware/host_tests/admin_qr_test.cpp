// Host test for AdminQrAuth.h (the owner's QR login) and the crypto helpers in CredCrypto.h, with a throwaway key pair and
// signatures made by gen_admin_qr_fixture.py (the same script that makes the owner's QR code).
#include "../include/AdminQrAuth.h"
#include "admin_qr_fixture.h"
#include <cstdio>
#include <string>

using namespace adminqr;
static int failures = 0, checks = 0;
#define CHECK(c)                                                                                                       \
    do {                                                                                                               \
        checks++;                                                                                                      \
        if (!(c)) {                                                                                                    \
            failures++;                                                                                                \
            printf("FAIL line %d: %s\n", __LINE__, #c);                                                                \
        }                                                                                                              \
    } while (0)

// The bytes the fixture's nonce stands for.
static void nonceBytes(uint8_t out[RANDOM_BYTES]) {
    std::vector<uint8_t> v;
    credcrypto::hexDecode(FX_NONCE, v);
    memcpy(out, v.data(), RANDOM_BYTES);
}
static void token(uint8_t out[RANDOM_BYTES], uint8_t fill) {
    memset(out, fill, RANDOM_BYTES);
}

int main() {
    // hex, base64, constant-time compare, HMAC (RFC 4231 test case 2)
    std::vector<uint8_t> v;
    CHECK(credcrypto::hexDecode("00ff10", v) && v.size() == 3 && v[1] == 0xff);
    CHECK(!credcrypto::hexDecode("0", v) && !credcrypto::hexDecode("zz", v) && !credcrypto::hexDecode("", v));
    CHECK(credcrypto::hexEncode(v.data(), 0) == "");
    CHECK(credcrypto::base64Decode("aGk=", v) && v.size() == 2 && v[0] == 'h');
    CHECK(!credcrypto::base64Decode("!!!!", v));
    uint8_t a[3] = {1, 2, 3}, b[3] = {1, 2, 3}, c[3] = {1, 2, 4};
    CHECK(credcrypto::constantTimeEquals(a, b, 3) && !credcrypto::constantTimeEquals(a, c, 3));
    uint8_t mac[32];
    CHECK(credcrypto::hmacSha256((const uint8_t*)"Jefe", 4, (const uint8_t*)"what do ya want for nothing?", 28, mac));
    CHECK(credcrypto::hexEncode(mac, 32) == "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");

    // The signature check itself: only the right key, box and challenge pass.
    std::string msg = signedMessage(FX_NONCE, FX_BOX);
    CHECK(msg == std::string("pisophone-admin-v1|") + FX_NONCE + "|" + FX_BOX);
    CHECK(credcrypto::verifySignature(FX_PUB, sizeof(FX_PUB), msg, FX_SIG_GOOD));
    CHECK(!credcrypto::verifySignature(FX_PUB, sizeof(FX_PUB), msg, FX_SIG_OTHER_KEY));
    CHECK(!credcrypto::verifySignature(FX_PUB, sizeof(FX_PUB), msg, FX_SIG_OTHER_BOX));
    CHECK(!credcrypto::verifySignature(FX_PUB, sizeof(FX_PUB), msg, FX_SIG_OTHER_NONCE));
    CHECK(!credcrypto::verifySignature(FX_PUB, sizeof(FX_PUB), msg, ""));
    CHECK(!credcrypto::verifySignature(FX_PUB, sizeof(FX_PUB), msg, "AAAA"));
    CHECK(!credcrypto::verifySignature(nullptr, 0, msg, FX_SIG_GOOD));

    // A login: challenge, signature, session.
    uint8_t r[RANDOM_BYTES], t[RANDOM_BYTES];
    nonceBytes(r);
    token(t, 0xab);
    Auth auth;
    std::string nonce = auth.issueChallenge(r, 1000);
    CHECK(nonce == FX_NONCE);
    std::string tok;
    CHECK(auth.login(nonce, FX_SIG_GOOD, FX_BOX, FX_PUB, sizeof(FX_PUB), t, 2000, tok) == Result::Ok);
    CHECK(tok == "abababababababababababababababab");
    CHECK(auth.sessionValid(tok, 3000) && auth.active());
    CHECK(!auth.sessionValid("abababababababababababababababaa", 3000) && !auth.sessionValid("", 3000) &&
          !auth.sessionValid("ab", 3000));

    // One try per challenge: the same challenge cannot be answered twice, not even with the right signature.
    CHECK(auth.login(nonce, FX_SIG_GOOD, FX_BOX, FX_PUB, sizeof(FX_PUB), t, 2500, tok) == Result::UnknownNonce &&
          tok.empty());
    // A wrong signature uses the challenge up, and starts no session.
    Auth other;
    other.issueChallenge(r, 1000);
    CHECK(other.login(FX_NONCE, FX_SIG_OTHER_KEY, FX_BOX, FX_PUB, sizeof(FX_PUB), t, 1500, tok) ==
              Result::BadSignature &&
          !other.active());
    CHECK(other.login(FX_NONCE, FX_SIG_GOOD, FX_BOX, FX_PUB, sizeof(FX_PUB), t, 1600, tok) == Result::UnknownNonce);
    // A signature for another box, or for a challenge this box never gave, is no good.
    Auth thirdParty;
    thirdParty.issueChallenge(r, 1000);
    CHECK(thirdParty.login(FX_NONCE, FX_SIG_OTHER_BOX, FX_BOX, FX_PUB, sizeof(FX_PUB), t, 1100, tok) ==
          Result::BadSignature);
    Auth stranger;
    CHECK(stranger.login(FX_NONCE, FX_SIG_GOOD, FX_BOX, FX_PUB, sizeof(FX_PUB), t, 1100, tok) == Result::UnknownNonce);
    // The challenge expires after a minute.
    Auth slow;
    slow.issueChallenge(r, 1000);
    CHECK(slow.login(FX_NONCE, FX_SIG_GOOD, FX_BOX, FX_PUB, sizeof(FX_PUB), t, 1000 + NONCE_TTL_MS + 1, tok) ==
          Result::UnknownNonce);
    Auth justInTime;
    justInTime.issueChallenge(r, 1000);
    CHECK(justInTime.login(FX_NONCE, FX_SIG_GOOD, FX_BOX, FX_PUB, sizeof(FX_PUB), t, 1000 + NONCE_TTL_MS, tok) ==
          Result::Ok);
    // A box built without a key accepts nothing.
    Auth keyless;
    keyless.issueChallenge(r, 1000);
    CHECK(keyless.login(FX_NONCE, FX_SIG_GOOD, FX_BOX, nullptr, 0, t, 1100, tok) == Result::NoKey && !keyless.active());

    // Asking again soon returns the same challenge (so the owner's challenge cannot be starved); later, a fresh one.
    Auth many;
    uint8_t r2[RANDOM_BYTES];
    memset(r2, 0x11, sizeof(r2));
    std::string first = many.issueChallenge(r, 5000);
    CHECK(many.issueChallenge(r2, 5000 + NONCE_REUSE_MS - 1) == first);
    std::string second = many.issueChallenge(r2, 5000 + NONCE_REUSE_MS + 1);
    CHECK(second != first && second == "11111111111111111111111111111111");
    // ... and the older challenge still works for its minute (four are kept).
    CHECK(many.login(first, FX_SIG_GOOD, FX_BOX, FX_PUB, sizeof(FX_PUB), t, 5000 + NONCE_REUSE_MS + 2, tok) ==
          Result::Ok);
    Auth flood;
    std::string oldest = flood.issueChallenge(r, 0);
    for (int i = 1; i <= NONCE_SLOTS; i++) {
        uint8_t rr[RANDOM_BYTES];
        memset(rr, i, sizeof(rr));
        flood.issueChallenge(rr, (unsigned long)i * (NONCE_REUSE_MS + 1));
    }
    CHECK(flood.login(oldest, FX_SIG_GOOD, FX_BOX, FX_PUB, sizeof(FX_PUB), t, NONCE_SLOTS * (NONCE_REUSE_MS + 1) + 1,
                      tok) == Result::UnknownNonce);

    // The session: sliding idle time, a hard limit, an end, and a new login replaces it.
    Auth s;
    s.issueChallenge(r, 0);
    CHECK(s.login(FX_NONCE, FX_SIG_GOOD, FX_BOX, FX_PUB, sizeof(FX_PUB), t, 10, tok) == Result::Ok);
    unsigned long now = 10;
    for (int i = 0; i < 7;
         i++) { // used every 10 minutes for 70 minutes: the idle timer slides, the hour limit does not
        now += 10UL * 60UL * 1000UL;
        bool ok = s.sessionValid(tok, now);
        CHECK(ok == (now - 10 <= SESSION_MAX_MS));
        if (!ok) break;
    }
    Auth idle;
    idle.issueChallenge(r, 0);
    CHECK(idle.login(FX_NONCE, FX_SIG_GOOD, FX_BOX, FX_PUB, sizeof(FX_PUB), t, 10, tok) == Result::Ok);
    CHECK(!idle.sessionValid(tok, 10 + SESSION_IDLE_MS + 1) && !idle.active());
    Auth ended;
    ended.issueChallenge(r, 0);
    CHECK(ended.login(FX_NONCE, FX_SIG_GOOD, FX_BOX, FX_PUB, sizeof(FX_PUB), t, 10, tok) == Result::Ok);
    ended.end();
    CHECK(!ended.sessionValid(tok, 20));
    Auth replaced;
    replaced.issueChallenge(r, 0);
    CHECK(replaced.login(FX_NONCE, FX_SIG_GOOD, FX_BOX, FX_PUB, sizeof(FX_PUB), t, 10, tok) == Result::Ok);
    std::string firstTok = tok;
    uint8_t t2[RANDOM_BYTES];
    token(t2, 0xcd);
    replaced.issueChallenge(r,
                            10 + NONCE_REUSE_MS + 1); // (the same nonce text again: the fixture signs one fixed text)
    CHECK(replaced.login(FX_NONCE, FX_SIG_GOOD, FX_BOX, FX_PUB, sizeof(FX_PUB), t2, 5020, tok) == Result::Ok);
    CHECK(tok == "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd" && replaced.sessionValid(tok, 5030) &&
          !replaced.sessionValid(firstTok, 5030));

    printf("%d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
