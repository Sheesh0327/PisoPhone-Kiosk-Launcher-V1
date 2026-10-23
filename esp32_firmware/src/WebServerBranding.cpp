// Admin API for the shop's branding: GET /api/branding, POST /api/branding (all four settings). A change is checked as a whole
// first: if anything in it is wrong, nothing is saved and the answer says which setting and why.

#include "WebServerBranding.h"

#include <WebServer.h>

#include "Config.h"
#include "ShopBrandingStore.h"
#include "WebServerAuth.h"
#include "WebServerModule.h"

using namespace shopbranding;

static void sendError(int code, const String& message) {
    webServer.send(code, "application/json", "{\"success\":false,\"error\":\"" + message + "\"}");
}

static void sendState() {
    String json = shopBrandingJson();
    json.setCharAt(0, ','); // "{...}" -> ",...}" so "success" can lead
    webServer.send(200, "application/json", "{\"success\":true" + json);
}

void handleApiBranding() {
    if (webServer.method() == HTTP_GET) {
        if (!checkAdminAuth()) return;
        sendState();
        return;
    }
    if (webServer.method() != HTTP_POST) {
        sendError(405, "USE_POST");
        return;
    }
    if (!checkAdminAuth()) return;
    if (!webServer.hasArg("palette") || !webServer.hasArg("name") || !webServer.hasArg("title") || !webServer.hasArg("density")) {
        sendError(400, "NO_BRANDING");
        return;
    }
    Branding next;
    next.palette = std::string(webServer.arg("palette").c_str());
    next.name = std::string(webServer.arg("name").c_str());
    next.title = std::string(webServer.arg("title").c_str());
    next.density = std::string(webServer.arg("density").c_str());
    const char* why = refusalFor(next); // the first problem found; nothing is saved on a refusal
    if (why[0] != '\0') {
        sendError(400, why);
        return;
    }
    if (!shopBrandingSave(next)) {
        sendError(500, "NOT_SAVED");
        return;
    }
    pushBrandingToPhones(); // every paired phone follows the box
    sendState();
}
