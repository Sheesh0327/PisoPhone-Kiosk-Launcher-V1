// USB first-time setup and local diagnostics commands.

#include "SerialCli.h"
#include "Config.h"
#include "Diagnostics.h"
#include "FirmwareVersion.h"
#include "GatewaySessionStore.h"
#include "SerialProvision.h"
#include "WifiLink.h"
#include <Arduino.h>
#include <WiFi.h>

static provision::SetupState currentSetupState() {
    provision::BoxFacts f;
    f.adminPasswordChanged = adminPwChanged;
    f.wifiWait = wifiWaitFirstJoin;
    f.lifetimeCoins = gatewayStore().lifetimePulses();
    f.pendingPayments = getPendingPaymentCount();
    f.unpersistedPayments = hasUnpersistedPayments();
    return provision::setupState(f);
}

static void answerHello() {
    bool up = WiFi.status() == WL_CONNECTED;
    Serial.printf("PISO-BOX fw=%s mac=%s setup=%s wifi=%s ip=%s why=%s\n", PISO_FW_VERSION, macAddressStr.c_str(),
                  provision::stateName(currentSetupState()), up ? "up" : "down",
                  up ? WiFi.localIP().toString().c_str() : "-",
                  up ? "none" : wifilink::whyCode(wifiLastDisconnectReason));
}

static void applySetup(const String& rest) {
    if (!provision::acceptsSetup(currentSetupState())) {
        Serial.println("PISO-SETUP-ERR already-set-up");
        return;
    }
    provision::Request req;
    provision::Parse parsed = provision::parseSetup(rest.c_str(), req);
    if (parsed != provision::Parse::Ok) {
        Serial.printf("PISO-SETUP-ERR %s\n", provision::reason(parsed));
        return;
    }
    if (!canPerformRebootOrOta()) {
        Serial.println("PISO-SETUP-ERR busy");
        return;
    }
    wifiSsid = req.wifiName.c_str();
    wifiPass = req.wifiPassword.c_str();
    webPassword = req.adminPassword.c_str();
    prefs.begin(NVS_NAMESPACE, false);
    bool saved = prefs.putString(NVS_KEY_WIFI_SSID, wifiSsid) > 0 && prefs.putString(NVS_KEY_WIFI_PASS, wifiPass) > 0 &&
                 prefs.putString(NVS_KEY_ADMIN_PW, webPassword) > 0;
    if (saved) {
        wifiWaitFirstJoin = true;
        prefs.putBool(NVS_KEY_WIFI_WAIT, true);
        adminPwChanged = true;
        prefs.putBool(NVS_KEY_ADMIN_PW_CHANGED, true);
    }
    prefs.end();
    if (!saved) {
        Serial.println("PISO-SETUP-ERR could-not-save");
        return;
    }
    Serial.println("PISO-SETUP-OK");
    Serial.flush();
    diagLog("[SETUP] Wi-Fi '%s' and the admin password were set over USB; restarting.\n", wifiSsid.c_str());
    delay(500);
    diagNoteRestartReason("usb-setup");
    ESP.restart();
}

void processSerialCli() {
    if (!Serial.available()) return;
    String line = Serial.readStringUntil('\n');
    line.trim();
    if (line.length() == 0) return;

    if (line == "PISO-HELLO") {
        answerHello();
        return;
    }
    if (line.startsWith("PISO-SETUP ")) {
        applySetup(line.substring(11));
        return;
    }
    if (line.equalsIgnoreCase("help")) {
        Serial.println("\nCommands: PISO-HELLO | help");
    }
}
