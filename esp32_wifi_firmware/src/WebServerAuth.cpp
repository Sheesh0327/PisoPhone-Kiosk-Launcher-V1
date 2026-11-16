// HTTP authentication for the headless PisoWiFi daemon: strict HTTP Basic Auth
// ("admin:<password>") with per-client failed-login lockout (InputSafety.h).

#include "WebServerAuth.h"
#include "Config.h"
#include "Diagnostics.h"
#include "InputSafety.h"
#include "WebServerModule.h"

static inputsafety::LoginThrottle loginThrottle;

bool defaultCredentialsActive() {
    return !adminPwChanged;
}

static void denyAdmin() {
    if (webServer.uri().startsWith("/api/")) {
        static unsigned long lastNoteMs = 0;
        if (lastNoteMs == 0 || millis() - lastNoteMs > 60000UL) {
            lastNoteMs = millis() == 0 ? 1 : millis();
            diagLog("[AUTH] %s answered 401 to %s: password %s\n", webServer.uri().c_str(),
                    webServer.client().remoteIP().toString().c_str(),
                    webServer.hasHeader("Authorization") ? "sent but not accepted" : "not sent");
        }
        webServer.send(401, "application/json", "{\"error\":\"login\"}");
        return;
    }
    webServer.requestAuthentication(BASIC_AUTH, "PisoWiFi Admin Login", "Unauthorized: Admin credentials required.");
}

bool checkAdminAuth() {
    uint32_t client = (uint32_t)webServer.client().remoteIP();
    unsigned long now = millis();
    unsigned long waitMs = loginThrottle.lockedForMs(client, now);
    if (waitMs > 0) {
        webServer.sendHeader("Retry-After", String((waitMs + 999) / 1000));
        webServer.send(429, "text/plain", "Too many failed logins. Try again later.");
        return false;
    }

    if (webServer.authenticate("admin", webPassword.c_str())) {
        loginThrottle.recordSuccess(client);
        return true;
    }

    if (webServer.hasHeader("Authorization")) {
        loginThrottle.recordFailure(client, now);
        diagLog("[AUTH] Failed admin login from %s\n", webServer.client().remoteIP().toString().c_str());
        delay(250);
    }
    denyAdmin();
    return false;
}
