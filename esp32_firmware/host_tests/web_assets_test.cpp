// Checks the generated WebAssets.h: every asset is a gzip stream with a plausible size and a version tag.
// (scripts/embed_web.py --check separately makes sure the header matches web/.)
#include "../include/WebAssets.h"

#include <cstdio>
#include <cstring>

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
    CHECK(WEB_ASSET_COUNT == 5);
    for (size_t i = 0; i < WEB_ASSET_COUNT; i++) {
        const WebAsset& a = WEB_ASSETS[i];
        CHECK(a.gzLen > 200 && a.gzLen < 20000);
        CHECK(a.gz[0] == 0x1f && a.gz[1] == 0x8b && a.gz[2] == 8); // gzip magic, deflate
        CHECK(strlen(a.version) == 12);
        CHECK(strstr(a.contentType, "text/css") || strstr(a.contentType, "javascript"));
        for (size_t j = i + 1; j < WEB_ASSET_COUNT; j++)
            CHECK(strcmp(a.name, WEB_ASSETS[j].name) != 0);
    }
    printf("web_assets_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
