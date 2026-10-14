// Host test for BoxLicense.h using tokens signed by the real scripts/sign_box_type.py.
#include "../include/BoxLicense.h"
#include "box_license_fixture.h"
#include <cstdio>
#include <string>

using namespace boxlicense;
static int failures = 0, checks = 0;
#define CHECK(c)                                                                                                       \
    do {                                                                                                               \
        checks++;                                                                                                      \
        if (!(c)) {                                                                                                    \
            failures++;                                                                                                \
            printf("FAIL line %d: %s\n", __LINE__, #c);                                                                \
        }                                                                                                              \
    } while (0)

static Check run(const std::string& token, const char* mac = LIC_MAC, Type* out = nullptr) {
    Type t = Type::Purchased;
    Check r = verifyToken(token, mac, LIC_PUB, sizeof(LIC_PUB), t);
    if (out) *out = t;
    return r;
}

int main() {
    // the numbers
    CHECK(capFor(Type::Purchased) == 6);
    CHECK(capFor(Type::Rental) == 12);
    CHECK(std::string(name(Type::Purchased)) == "purchased" && std::string(name(Type::Rental)) == "rental");
    Type t;
    CHECK(parseType("rental", t) && t == Type::Rental);
    CHECK(parseType("purchased", t) && t == Type::Purchased);
    CHECK(!parseType("Rental", t) && !parseType("", t) && !parseType("lifetime", t));

    // pairing: the seventh phone on a purchased box is refused, the twelfth on a rental box is fine, the thirteenth is not
    CHECK(mayPair(Type::Purchased, 5, 6));
    CHECK(!mayPair(Type::Purchased, 6, 7));
    CHECK(mayPair(Type::Rental, 11, 12));
    CHECK(!mayPair(Type::Rental, 12, 13));
    // a box that already holds more than its type allows keeps working: swapping a phone is fine, adding one is not
    CHECK(mayPair(Type::Purchased, 9, 9));
    CHECK(!mayPair(Type::Purchased, 9, 10));
    CHECK(mayPair(Type::Purchased, 9, 8)); // (an unpairing is never refused)

    // addresses
    CHECK(normalizeMac("AA:BB:CC:00:11:22") == "aabbcc001122");
    CHECK(normalizeMac("aa-bb-cc-00-11-22") == "aabbcc001122");
    CHECK(normalizeMac("aabbcc001122") == "aabbcc001122");
    CHECK(normalizeMac("aa:bb:cc").empty() && normalizeMac("zz:bb:cc:00:11:22").empty() && normalizeMac("").empty());

    // tokens
    Type got = Type::Purchased;
    CHECK(run(LIC_RENTAL, LIC_MAC, &got) == Check::Ok && got == Type::Rental);
    CHECK(run(LIC_PURCHASED, LIC_MAC, &got) == Check::Ok && got == Type::Purchased);
    CHECK(run(LIC_RENTAL, "aabbcc001122") == Check::Ok); // the box's address in any spelling
    CHECK(run(LIC_RENTAL_OTHER_BOX) == Check::WrongBox); // signed for another box
    CHECK(run(LIC_RENTAL, "AA:BB:CC:00:11:23") == Check::WrongBox);
    CHECK(run(LIC_RENTAL_OTHER_KEY) == Check::BadSignature); // not the owner's key
    CHECK(verifyToken(LIC_RENTAL, LIC_MAC, nullptr, 0, got) == Check::NoKey);

    // a token cannot be turned into another type: the type is part of what was signed
    std::string swapped = std::string(LIC_PURCHASED);
    swapped.replace(0, std::string("purchased").size(), "rental");
    CHECK(run(swapped) == Check::BadSignature);
    std::string up = LIC_PURCHASED;
    up.replace(0, 9, "rental");
    CHECK(run(up) == Check::BadSignature);

    // damaged tokens
    CHECK(run("") == Check::BadFormat);
    CHECK(run("rental") == Check::BadFormat);
    CHECK(run("rental:aabbcc001122") == Check::BadFormat);
    CHECK(run("lifetime:aabbcc001122:" + std::string(60, 'A')) == Check::BadFormat);
    CHECK(run("rental:nonsense:" + std::string(60, 'A')) == Check::BadFormat);
    CHECK(run("rental:aabbcc001122:AAAA") == Check::BadFormat);
    std::string flipped = LIC_RENTAL;
    flipped[flipped.size() - 8] = flipped[flipped.size() - 8] == 'A' ? 'B' : 'A';
    CHECK(run(flipped) == Check::BadSignature);
    CHECK(run(std::string(LIC_RENTAL) + "\r\n") == Check::Ok); // a pasted trailing newline is harmless

    if (failures) {
        printf("box_license_test: %d of %d checks FAILED\n", failures, checks);
        return 1;
    }
    printf("box_license_test: all %d checks passed\n", checks);
    return 0;
}
