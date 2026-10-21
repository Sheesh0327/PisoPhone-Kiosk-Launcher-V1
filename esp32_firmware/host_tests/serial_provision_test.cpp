// Host test for SerialProvision.h: the first-time setup line the setup program sends over USB.
#include "../include/SerialProvision.h"

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
using namespace provision;

static std::string hex(const std::string& s) {
    static const char* d = "0123456789abcdef";
    std::string out;
    for (unsigned char c : s) {
        out += d[c >> 4];
        out += d[c & 15];
    }
    return out;
}

static Parse parse(const std::string& ssid, const std::string& pass, const std::string& admin, Request& r) {
    return parseSetup(hex(ssid) + " " + hex(pass) + " " + hex(admin), r);
}

int main() {
    Request r;
    // a good line: names with spaces and symbols survive (that is why the words are hex)
    CHECK(parse("Dela Cruz Store 5G", "p@ss w0rd!", "Shop-Admin-2026", r) == Parse::Ok);
    CHECK(r.wifiName == "Dela Cruz Store 5G" && r.wifiPassword == "p@ss w0rd!" && r.adminPassword == "Shop-Admin-2026");
    CHECK(parseSetup(hex("a") + "  " + hex("12345678") + " " + hex("abcdefgh") + " ", r) ==
          Parse::Ok); // (extra spaces are fine)
    CHECK(parseSetup(hex("A") + " " + std::string("3132333435363738") + " " + hex("abcdefgh"), r) == Parse::Ok);
    CHECK(parseSetup("41 3132333435363738 6162636465666768", r) == Parse::Ok && r.wifiName == "A");
    CHECK(parseSetup("41 3132333435363738 6162636465666768", r) == Parse::Ok);
    CHECK(parseSetup("4A 3132333435363738 4142434445464748", r) == Parse::Ok); // upper-case hex

    // lengths: the Wi-Fi name is 1 to 32, its password 8 to 63, the admin password 8 or more
    CHECK(parse(std::string(32, 'a'), "12345678", "abcdefgh", r) == Parse::Ok);
    CHECK(parse(std::string(33, 'a'), "12345678", "abcdefgh", r) ==
          Parse::BadFormat); // (too long to be accepted as a word at all)
    CHECK(parse("shop", "1234567", "abcdefgh", r) == Parse::BadWifiPassword);
    CHECK(parse("shop", std::string(63, 'p'), "abcdefgh", r) == Parse::Ok);
    CHECK(parse("shop", std::string(64, 'p'), "abcdefgh", r) == Parse::BadFormat);
    CHECK(parse("shop", "12345678", "short", r) == Parse::BadAdminPassword);
    CHECK(parse("shop", "12345678", setupgate::DEFAULT_PASSWORD, r) ==
          Parse::BadAdminPassword); // the published default is not a choice
    CHECK(parse("shop", "12345678", std::string(64, 'x'), r) == Parse::Ok);

    // characters: no control characters, nothing past plain ASCII, no NUL
    CHECK(parse(std::string("sh\x01p"), "12345678", "abcdefgh", r) == Parse::BadWifiName);
    CHECK(parse(std::string("sh\xc3\xa9p"), "12345678", "abcdefgh", r) == Parse::BadWifiName);
    CHECK(parse(std::string("sh\0p", 4), "12345678", "abcdefgh", r) == Parse::BadWifiName);
    CHECK(parse("shop",
                std::string("1234\0"
                            "5678",
                            9),
                "abcdefgh", r) == Parse::BadWifiPassword);
    CHECK(parse("shop", "12345678", std::string("abcd\nefg"), r) == Parse::BadAdminPassword);

    // a damaged line (the USB buffer is small): nothing is accepted
    CHECK(parseSetup("", r) == Parse::BadFormat);
    CHECK(parseSetup(hex("shop"), r) == Parse::BadFormat);
    CHECK(parseSetup(hex("shop") + " " + hex("12345678"), r) == Parse::BadFormat);
    CHECK(parseSetup(hex("shop") + " " + hex("12345678") + " " + hex("abcdefgh") + " 00", r) == Parse::BadFormat);
    CHECK(parseSetup("zz 3132333435363738 6162636465666768", r) == Parse::BadFormat);
    CHECK(parseSetup("4 3132333435363738 6162636465666768", r) == Parse::BadFormat);
    std::string line = hex("shop") + " " + hex("12345678") + " " + hex("abcdefgh");
    CHECK(parseSetup(line.substr(0, line.size() - 1), r) == Parse::BadFormat); // cut short in the middle of a byte
    CHECK(parseSetup(line.substr(0, line.size() - 2), r) !=
          Parse::Ok); // cut short: the admin password is too short now

    CHECK(std::string(reason(Parse::BadAdminPassword)) == "bad-admin-password" &&
          std::string(reason(Parse::Ok)) == "ok");

    printf("serial_provision_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
