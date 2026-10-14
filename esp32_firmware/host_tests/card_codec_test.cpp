// The box side of the QR card format: CardCodec.h must accept the cards the Python tools make (scripts/make_cards.py via
// gen_card_fixture.py) and refuse every way of tampering with one.
#include "../include/CardCodec.h"
#include "card_fixture.h"

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

static card::Check verify(const std::string& text, const std::string& box = FIX_BOX) {
    card::Card c;
    return card::verify(text, box, FIX_PUBKEY, sizeof(FIX_PUBKEY), c);
}

int main() {
    using card::Check;
    card::Card c;

    // A good card gives back exactly what was printed on it.
    CHECK(card::verify(CARD_GOOD, FIX_BOX, FIX_PUBKEY, sizeof(FIX_PUBKEY), c) == Check::OK);
    CHECK(c.serial == 42 && c.seconds == 10800 && c.box == FIX_BOX);
    CHECK(verify(CARD_GOOD_5H) == Check::OK && verify(CARD_GOOD_MAX_SERIAL) == Check::OK);
    CHECK(card::verify(CARD_GOOD_5H, FIX_BOX, FIX_PUBKEY, sizeof(FIX_PUBKEY), c) == Check::OK && c.seconds == 18000 &&
          c.serial == 7);

    // The box id may be written the way the dashboard shows the MAC.
    CHECK(verify(CARD_GOOD, "AA:BB:CC:DD:EE:FF") == Check::OK);
    CHECK(verify(CARD_GOOD, "aa-bb-cc-dd-ee-ff") == Check::OK);

    // Every kind of tampering is refused.
    CHECK(verify(CARD_BAD_SERIAL) == Check::BAD_SIGNATURE);
    CHECK(verify(CARD_BAD_SECONDS) == Check::BAD_SIGNATURE);
    CHECK(verify(CARD_OTHER_KEY) == Check::BAD_SIGNATURE);
    CHECK(verify(CARD_OTHER_BOX) == Check::WRONG_BOX);            // a card for another box, however well signed
    CHECK(verify(CARD_GOOD, "112233445566") == Check::WRONG_BOX); // and this card on another box
    CHECK(verify(CARD_GOOD, "") == Check::WRONG_BOX);

    // No card key installed (the placeholder): nothing is accepted.
    static const uint8_t placeholder[] = {0};
    CHECK(card::verify(CARD_GOOD, FIX_BOX, placeholder, 0, c) == Check::CARDS_OFF);
    CHECK(card::verify(CARD_GOOD, FIX_BOX, placeholder, sizeof(placeholder), c) == Check::CARDS_OFF);
    CHECK(card::verify(CARD_GOOD, FIX_BOX, nullptr, 0, c) == Check::CARDS_OFF);

    // Malformed text is refused before any signature work, and never crashes.
    std::string good = CARD_GOOD;
    const char* bad[] = {"",
                         "PISO1",
                         "PISO1....",
                         "PISO2.aabbccddeeff.42.10800.AAAA",
                         "PISO1.AABBCCDDEEFF.42.10800.AAAA",    // upper-case box
                         "PISO1.aabbccddeeff.0.10800.AAAA",     // serial 0
                         "PISO1.aabbccddeeff.65536.10800.AAAA", // serial too large
                         "PISO1.aabbccddeeff.42.59.AAAA",       // too little time
                         "PISO1.aabbccddeeff.42.864001.AAAA",   // too much time
                         "PISO1.aabbccddeeff.42.10800.",        // no signature
                         "PISO1.aabbccddeeff.42.10800.AAA",     // not a multiple of 4
                         "PISO1.aabbccddeeff.42.10800.AA*A",    // not base64
                         "PISO1.aabbccddeeff.4x.10800.AAAA",
                         "PISO1.aabbccddeeff.42.10800.AAAA.extra",
                         "PISO1.aabbccddeeff.-1.10800.AAAA",
                         "PISO1.aabbccddeeff.99999999999.10800.AAAA"};
    for (const char* b : bad)
        CHECK(verify(b) == Check::MALFORMED);
    CHECK(verify(good + ".x") == Check::MALFORMED);
    CHECK(verify(std::string(500, 'A')) == Check::MALFORMED);
    CHECK(verify(good.substr(0, good.size() - 4)) != Check::OK); // cut short

    // Flipping any single character of a good card never gives a card that is accepted as something else. (The unused low bits of
    // the last base64 character can change without changing the signature bytes: that text is the same card, same serial and
    // same hours, so it may still be accepted, but only as exactly that card.)
    for (size_t i = 0; i < good.size(); i++) {
        std::string t = good;
        t[i] = (t[i] == 'A') ? 'B' : 'A';
        if (t == good) continue;
        card::Card x;
        Check r = card::verify(t, FIX_BOX, FIX_PUBKEY, sizeof(FIX_PUBKEY), x);
        if (r == Check::OK) {
            CHECK(x.serial == 42 && x.seconds == 10800 && x.box == FIX_BOX);
            CHECK(i >= good.size() - 3); // only inside the signature's final characters
        }
    }

    printf("card_codec_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
