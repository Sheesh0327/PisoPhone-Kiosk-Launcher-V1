// USB first-time setup and local maintenance commands. Physical USB access is trusted.
#include "PilotLicense.h"
#include "SerialCli.h"
#include "Config.h"
#include "DeviceNetwork.h"
#include "Diagnostics.h"
#include "FirmwareVersion.h"
#include "PaymentQueueManager.h"
#include "SerialProvision.h"
#include "WifiLink.h"
#include "WebServerAuth.h"
#include <Arduino.h>
#include <WiFi.h>

// needed / joining / done (the rule and the reason are in SerialProvision.h)
static provision::SetupState currentSetupState() {
    provision::BoxFacts f;
    f.adminPasswordChanged = adminPwChanged;
    f.wifiWait = wifiWaitFirstJoin;
    f.lifetimeCoins = totalCoinsLifetime;
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

// Saves the shop's Wi-Fi and the admin password, then restarts so the box joins that Wi-Fi. Box and phones share one Wi-Fi.
// Accepted by a box that was never set up, and by one that was set up this way but never got an address and has no payment.
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
#if PISO_HAS_PHONES
    kioskWifiSsid = wifiSsid;
    kioskWifiPass = wifiPass;
#endif
    webPassword = req.adminPassword.c_str();
    prefs.begin(NVS_NAMESPACE, false);
    bool saved = prefs.putString(NVS_KEY_WIFI_SSID, wifiSsid) > 0 && prefs.putString(NVS_KEY_WIFI_PASS, wifiPass) > 0 &&
#if PISO_HAS_PHONES
                 prefs.putString(NVS_KEY_KIOSK_WIFI_SSID, kioskWifiSsid) > 0 &&
                 prefs.putString(NVS_KEY_KIOSK_WIFI_PASS, kioskWifiPass) > 0 &&
#endif
                 prefs.putString(NVS_KEY_ADMIN_PW, webPassword) > 0;
    if (saved) {
        // until the box gets an address with these details it may be given others (a mistyped password)
        wifiWaitFirstJoin = true;
        prefs.putBool(NVS_KEY_WIFI_WAIT, true);
        adminPwChanged = true;
        prefs.putBool(NVS_KEY_ADMIN_PW_CHANGED, true); // (last: until this is saved the box counts as not set up)
    }
    prefs.end();
    if (!saved) {
        Serial.println("PISO-SETUP-ERR could-not-save");
        return;
    }
    adminSessionsEndAll();
    Serial.println("PISO-SETUP-OK");
    Serial.flush();
    diagLog("[SETUP] Wi-Fi '%s' and the admin password were set over USB; restarting.\n", wifiSsid.c_str());
    delay(500);
    diagNoteRestartReason("usb-setup");
    ESP.restart();
}

#if defined(PISO_WORKSHOP_PILOT)
static void applyPilotWifi(const String& args) {
    const int separator = args.indexOf(' ');
    std::string name, password;
    if (separator < 0 || !provision::hexDecode(args.substring(0, separator).c_str(), name, 32) ||
        !provision::hexDecode(args.substring(separator + 1).c_str(), password, 63) ||
        name.find('\0') != std::string::npos || password.find('\0') != std::string::npos ||
        !setupgate::wifiSsidValid(name.c_str()) || !setupgate::wifiPasswordValid(password.c_str())) {
        Serial.println("PISO-PILOT-WIFI-ERR invalid-network");
        return;
    }
    if (!canPerformRebootOrOta()) {
        Serial.println("PISO-PILOT-WIFI-ERR busy");
        return;
    }
    if (!prefs.begin(NVS_NAMESPACE, false)) {
        Serial.println("PISO-PILOT-WIFI-ERR could-not-save");
        return;
    }
    const bool saved = prefs.putString(NVS_KEY_WIFI_SSID, name.c_str()) == name.size() &&
                       prefs.putString(NVS_KEY_WIFI_PASS, password.c_str()) == password.size() &&
                       prefs.putString(NVS_KEY_KIOSK_WIFI_SSID, name.c_str()) == name.size() &&
                       prefs.putString(NVS_KEY_KIOSK_WIFI_PASS, password.c_str()) == password.size();
    prefs.end();
    if (!saved) {
        Serial.println("PISO-PILOT-WIFI-ERR could-not-save");
        return;
    }
    Serial.println("PISO-PILOT-WIFI-OK");
    Serial.flush();
    delay(250);
    diagNoteRestartReason("pilot-usb-wifi");
    ESP.restart();
}
#endif

void processSerialCli() {
    if (!Serial.available()) return;
    String line = Serial.readStringUntil('\n');
    line.trim();
    if (line.length() == 0) return;

#if defined(PISO_WORKSHOP_PILOT)
    if (line == "PISO-PILOT-STATUS") {
        Serial.printf("PISO-PILOT state=%s slots=%d\n", pilotLicenseState(), pilotLicensedSlots());
        return;
    }
    constexpr const char wifiPrefix[] = "PISO-PILOT-WIFI ";
    if (line.startsWith(wifiPrefix)) {
        applyPilotWifi(line.substring(sizeof(wifiPrefix) - 1));
        return;
    }
    constexpr const char prefix[] = "PISO-PILOT-CREDENTIAL ";
    if (line.startsWith(prefix)) {
        bool saved = provisionPilotCredential(line.substring(sizeof(prefix) - 1));
        Serial.println(saved ? "PISO-PILOT-CREDENTIAL-OK" : "PISO-PILOT-CREDENTIAL-ERR");
        return;
    }
#endif
    if (line == "PISO-HELLO") {
        answerHello();
        return;
    }
    if (line.startsWith("PISO-SETUP ")) {
        applySetup(line.substring(11));
        return;
    }

#if PISO_HAS_PHONES
    if (line.startsWith("add ") || line.startsWith("add")) {
        int firstSpace = line.indexOf(' ');
        if (firstSpace != -1) {
            int minutes = line.substring(firstSpace + 1).toInt();
            sendAddTime(minutes, "ALL");
        }
    } else if (line.equalsIgnoreCase("help")) {
        Serial.println("\nCommands: add <minutes> | help");
    }
#else
    if (line.equalsIgnoreCase("help")) Serial.println("\nCommands: help");
#endif
}
