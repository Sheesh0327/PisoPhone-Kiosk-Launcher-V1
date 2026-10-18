// The rental phone profiles (include/PhoneProfiles.h): names, rates, the stored text and what a slot uses.
#include <cstdio>

#include "../include/PhoneProfiles.h"

using namespace phoneprofiles;

static int failures = 0;
#define CHECK(cond, msg)                                                                                               \
    do {                                                                                                               \
        if (!(cond)) {                                                                                                 \
            std::printf("FAIL: %s\n", msg);                                                                            \
            failures++;                                                                                                \
        }                                                                                                              \
    } while (0)

int main() {
    // names the dashboard and JSON can carry
    CHECK(validName("Gaming phone") && validName("Tablet 10\"") == false, "plain names ok, a quote refused");
    CHECK(!validName("") && !validName(" Tablet") && !validName("Tablet "), "empty or padded names refused");
    CHECK(!validName("a|b") && !validName("a;b") && !validName("<b>") && !validName("a&b") && !validName("a\\b"),
          "separators and markup refused");
    CHECK(!validName(std::string("a\nb")) && !validName(std::string("a\x01")), "control characters refused");
    CHECK(validName("12345678901234567890") && !validName("123456789012345678901"), "20 bytes at most");

    // rates
    CHECK(validMinutes(1) && validMinutes(1440) && !validMinutes(0) && !validMinutes(1441) && !validMinutes(-5),
          "1..1440");
    CHECK(parseNumber("12") == 12 && parseNumber("") == -1 && parseNumber("12a") == -1 && parseNumber("-3") == -1 &&
              parseNumber("123456") == -1,
          "numbers from text");

    // the stored text round-trips
    Profile in[3] = {{1, "Basic phone", 6}, {2, "Gaming phone", 3}, {5, "Tablet", 10}};
    std::string raw = serializeList(in, 3);
    CHECK(raw == "1|Basic phone|6;2|Gaming phone|3;5|Tablet|10", "serialised");
    Profile out[MAX_PROFILES];
    int n = parseList(raw, out, MAX_PROFILES);
    CHECK(n == 3 && out[1].id == 2 && out[1].name == "Gaming phone" && out[2].minutes == 10, "read back");
    CHECK(parseList("", out, MAX_PROFILES) == 0, "empty text is no profile");

    // damaged text: bad entries skipped, repeated ids keep the first, never more than asked for
    n = parseList("1|Basic|6;x|Bad|3;2|Zero|0;3|Big|9999;4||5;1|Again|7;6|Ok|8;broken", out, MAX_PROFILES);
    CHECK(n == 2 && out[0].id == 1 && out[0].name == "Basic" && out[1].id == 6, "damaged entries are skipped");
    n = parseList("1|A|1;2|B|2;3|C|3;4|D|4;5|E|5;6|F|6;7|G|7;8|H|8;9|I|9", out, MAX_PROFILES);
    CHECK(n == MAX_PROFILES, "no more than the limit are read");

    // the rate a slot gets: its profile's, or the standard one when the profile is unknown
    CHECK(rateFor(in, 3, 2, 6) == 3, "a profile's own rate");
    CHECK(rateFor(in, 3, 0, 6) == 6, "profile 0 is the standard rate");
    CHECK(rateFor(in, 3, 9, 6) == 6, "an unknown profile is the standard rate");
    CHECK(rateFor(in, 0, 2, 6) == 6, "no profiles: the standard rate");
    CHECK(rateFor(in, 3, 2, 0) == 3 && rateFor(in, 3, 0, 0) == 1, "a broken standard rate falls back to 1, not 0");
    Profile bad[1] = {{1, "Bad", 0}};
    CHECK(rateFor(bad, 1, 1, 6) == 6, "a stored rate of 0 is never used");

    // ids
    CHECK(freeId(in, 3) == 3 && freeId(in, 0) == 1, "the lowest free id");

    // what each slot uses
    int ids[4];
    parseAssign("0,2,5,9", ids, 4);
    CHECK(ids[0] == 0 && ids[1] == 2 && ids[2] == 5 && ids[3] == 9, "assignments read");
    parseAssign("2", ids, 4);
    CHECK(ids[0] == 2 && ids[1] == 0 && ids[3] == 0, "slots that are not listed use the standard rate");
    parseAssign("x,-1,300,2", ids, 4);
    CHECK(ids[0] == 0 && ids[1] == 0 && ids[2] == 0 && ids[3] == 2, "junk and out-of-range ids become standard");
    int back[4] = {0, 2, 0, 5};
    CHECK(serializeAssign(back, 4) == "0,2,0,5", "assignments written");

    // deleting a profile sends its slots back to the standard rate, and only those
    int slots[4] = {1, 2, 5, 0};
    Profile left[2] = {{1, "Basic phone", 6}, {5, "Tablet", 10}};
    CHECK(sanitizeAssign(slots, 4, left, 2) == 1 && slots[0] == 1 && slots[1] == 0 && slots[2] == 5 && slots[3] == 0,
          "only the slot of the deleted profile changes");

    std::printf("phone profiles: %s\n", failures ? "FAILED" : "OK");
    return failures ? 1 : 0;
}
