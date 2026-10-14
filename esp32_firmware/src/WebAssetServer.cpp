// Serves the embedded dashboard assets. The only file that includes the generated WebAssets.h, so each
// compressed array exists once in flash.

#include "WebAssetServer.h"
#include "WebAssets.h"
#include "WebServerModule.h"
#include <WebServer.h>

static void sendAsset(const WebAsset& asset) {
    // The pages link with ?v=<version>, which changes whenever the file does, so a matching version can be
    // cached for a year. Anything else (a hand-typed URL) is always revalidated.
    bool versioned = webServer.hasArg("v") && webServer.arg("v") == asset.version;
    webServer.sendHeader("Content-Encoding", "gzip");
    webServer.sendHeader("Cache-Control", versioned ? "public, max-age=31536000, immutable" : "no-cache");
    webServer.send_P(200, asset.contentType, reinterpret_cast<PGM_P>(asset.gz), asset.gzLen);
}

void registerWebAssetRoutes() {
    for (size_t i = 0; i < WEB_ASSET_COUNT; i++) {
        String path = String("/assets/") + WEB_ASSETS[i].name;
        webServer.on(path.c_str(), HTTP_GET, [i]() { sendAsset(WEB_ASSETS[i]); });
    }
}

String webAssetVersion(const char* name) {
    for (size_t i = 0; i < WEB_ASSET_COUNT; i++) {
        if (strcmp(WEB_ASSETS[i].name, name) == 0) return String(WEB_ASSETS[i].version);
    }
    return String("0");
}
