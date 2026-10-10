#include "../include/SetupGate.h"

#include <cstdio>
#include <string>

static int checks = 0, failures = 0;
#define CHECK(c)                                                                                                       \
    do {                                                                                                               \
        checks++;                                                                                                      \
        if (!(c)) {                                                                                                    \
            failures++;                                                                                                \
            printf("FAIL line %d: %s\n", __LINE__, #c);                                                                \
        }                                                                                                              \
    } while (0)
using namespace setupgate;

int main() {
    CHECK(std::strcmp(DEFAULT_PASSWORD, "Coinslot@Setup") == 0);
    CHECK(std::strlen(DEFAULT_PASSWORD) >= MIN_PASSWORD_LENGTH); // usable as a Wi-Fi password too

    // a new admin password: not the default, not too short, not empty
    CHECK(!passwordAcceptable(DEFAULT_PASSWORD));
    CHECK(!passwordAcceptable(nullptr));
    CHECK(!passwordAcceptable(""));
    CHECK(!passwordAcceptable("short"));
    CHECK(!passwordAcceptable("1234567"));
    CHECK(passwordAcceptable("12345678"));
    CHECK(passwordAcceptable("My-shop-2026"));
    CHECK(passwordAcceptable("coinslot@setup")); // only the exact default is refused

    // the phones' Wi-Fi password the router hands over: 8-63 printable characters
    CHECK(!kioskWifiPasswordValid(nullptr));
    CHECK(!kioskWifiPasswordValid(""));
    CHECK(!kioskWifiPasswordValid("1234567"));
    CHECK(kioskWifiPasswordValid("12345678"));
    CHECK(kioskWifiPasswordValid("a&b#c%d+e=f g!"));
    CHECK(kioskWifiPasswordValid(std::string(63, 'x').c_str()));
    CHECK(!kioskWifiPasswordValid(std::string(64, 'x').c_str()));
    CHECK(!kioskWifiPasswordValid("tab\there1234"));
    CHECK(!kioskWifiPasswordValid("caf\xC3\xA9-password"));

    // coins only after the operator's own password
    CHECK(!usageAllowed(false));
    CHECK(usageAllowed(true));

    printf("setup_gate_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
