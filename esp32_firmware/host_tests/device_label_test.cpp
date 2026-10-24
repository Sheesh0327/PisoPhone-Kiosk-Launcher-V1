// The label a phone shows as its title (include/DeviceLabel.h, feature 024): the name of its slot's profile, nothing for the standard rate.
// Display only: the rate of every profile is the same before and after.
#include <cstdio>
#include <string>

#include "../include/DeviceLabel.h"

using namespace devicelabel;
using phoneprofiles::Profile;

static int failures = 0;
#define CHECK(cond, msg)                                                                                               \
    do {                                                                                                               \
        if (!(cond)) {                                                                                                 \
            std::printf("FAIL: %s\n", msg);                                                                            \
            failures++;                                                                                                \
        }                                                                                                              \
    } while (0)

int main() {
    // the three presets, exactly as the owner asked for them, and each is a name a profile may have
    CHECK(PRESET_COUNT == 3, "three presets");
    CHECK(std::string(presetName(0)) == "PisoPhone" && std::string(presetName(1)) == "PisoTab" &&
              std::string(presetName(2)) == "PisoPhone Pro",
          "the preset names");
    CHECK(std::string(presetName(3)).empty() && std::string(presetName(-1)).empty(), "no preset outside the list");
    for (int i = 0; i < PRESET_COUNT; i++)
        CHECK(phoneprofiles::validName(presetName(i)), "a preset is a valid profile name");

    Profile list[3] = {{1, "PisoTab", 6}, {2, "PisoPhone Pro", 8}, {5, "Tab Kids", 4}};

    // a slot's label is its profile's name; the standard rate (0) and an unknown or deleted profile have none
    CHECK(labelFor(list, 3, 1) == "PisoTab", "profile 1 is a PisoTab");
    CHECK(labelFor(list, 3, 2) == "PisoPhone Pro", "profile 2 is a PisoPhone Pro");
    CHECK(labelFor(list, 3, 5) == "Tab Kids", "a custom name");
    CHECK(labelFor(list, 3, 0).empty(), "the standard rate has no label: the phone keeps the box's own title");
    CHECK(labelFor(list, 3, 9).empty(), "an unknown profile has no label");
    CHECK(labelFor(list, 3, -1).empty(), "a negative id has no label");
    CHECK(labelFor(list, 0, 1).empty(), "no profiles, no label");

    // the push: the label alone, encoded; blank on purpose to clear
    CHECK(pushBody("PisoTab") == "device_label=PisoTab", "a plain label");
    CHECK(pushBody("PisoPhone Pro") == "device_label=PisoPhone+Pro", "a space is encoded like the branding text");
    CHECK(pushBody("") == "device_label=", "a blank label clears");
    CHECK(pushBody("A.B-C_D~E") == "device_label=A.B-C_D~E", "the safe characters pass");

    // the longest name always fits the push buffer, even if every byte is encoded
    std::string longest(phoneprofiles::MAX_NAME_LEN, '\xC3'); // 20 bytes, each encoded as %XX
    CHECK(fitsInPush(longest), "a 20-byte name fits");
    CHECK(pushBody(longest).size() <= 14 + 3 * (size_t)phoneprofiles::MAX_NAME_LEN, "at most three characters a byte");

    // display only: a label never changes a rate (the rate lookup is PhoneProfiles.h and takes no label)
    int before = phoneprofiles::rateFor(list, 3, 2, 6);
    (void)labelFor(list, 3, 2);
    (void)pushBody("anything");
    CHECK(phoneprofiles::rateFor(list, 3, 2, 6) == before && before == 8,
          "a profile's rate is the same after labelling");
    CHECK(phoneprofiles::rateFor(list, 3, 0, 6) == 6 && phoneprofiles::rateFor(list, 3, 9, 6) == 6,
          "standard and unknown use the standard rate");

    if (failures) {
        std::printf("%d failure(s)\n", failures);
        return 1;
    }
    std::printf("all device label checks passed\n");
    return 0;
}
