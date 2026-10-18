// Admin portal page and settings endpoints: render the dashboard, save configuration, reboot,
// factory reset, vault reset and the firmware update form.

#include "InputSafety.h"
#include "WebServerConfig.h"
#include "Diagnostics.h"
#include "SecretMode.h"
#include "WebAssetServer.h"
#include "WebServerModule.h"
#include "FirmwareVersion.h"
#include "WebServerAuth.h"
#include "PaymentQueueManager.h"
#include "Config.h"
#include "PhoneProfileStore.h"
#include "PulseGuard.h"
#include "PinRules.h"
#include "SetupGate.h"
#include "RevenueVault.h"
#include "RevenueLedger.h"
#include "Security.h"
#include "HardwareManager.h"
#include "DeviceManager.h"
#include "SuperAdminManager.h"
#include "WebDashboardHtml.h"
#include "WebDashboardIcons.h"
#include "WebDashboardOta.h"
#include <WiFi.h>
#include <WebServer.h>
#include "esp_wifi.h"

bool otaUpdateSuccess = false;
bool otaFirstChunkReceived = false;
bool otaIsValidBinary = true;
String otaErrorMsg = "";

void handlePortalRoot() {
    if (!checkAdminAuthOrLoginPage()) return;
    if (macAddressStr.length() == 0) {
        uint8_t mac[6];
        esp_read_mac(mac, ESP_MAC_WIFI_STA);
        char macBuf[18];
        snprintf(macBuf, sizeof(macBuf), "%02X:%02X:%02X:%02X:%02X:%02X", mac[0], mac[1], mac[2], mac[3], mac[4],
                 mac[5]);
        macAddressStr = String(macBuf);
    }

    // A phone's setup page sends the owner here (?action=pair&slot=&id=&name=) once it is installed. Opening a link must never
    // change anything by itself (another site could make the owner's browser open it): the page asks "Pair this phone?" and the
    // button does the pairing with a POST from this very page, which a cross-site page cannot make (WebServerAuth.cpp).
    if (webServer.hasArg("action") && webServer.arg("action") == "pair") {
        int slot = webServer.hasArg("slot") ? webServer.arg("slot").toInt() : 0;
        String id = webServer.hasArg("id") ? webServer.arg("id") : "";
        String ip = webServer.hasArg("ip") ? webServer.arg("ip") : "";
        String name = webServer.hasArg("name") ? cleanName(webServer.arg("name")) : ("PisoPhone " + String(slot));

        if (slot >= 1 && slot <= MAX_SUPPORTED_SLOTS && id.length() > 0) {
            // values go into a script as JSON strings ("<" is escaped so nothing can close the script)
            auto js = [](const String& v) {
                String q = "\"" + jsonEsc(v) + "\"";
                q.replace("<", "\\u003c");
                return q;
            };
            String page = R"HTML(<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta name="color-scheme" content="light dark">
    <title>Pair this phone?</title>
    <link rel="stylesheet" href="/assets/portal.css">
</head>
<body>
    <div class="center-page">
        <div class="card-narrow" style="text-align: center;">
            <h1 id="t">Pair this phone?</h1>
            <p class="muted" id="m">The phone that just finished its setup will become slot _SLOT_.</p>
            <p id="b"><button type="button" class="btn primary" id="ok">Pair phone</button> <a class="btn" href="/">Cancel</a></p>
        </div>
    </div>
    <script>
    var P = { slot: _SLOT_, id: _ID_, ip: _IP_, name: _NAME_ };
    document.getElementById('ok').onclick = function () {
        var q = 'slot=' + P.slot + '&id=' + encodeURIComponent(P.id) + '&ip=' + encodeURIComponent(P.ip) + '&name=' + encodeURIComponent(P.name);
        document.getElementById('ok').disabled = true;
        fetch('/api/slots/pair?' + q, { method: 'POST' })
            .then(function (r) { return r.json().catch(function () { return {}; }); })
            .then(function (j) {
                if (j && j.success) {
                    document.getElementById('t').textContent = 'Phone paired';
                    document.getElementById('m').textContent = 'Slot ' + P.slot + ' is now linked to the phone. Taking you back to the dashboard…';
                    document.getElementById('b').style.display = 'none';
                    setTimeout(function () { location.href = '/'; }, 2500);
                } else {
                    document.getElementById('t').textContent = 'Could not pair';
                    document.getElementById('m').textContent = (j && j.message) || 'The box did not accept the phone. Try again from the dashboard.';
                    document.getElementById('ok').style.display = 'none';
                }
            })
            .catch(function () {
                document.getElementById('t').textContent = 'Could not reach the box';
                document.getElementById('ok').disabled = false;
            });
    };
    </script>
</body>
</html>
)HTML";
            page.replace("_SLOT_", String(slot));
            page.replace("_ID_", js(id));
            page.replace("_IP_", js(ip));
            page.replace("_NAME_", js(name));
            webServer.sendHeader("Cache-Control", "no-store");
            webServer.send(200, "text/html", page);
            return;
        }
    }

    streamPortalHtml();
}

// Writes the revenue counters to flash if they changed since the last write.
static void flushRevenueToFlash() {
    if (revenueDirty || totalCoinsLifetime != lastSavedTotalCoins || totalCentavosLifetime != lastSavedTotalCentavos) {
        prefs.begin(NVS_NAMESPACE, false);
        prefs.putULong(NVS_KEY_TOTAL_COINS, totalCoinsLifetime);
        prefs.putULong(NVS_KEY_TOTAL_CENTAVOS, totalCentavosLifetime);
        prefs.end();
        lastSavedTotalCoins = totalCoinsLifetime;
        lastSavedTotalCentavos = totalCentavosLifetime;
        revenueDirty = false;
    }
}

void handleReboot() {
    if (!checkAdminAuth()) return;
    Serial.println("\n[🔄 HTTP API] Reboot request received from Web Portal.");
    if (!canPerformRebootOrOta()) {
        webServer.send(409, "text/plain", "BUSY: Unpersisted transactions in RAM");
        return;
    }
    flushRevenueToFlash();
    webServer.send(200, "text/plain", "REBOOTING");
    delay(500);
    diagNoteRestartReason("admin-reboot");
    ESP.restart();
}

void handleFactoryReset() {
    if (!checkAdminAuth()) return;
    Serial.println("\n[⚠️ HTTP API] Factory reset request received from Web Portal.");
    if (!canPerformRebootOrOta()) {
        webServer.send(409, "text/plain", "BUSY: Unpersisted transactions in RAM");
        return;
    }
    factoryResetDefaults();
    webServer.send(200, "text/plain", "OK");
    delay(1000);
    diagNoteRestartReason("factory-reset");
    ESP.restart();
}

// The 400 answer for a setting that cannot be saved. Nothing of the request has been written when it is sent.
static void refuseSave(const char* code, const char* message) {
    webServer.send(400, "application/json",
                   String("{\"success\":false,\"error\":\"") + code + "\",\"message\":\"" + message + "\"}");
}

// A port typed in the form: 1-65535, or -1.
static int parsePort(const String& text) {
    if (text.length() == 0 || text.length() > 5) return -1;
    for (size_t i = 0; i < text.length(); i++) {
        if (text[i] < '0' || text[i] > '9') return -1;
    }
    long port = text.toInt();
    return (port >= 1 && port <= 65535) ? (int)port : -1;
}

void handleSave() {
    if (!checkAdminAuth()) return;

    // Check everything that can be refused BEFORE anything is written, so a refused save changes nothing. A pin on the flash bus
    // used to be saved and applied at once, which crashed the box at this and every following start (only a USB flash fixed it).
    int newCoinPin = universalCoinPin, newRelayPin = relayPin, newLedPin = ledPin, newPort = targetPort;
    if (webServer.hasArg(NVS_KEY_U_COIN_PIN))
        newCoinPin = pinrules::parsePin(webServer.arg(NVS_KEY_U_COIN_PIN).c_str());
    if (webServer.hasArg(NVS_KEY_RELAY_PIN)) newRelayPin = pinrules::parsePin(webServer.arg(NVS_KEY_RELAY_PIN).c_str());
    if (webServer.hasArg(NVS_KEY_LED_PIN)) newLedPin = pinrules::parsePin(webServer.arg(NVS_KEY_LED_PIN).c_str());
    const char* pinProblem =
        pinrules::problem(pinrules::THIS_CHIP, newCoinPin, newRelayPin, newLedPin, HARDWARE_RESET_PIN);
    if (pinProblem) {
        refuseSave("BAD_PIN", pinProblem);
        return;
    }
    if (webServer.hasArg(NVS_KEY_PORT)) newPort = parsePort(webServer.arg(NVS_KEY_PORT));
    if (newPort < 0) {
        refuseSave("BAD_PORT", "The phone app port must be a number from 1 to 65535.");
        return;
    }
    if (webServer.hasArg(NVS_KEY_ADMIN_PW)) {
        String newPw = webServer.arg(NVS_KEY_ADMIN_PW);
        if (newPw != webPassword && !setupgate::passwordAcceptable(newPw.c_str())) {
            refuseSave("WEAK_PASSWORD", "Use at least 8 characters, and not the default password.");
            return;
        }
    }

    // Immediately flush any dirty revenue to NVS flash on manual save
    flushRevenueToFlash();

    prefs.begin(NVS_NAMESPACE, false);
    if (webServer.hasArg(NVS_KEY_WIFI_SSID)) {
        wifiSsid = webServer.arg(NVS_KEY_WIFI_SSID);
        prefs.putString(NVS_KEY_WIFI_SSID, wifiSsid);
    }
    if (webServer.hasArg(NVS_KEY_WIFI_PASS)) {
        wifiPass = webServer.arg(NVS_KEY_WIFI_PASS);
        prefs.putString(NVS_KEY_WIFI_PASS, wifiPass);
    }
    if (webServer.hasArg(NVS_KEY_KIOSK_WIFI_PASS)) {
        // the router's PisoKiosk password for the rental phones (empty clears it); an invalid one is ignored
        String kp = webServer.arg(NVS_KEY_KIOSK_WIFI_PASS);
        if (kp.length() == 0 || setupgate::kioskWifiPasswordValid(kp.c_str())) {
            kioskWifiPass = kp;
            prefs.putString(NVS_KEY_KIOSK_WIFI_PASS, kioskWifiPass);
        }
    }
    if (webServer.hasArg(NVS_KEY_KIOSK_WIFI_SSID)) {
        // this router's name for the phones' network (empty clears it, and the phones use the shared name); an invalid one is ignored
        String ks = webServer.arg(NVS_KEY_KIOSK_WIFI_SSID);
        if (ks.length() == 0 || setupgate::kioskWifiSsidValid(ks.c_str())) {
            kioskWifiSsid = ks;
            prefs.putString(NVS_KEY_KIOSK_WIFI_SSID, kioskWifiSsid);
        }
    }
    if (webServer.hasArg(NVS_KEY_U_COIN_PIN)) {
        universalCoinPin = newCoinPin;
        prefs.putInt(NVS_KEY_U_COIN_PIN, universalCoinPin);
    }
    if (webServer.hasArg(NVS_KEY_LED_PIN)) {
        ledPin = newLedPin;
        prefs.putInt(NVS_KEY_LED_PIN, ledPin);
    }
    if (webServer.hasArg(NVS_KEY_LED_ACTIVE_LOW)) {
        ledActiveLow = (webServer.arg(NVS_KEY_LED_ACTIVE_LOW) == "1");
        prefs.putBool(NVS_KEY_LED_ACTIVE_LOW, ledActiveLow);
    }
    if (webServer.hasArg(NVS_KEY_RELAY_PIN)) {
        relayPin = newRelayPin;
        prefs.putInt(NVS_KEY_RELAY_PIN, relayPin);
    }
    if (webServer.hasArg("ips")) {
        String rawIps = webServer.arg("ips");
        rawIps.trim();
        androidIps = rawIps;
        String cleanIps = "";
        forEachConfiguredDevice([&](const DeviceConfig& cfg) {
            if (cleanIps.length() > 0) cleanIps += ",";
            cleanIps += cfg.id + "|" + cfg.ip + "|" + cfg.name;
            return true;
        });
        androidIps = cleanIps;
        prefs.putString("ips", androidIps);

        // Prune or clear in-memory telemetry tracking
        if (androidIps.length() == 0) {
            trackedDeviceCount = 0;
        } else {
            int newCount = 0;
            for (int i = 0; i < trackedDeviceCount; i++) {
                bool keep = false;
                forEachConfiguredDevice([&](const DeviceConfig& cCfg) {
                    if ((cCfg.id.length() > 0 && cCfg.id == trackedDevices[i].deviceId) ||
                        cCfg.ip == trackedDevices[i].lastKnownIp) {
                        keep = true;
                        return false;
                    }
                    return true;
                });
                if (keep) {
                    if (newCount != i) {
                        trackedDevices[newCount] = trackedDevices[i];
                    }
                    newCount++;
                }
            }
            trackedDeviceCount = newCount;
        }
    }
    if (webServer.hasArg(NVS_KEY_PORT)) {
        targetPort = newPort;
        prefs.putInt(NVS_KEY_PORT, targetPort);
    }
    if (webServer.hasArg(NVS_KEY_ADMIN_PW)) {
        String newPw = webServer.arg(NVS_KEY_ADMIN_PW); // checked above
        if (newPw != webPassword) {
            webPassword = newPw;
            adminSessionsEndAll();
            adminPwChanged = true;
            prefs.putString(NVS_KEY_ADMIN_PW, webPassword);
            prefs.putBool(NVS_KEY_ADMIN_PW_CHANGED, true);
        }
    }
    if (webServer.hasArg("minutes_per_coin")) {
        int m = webServer.arg("minutes_per_coin").toInt();
        if (m >= 1) {
            minutesPerCoin = m;
            prefs.putInt(NVS_KEY_MINS_PER_COIN, minutesPerCoin);
        }
    }
    if (webServer.hasArg(NVS_KEY_RELAY_ACTIVE_LOW)) {
        relayActiveLow =
            (webServer.arg(NVS_KEY_RELAY_ACTIVE_LOW) == "1" || webServer.arg(NVS_KEY_RELAY_ACTIVE_LOW) == "true");
        prefs.putBool(NVS_KEY_RELAY_ACTIVE_LOW, relayActiveLow);
    }
    if (webServer.hasArg(NVS_KEY_PULSE_GUARD)) {
        coinPulseGuard = (webServer.arg(NVS_KEY_PULSE_GUARD) == "1" || webServer.arg(NVS_KEY_PULSE_GUARD) == "true");
        prefs.putBool(NVS_KEY_PULSE_GUARD, coinPulseGuard);
    }
    if (webServer.hasArg(NVS_KEY_PULSE_MIN_MS)) {
        coinPulseMinMs = pulseMinMsClamp(webServer.arg(NVS_KEY_PULSE_MIN_MS).toInt());
        prefs.putInt(NVS_KEY_PULSE_MIN_MS, coinPulseMinMs);
    }
    if (webServer.hasArg(NVS_KEY_SHARED_SECRET)) {
        String newSecret = webServer.arg(NVS_KEY_SHARED_SECRET);
        if (secretmode::validSecret(newSecret.c_str()) && newSecret != getLegacySharedSecret()) {
            setSharedSecret(newSecret);
            prefs.putString(NVS_KEY_SHARED_SECRET, newSecret);
        }
    }
    prefs.end();

    // Dynamic Hardware Pin and Coin Slot reconfiguration
    applyCoinSlotHardwareConfig();
    setLedHardware(currentLedState == LED_STATE_CONNECTED);
    processRelayState();

    Serial.println("\n[+] Config updated and saved. Pushing live config to registered Android terminals...");

    // True Push Configuration to all registered Android terminals
    forEachConfiguredDevice([&](const DeviceConfig& cfg) {
        int slotIdx = findSlotIndexForDevice(cfg.id, cfg.ip);
        if (slotIdx >= 0 && cfg.ip.length() > 0 && cfg.ip != "127.0.0.1") {
            String configParams = "minutes=" + String(minutesPerCoinForSlotIndex(slotIdx)) + "&price=1.0";
            if (secretmode::mayPushAdminPassword(isLegacyKeyMode(), webPassword.c_str()))
                configParams += "&admin_pin=" + webPassword;
            if (cfg.name.length() > 0) {
                configParams += "&device_name=" + urlEncode(cfg.name);
            }
            sendAuthenticated(cfg.ip, targetPort, "/config", "/challenge", configParams, 1000);
        }
        return true;
    });

    webServer.send(200, "text/plain", "OK");
}

void handleOtaForm() {
    if (!checkAdminAuth()) return;
    String html = FPSTR(OTA_FORM_HTML);
    if (macAddressStr.length() == 0) {
        uint8_t mac[6];
        esp_read_mac(mac, ESP_MAC_WIFI_STA);
        char macBuf[18];
        snprintf(macBuf, sizeof(macBuf), "%02X:%02X:%02X:%02X:%02X:%02X", mac[0], mac[1], mac[2], mac[3], mac[4],
                 mac[5]);
        macAddressStr = String(macBuf);
    }
    html.replace("{MAC_ADDRESS}", macAddressStr);
    html.replace("{FW_VERSION}", PISO_FW_VERSION);
    html.replace("{ASSET_V_OTA}", webAssetVersion("ota.js"));
    html.replace("{ASSET_V_PORTAL_CSS}", webAssetVersion("portal.css"));
    html.replace("{ASSET_V_PORTAL_CORE}", webAssetVersion("portal-core.js"));
    html.replace("{PORTAL_ICONS}", FPSTR(PORTAL_ICONS_HTML));
#if CONFIG_IDF_TARGET_ESP32C3
    html.replace("{CHIP_ID}", "esp32c3");
#else
    html.replace("{CHIP_ID}", "esp32");
#endif
    webServer.send(200, "text/html; charset=utf-8", html);
}
