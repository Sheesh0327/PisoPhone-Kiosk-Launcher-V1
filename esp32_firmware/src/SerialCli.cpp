// USB serial: first-time setup for the setup program (SerialProvision.h), and in the development builds a small command line for
// bring-up. The *-production-encrypted builds do not have the command line: its "add" command gives free time to every phone
// and asks for no password. The setup commands exist in every build and work only on a box that has not been set up yet: they are how
// the PisoPhone-only setup (no router) gives a new box the shop's Wi-Fi and its admin password over the USB cable.

#include "SerialCli.h"
#include "Config.h"
#include "DeviceNetwork.h"
#include "Diagnostics.h"
#include "FirmwareVersion.h"
#include "PaymentQueueManager.h"
#include "SerialProvision.h"
#include "WebServerAuth.h"
#include <Arduino.h>
#include <WiFi.h>

static void answerHello() {
    bool needed = !adminPwChanged;
    bool up = WiFi.status() == WL_CONNECTED;
    Serial.printf("PISO-BOX fw=%s mac=%s setup=%s wifi=%s ip=%s\n", PISO_FW_VERSION, macAddressStr.c_str(),
                  needed ? "needed" : "done", up ? "up" : "down", up ? WiFi.localIP().toString().c_str() : "-");
}

// Saves the shop's Wi-Fi and the admin password, then restarts so the box joins that Wi-Fi. Box and phones share one Wi-Fi.
static void applySetup(const String& rest) {
    if (adminPwChanged) {
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
    kioskWifiSsid = wifiSsid;
    kioskWifiPass = wifiPass;
    webPassword = req.adminPassword.c_str();
    prefs.begin(NVS_NAMESPACE, false);
    bool saved = prefs.putString(NVS_KEY_WIFI_SSID, wifiSsid) > 0 && prefs.putString(NVS_KEY_WIFI_PASS, wifiPass) > 0 &&
                 prefs.putString(NVS_KEY_KIOSK_WIFI_SSID, kioskWifiSsid) > 0 &&
                 prefs.putString(NVS_KEY_KIOSK_WIFI_PASS, kioskWifiPass) > 0 &&
                 prefs.putString(NVS_KEY_ADMIN_PW, webPassword) > 0;
    if (saved) {
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

#ifdef BUILD_ENV_PRODUCTION
    if (line.equalsIgnoreCase("help")) Serial.println("\nNo serial commands in this build.");
    return;
#else
    if (line.startsWith("add ") || line.startsWith("add")) {
        int firstSpace = line.indexOf(' ');
        if (firstSpace != -1) {
            int minutes = line.substring(firstSpace + 1).toInt();
            sendAddTime(minutes, "ALL");
        }
    } else if (line.equalsIgnoreCase("help")) {
        Serial.println("\nCommands: add <minutes> | help");
    }
#endif
}
