// Host test for GatewayEvent.h: the coin-event line the box sends to the router.
#include "../include/GatewayEvent.h"
#include <cstdio>

using namespace gatewayevent;
static int failures = 0, checks = 0;
#define CHECK(c)                                                                                                       \
    do {                                                                                                               \
        checks++;                                                                                                      \
        if (!(c)) {                                                                                                    \
            failures++;                                                                                                \
            printf("FAIL line %d: %s\n", __LINE__, #c);                                                                \
        }                                                                                                              \
    } while (0)

int main() {
    // Pinned to a value computed independently with Python hmac/sha256 (the router verifies it with openssl).
    CHECK(line("0123456789abcdef-key", "ab12", "f00d", 3, "coin", 7) ==
          "gw1ev:ab12:f00d:3:coin:7:8cdd2d26be0003967ad769d3dfaf7e62d63bb1f2838c1efdcd7c91f4131b7004\n");
    std::string l = line("0123456789abcdef-key", "ab12", "f00d", 3, "coin", 7);
    CHECK(l.rfind("gw1ev:ab12:f00d:3:coin:7:", 0) == 0);
    CHECK(l.size() == std::string("gw1ev:ab12:f00d:3:coin:7:").size() + 64 + 1);
    CHECK(l.back() == '\n');
    CHECK(body("s", "w", 0, "ready", 0) == "gw1ev:s:w:0:ready:0");

    CHECK(validWindowId("0123456789abcdef0123456789abcdef"));
    CHECK(!validWindowId(""));
    CHECK(!validWindowId("ABCDEF"));
    CHECK(!validWindowId("12:34"));
    CHECK(!validWindowId(std::string(41, 'a')));
    CHECK(validPort(8101) && !validPort(0) && !validPort(70000));

    printf("gateway_event_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
