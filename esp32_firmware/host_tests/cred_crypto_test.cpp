// Host test for CredCrypto.h: PBKDF2 against RFC 7914 vectors, and the signed-credentials checks
// using a throwaway key pair made by gen_cred_fixture.py.
#include "../include/CredCrypto.h"
#include "cred_fixture.h"
#include <cstdio>
#include <string>

using namespace credcrypto;
static int failures = 0, checks = 0;
#define CHECK(c)                                                                                                       \
    do {                                                                                                               \
        checks++;                                                                                                      \
        if (!(c)) {                                                                                                    \
            failures++;                                                                                                \
            printf("FAIL line %d: %s\n", __LINE__, #c);                                                                \
        }                                                                                                              \
    } while (0)

static SignedCredentials good() {
    SignedCredentials c;
    c.version = FX_GOOD_VER;
    c.iterations = FX_GOOD_ITER;
    c.saltHex = FX_GOOD_SALT;
    c.hashHex = FX_GOOD_HASH;
    return c;
}

int main() {
    // PBKDF2-HMAC-SHA256 vectors (RFC 7914 / widely published), 32-byte first block.
    uint8_t out[32];
    std::vector<uint8_t> salt = {'s', 'a', 'l', 't'};
    CHECK(pbkdf2Sha256("password", salt, 1, out));
    CHECK(hexEncode(out, 32) == "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b");
    CHECK(pbkdf2Sha256("password", salt, 2, out));
    CHECK(hexEncode(out, 32) == "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43");
    CHECK(pbkdf2Sha256("password", salt, 4096, out));
    CHECK(hexEncode(out, 32) == "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a");
    CHECK(!pbkdf2Sha256("password", salt, 0, out));
    CHECK(!pbkdf2Sha256("password", salt, MAX_ITERATIONS + 1, out));

    // Password verification against the fixture hash.
    SignedCredentials g = good();
    CHECK(passwordMatches("correct horse battery", g.saltHex, g.iterations, g.hashHex));
    CHECK(!passwordMatches("correct horse batterY", g.saltHex, g.iterations, g.hashHex));
    CHECK(!passwordMatches("", g.saltHex, g.iterations, g.hashHex));
    CHECK(!passwordMatches("correct horse battery", "zz", g.iterations, g.hashHex));
    CHECK(!passwordMatches("correct horse battery", g.saltHex, g.iterations, "abcd"));

    // Signed credential acceptance.
    CHECK(checkCredentials(g, FX_GOOD_SIG, FX_PUB, sizeof(FX_PUB), 0) == CredCheck::Ok);
    CHECK(checkCredentials(g, FX_GOOD_SIG, FX_PUB, sizeof(FX_PUB), g.version - 1) == CredCheck::Ok);
    CHECK(checkCredentials(g, FX_GOOD_SIG, FX_PUB, sizeof(FX_PUB), g.version) == CredCheck::NotNewer);
    CHECK(checkCredentials(g, FX_GOOD_SIG, FX_PUB, sizeof(FX_PUB), g.version + 5) == CredCheck::NotNewer);

    // Tampering with any signed field breaks the signature.
    SignedCredentials t = g;
    t.version += 1;
    CHECK(checkCredentials(t, FX_GOOD_SIG, FX_PUB, sizeof(FX_PUB), 0) == CredCheck::BadSignature);
    t = g;
    t.iterations += 1;
    CHECK(checkCredentials(t, FX_GOOD_SIG, FX_PUB, sizeof(FX_PUB), 0) == CredCheck::BadSignature);
    t = g;
    t.hashHex[0] = (t.hashHex[0] == 'a') ? 'b' : 'a';
    CHECK(checkCredentials(t, FX_GOOD_SIG, FX_PUB, sizeof(FX_PUB), 0) == CredCheck::BadSignature);
    t = g;
    t.saltHex[0] = (t.saltHex[0] == 'a') ? 'b' : 'a';
    CHECK(checkCredentials(t, FX_GOOD_SIG, FX_PUB, sizeof(FX_PUB), 0) == CredCheck::BadSignature);

    // A file signed by a different key is refused even though it is internally consistent.
    SignedCredentials f;
    f.version = FX_FORGED_VER;
    f.iterations = FX_FORGED_ITER;
    f.saltHex = FX_FORGED_SALT;
    f.hashHex = FX_FORGED_HASH;
    CHECK(checkCredentials(f, FX_FORGED_SIG, FX_PUB, sizeof(FX_PUB), 0) == CredCheck::BadSignature);
    // Another file's signature does not transfer.
    CHECK(checkCredentials(f, FX_GOOD_SIG, FX_PUB, sizeof(FX_PUB), 0) == CredCheck::BadSignature);

    // Garbage signature, empty key and bad formats.
    CHECK(checkCredentials(g, "!!!notbase64", FX_PUB, sizeof(FX_PUB), 0) == CredCheck::BadSignature);
    CHECK(checkCredentials(g, "", FX_PUB, sizeof(FX_PUB), 0) == CredCheck::BadSignature);
    CHECK(checkCredentials(g, FX_GOOD_SIG, nullptr, 0, 0) == CredCheck::BadSignature);
    t = g;
    t.version = 0;
    CHECK(checkCredentials(t, FX_GOOD_SIG, FX_PUB, sizeof(FX_PUB), 0) == CredCheck::BadFormat);
    t = g;
    t.iterations = MAX_ITERATIONS + 1;
    CHECK(checkCredentials(t, FX_GOOD_SIG, FX_PUB, sizeof(FX_PUB), 0) == CredCheck::BadFormat);
    t = g;
    t.saltHex = "00";
    CHECK(checkCredentials(t, FX_GOOD_SIG, FX_PUB, sizeof(FX_PUB), 0) == CredCheck::BadFormat);
    t = g;
    t.hashHex = "00";
    CHECK(checkCredentials(t, FX_GOOD_SIG, FX_PUB, sizeof(FX_PUB), 0) == CredCheck::BadFormat);

    printf("%d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
