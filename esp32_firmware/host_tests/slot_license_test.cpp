// Host test for SlotLicense.h: the keys the owner signs, who may use them, and how many phones a box may pair.
#include "../include/SlotLicense.h"

#include <cstdio>
#include <string>

#include "slot_license_fixture.h"

static int failures = 0;
#define CHECK(c)                                                                                                       \
    do {                                                                                                               \
        if (!(c)) {                                                                                                    \
            printf("FAIL line %d: %s\n", __LINE__, #c);                                                                \
            failures++;                                                                                                \
        }                                                                                                              \
    } while (0)

using namespace slotlicense;

static Check verify(const std::string& token, const std::string& used, std::string& id) {
    return verifyToken(token, LIC_MAC, LIC_PUB, sizeof(LIC_PUB), used, id);
}

int main() {
    // the numbers: two slots are free, each key adds one, never above the box's 12
    CHECK(FREE_SLOTS == 2 && PRICE_PHP == 500 && MAX_SLOTS == 12);
    CHECK(capFor(0) == 2 && capFor(1) == 3 && capFor(10) == 12 && capFor(11) == 12 && capFor(-1) == 2);

    // a good key opens one slot, and says which key it was
    std::string id;
    CHECK(verify(LIC_A, "", id) == Check::Ok && id == "00000001");
    CHECK(verify(std::string("  ") + LIC_B + "\r\n", "", id) == Check::Ok &&
          id == "00000002"); // pasted with spaces around it
    CHECK(verifyToken(LIC_A, "aa-bb-cc-00-11-22", LIC_PUB, sizeof(LIC_PUB), "", id) ==
          Check::Ok); // the box code in any spelling

    // the same key twice opens one slot
    CHECK(verify(LIC_A, "00000001", id) == Check::AlreadyUsed);
    CHECK(verify(LIC_B, "00000001", id) == Check::Ok && id == "00000002");
    CHECK(verify(LIC_A, "00000003,00000001", id) == Check::AlreadyUsed);

    // a key for another box, from another key, or altered is refused
    CHECK(verify(LIC_OTHER_BOX, "", id) == Check::WrongBox);
    CHECK(verify(LIC_OTHER_KEY, "", id) == Check::BadSignature);
    CHECK(verify(LIC_BAD_MESSAGE, "", id) == Check::BadSignature); // its number was changed after signing
    std::string tampered = LIC_A;
    tampered[tampered.size() - 6] = tampered[tampered.size() - 6] == 'A' ? 'B' : 'A';
    CHECK(verify(tampered, "", id) == Check::BadSignature);
    std::string renamed = LIC_A;
    renamed.replace(renamed.find("00000001"), 8, "00000002");
    CHECK(verify(renamed, "", id) == Check::BadSignature); // a used number cannot be reused by editing it

    // rubbish
    CHECK(verify("", "", id) == Check::BadFormat);
    CHECK(verify("hello", "", id) == Check::BadFormat);
    CHECK(verify("PSLOT1.aabbcc001122.00000001", "", id) == Check::BadFormat);
    CHECK(verify(std::string("PSLOT2") + std::string(LIC_A).substr(6), "", id) == Check::BadFormat);
    CHECK(verify(std::string(LIC_A) + ".extra", "", id) == Check::BadFormat);
    CHECK(verify("PSLOT1.zzzzzzzzzzzz.00000001.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "", id) ==
          Check::BadFormat);
    CHECK(verifyToken(LIC_A, LIC_MAC, nullptr, 0, "", id) == Check::NoKey); // a box without the owner's key refuses all
    CHECK(verifyToken(LIC_A, "not a mac", LIC_PUB, sizeof(LIC_PUB), "", id) == Check::BadFormat);

    // every slot already opened
    std::string full = "00000010,00000011,00000012,00000013,00000014,00000015,00000016,00000017,00000018,00000019";
    CHECK(usedKeys(full) == 10);
    CHECK(verify(LIC_C, full, id) == Check::NoRoom);
    CHECK(verify(LIC_C, full.substr(0, full.rfind(',')), id) == Check::Ok);

    // the list of used keys: repeats, junk and too many are dropped
    CHECK(splitIds("0000000a,0000000A,zz,,0000000b").size() == 2);
    CHECK(joinIds(splitIds("0000000A, 0000000b")) == "0000000a"); // (a space makes an entry bad)
    CHECK(splitIds(full + ",00000020,00000021").size() == 10);
    CHECK(usedKeys("") == 0 && !hasId("", "0000000a") && hasId("0000000a,0000000b", "0000000b"));

    // boxes that were in a shop before licensing keep the slots they had (the floor); keys add above what they need
    CHECK(capWithFloor(0, 0) == 2 && capWithFloor(0, 2) == 2 && capWithFloor(0, 6) == 6 && capWithFloor(1, 6) == 6);
    CHECK(capWithFloor(5, 3) == 7 && capWithFloor(10, 12) == 12 && capWithFloor(0, 99) == 12 &&
          capWithFloor(0, -1) == 2);
    CHECK(mayPair(capWithFloor(0, 6), 5, 6) &&
          !mayPair(capWithFloor(0, 6), 6, 7)); // a shop with 6 phones can swap one, not add a 7th

    // pairing: a pairing that adds no phone is always allowed; one more phone only while there is a slot
    CHECK(mayPair(2, 0, 1) && mayPair(2, 1, 2));
    CHECK(!mayPair(2, 2, 3));
    CHECK(mayPair(2, 2, 2)); // the phone is already paired
    CHECK(mayPair(2, 5, 5)); // a box that held more phones before this rule keeps them
    CHECK(!mayPair(2, 5, 6));
    CHECK(mayPair(3, 2, 3) && !mayPair(3, 3, 4));
    CHECK(mayPair(capFor(10), 11, 12));

    if (failures == 0) printf("slot_license_test: all checks passed\n");
    return failures ? 1 : 0;
}
