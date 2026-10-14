#include "../include/SecretMode.h"
#include "../include/CredGen.h"

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

static void fill(uint8_t* b, size_t n) {
    static uint32_t s = 99;
    for (size_t i = 0; i < n; i++) {
        s = s * 1103515245u + 12345u;
        b[i] = (uint8_t)(s >> 16);
    }
}

int main() {
    using namespace secretmode;
    // upgrade of a box that was already set up: stays on the old key until the operator switches
    CHECK(startInLegacyMode(false, false, true));
    // brand-new box: its own key from the start
    CHECK(!startInLegacyMode(false, false, false));
    // once decided, the stored setting wins (switching is permanent)
    CHECK(!startInLegacyMode(true, false, true));
    CHECK(startInLegacyMode(true, true, false));

    // the admin password goes to phones only under the box's own key, and only if the phone would read it back unchanged
    CHECK(mayPushAdminPassword(false, "Str0ng!Pass#1"));
    CHECK(!mayPushAdminPassword(true, "Str0ng!Pass#1"));
    CHECK(!mayPushAdminPassword(false, ""));
    CHECK(!mayPushAdminPassword(false, "has&other"));
    CHECK(!mayPushAdminPassword(false, "a=b"));
    CHECK(!mayPushAdminPassword(false, "a+b"));
    CHECK(!mayPushAdminPassword(false, "50%off"));
    CHECK(!mayPushAdminPassword(false, "two words"));
    CHECK(!mayPushAdminPassword(false, "caf\xc3\xa9"));

    CHECK(validSecret("abcdefghijklmnop"));
    CHECK(validSecret("A1_b2.c3+d4=e5-F6"));
    CHECK(!validSecret("short"));
    CHECK(!validSecret("has space in it!!!"));
    CHECK(!validSecret("quote\"inside123456"));
    CHECK(!validSecret(std::string(129, 'a')));
    CHECK(validSecret(std::string(128, 'a')));

    // what the box generates must pass its own validity rule
    for (int i = 0; i < 50; i++)
        CHECK(validSecret(credgen::password(32, fill)));

    printf("secret_mode_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
