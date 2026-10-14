#include "../include/OriginCheck.h"

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

int main() {
    using namespace origincheck;
    // the dashboard's own requests
    CHECK(!isCrossSite("http://192.168.1.50", "192.168.1.50"));
    CHECK(!isCrossSite("http://192.168.1.50/", "192.168.1.50"));
    CHECK(!isCrossSite("http://192.168.1.50/path?x=1#y", "192.168.1.50"));
    CHECK(!isCrossSite("http://BOX.local", "box.LOCAL"));
    CHECK(!isCrossSite("http://192.168.1.50:8080", "192.168.1.50:8080"));
    CHECK(!isCrossSite("http://[fe80::1]:81/", "[fe80::1]:81"));
    // not a browser page: no Origin and no Referer
    CHECK(!isCrossSite("", "192.168.1.50"));
    // another site's page
    CHECK(isCrossSite("https://evil.example", "192.168.1.50"));
    CHECK(isCrossSite("http://192.168.1.51", "192.168.1.50"));
    CHECK(isCrossSite("http://192.168.1.50:8080", "192.168.1.50")); // another port is another origin
    CHECK(isCrossSite("http://192.168.1.50", "192.168.1.50:8080"));
    CHECK(isCrossSite("null", "192.168.1.50"));                              // sandboxed iframe, redirect chain
    CHECK(isCrossSite("http://192.168.1.50.evil.example/", "192.168.1.50")); // look-alike host
    CHECK(isCrossSite("http://192.168.1.50@evil.example/", "192.168.1.50")); // user-info trick
    CHECK(isCrossSite("garbage", "192.168.1.50"));
    CHECK(isCrossSite("http://192.168.1.50", "")); // no Host header: cannot be judged, so refused
    printf("origin_check: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
