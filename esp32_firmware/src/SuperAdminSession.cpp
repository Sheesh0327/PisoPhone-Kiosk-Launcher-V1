// The owner's QR login on the box: a challenge, the signature check against the public key built into the firmware
// (SuperAdminPubKey.h), and the session. The logic is in AdminQrAuth.h (tested on a PC); this file only connects it to the web
// server and the random number generator.

#include "SuperAdminSession.h"
#include "AdminQrAuth.h"
#include "SuperAdminPubKey.h"
#include "Config.h"
#include "Diagnostics.h"
#include "WebServerAuth.h"
#include "WebServerModule.h"
#include <WebServer.h>
#include <esp_random.h>

static adminqr::Auth auth;
static const char* SESSION_COOKIE = "piso_sa";

static String boxId() {
    if (macAddressStr.length() == 0) {
        uint8_t mac[6];
        esp_read_mac(mac, ESP_MAC_WIFI_STA);
        char buf[18];
        snprintf(buf, sizeof(buf), "%02X:%02X:%02X:%02X:%02X:%02X", mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
        macAddressStr = String(buf);
    }
    return macAddressStr;
}

static String sessionCookieValue() {
    String header = webServer.header("Cookie");
    String key = String(SESSION_COOKIE) + "=";
    int at = 0;
    while (at < (int)header.length()) {
        int end = header.indexOf(';', at);
        if (end < 0) end = header.length();
        String part = header.substring(at, end);
        part.trim();
        if (part.startsWith(key)) return part.substring(key.length());
        at = end + 1;
    }
    return "";
}

bool superAdminSessionOk() {
    if (!auth.active()) return false;
    String token = sessionCookieValue();
    return token.length() > 0 && auth.sessionValid(token.c_str(), millis());
}

void superAdminSessionEnd() {
    auth.end();
    webServer.sendHeader("Set-Cookie", String(SESSION_COOKIE) + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
}

void handleSuperAdminChallenge() {
    uint8_t random[adminqr::RANDOM_BYTES];
    esp_fill_random(random, sizeof(random));
    std::string nonce = auth.issueChallenge(random, millis());
    webServer.sendHeader("Cache-Control", "no-store");
    webServer.send(200, "application/json",
                   String("{\"nonce\":\"") + nonce.c_str() + "\",\"box\":\"" + boxId() + "\"}");
}

void handleSuperAdminQrLogin() {
    unsigned long wait = adminLoginLockedMs();
    if (wait > 0) {
        webServer.sendHeader("Retry-After", String((wait + 999) / 1000));
        webServer.send(429, "application/json",
                       "{\"status\":\"error\",\"message\":\"Too many tries. Wait a minute.\"}");
        return;
    }
    String nonce = webServer.arg("nonce");
    String sig = webServer.arg("sig");
    if (nonce.length() != adminqr::RANDOM_BYTES * 2 || sig.length() == 0 || sig.length() > 200) {
        webServer.send(400, "application/json", "{\"status\":\"error\",\"message\":\"Bad request.\"}");
        return;
    }
    uint8_t random[adminqr::RANDOM_BYTES];
    esp_fill_random(random, sizeof(random));
    std::string token;
    adminqr::Result r = auth.login(nonce.c_str(), sig.c_str(), boxId().c_str(), SUPER_ADMIN_PUBKEY_DER,
                                   SUPER_ADMIN_PUBKEY_LEN, random, millis(), token);
    if (r == adminqr::Result::Ok) {
        adminLoginRecordSuccess();
        diagLog("[AUTH] Super admin signed in with the admin QR code from %s\n",
                webServer.client().remoteIP().toString().c_str());
        webServer.sendHeader("Set-Cookie", String(SESSION_COOKIE) + "=" + token.c_str() +
                                               "; Path=/; HttpOnly; SameSite=Strict; Max-Age=3600");
        webServer.sendHeader("Cache-Control", "no-store");
        webServer.send(200, "application/json", "{\"status\":\"ok\"}");
        return;
    }
    adminLoginRecordFailure();
    diagLog("[AUTH] Admin QR login refused (%d) from %s\n", (int)r, webServer.client().remoteIP().toString().c_str());
    delay(250); // slows guessing without stalling the coin loop for long
    const char* message = r == adminqr::Result::NoKey          ? "This box was built without an admin key."
                          : r == adminqr::Result::UnknownNonce ? "That login took too long. Try again."
                                                               : "This code is not accepted by this box.";
    webServer.send(403, "application/json", String("{\"status\":\"error\",\"message\":\"") + message + "\"}");
}
