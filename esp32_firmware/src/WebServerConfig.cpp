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
#include "SetupGate.h"
#include "SuperAdminCreds.h"
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
    if (!checkAdminAuth()) return;
    if (macAddressStr.length() == 0) {
        uint8_t mac[6];
        esp_read_mac(mac, ESP_MAC_WIFI_STA);
        char macBuf[18];
        snprintf(macBuf, sizeof(macBuf), "%02X:%02X:%02X:%02X:%02X:%02X", mac[0], mac[1], mac[2], mac[3], mac[4],
                 mac[5]);
        macAddressStr = String(macBuf);
    }

    // Check for redirection-based pairing action from the HTTPS Installer
    if (webServer.hasArg("action") && webServer.arg("action") == "pair") {
        int slot = webServer.hasArg("slot") ? webServer.arg("slot").toInt() : 0;
        String id = webServer.hasArg("id") ? webServer.arg("id") : "";
        String ip = webServer.hasArg("ip") ? webServer.arg("ip") : "";
        String name = webServer.hasArg("name") ? cleanName(webServer.arg("name")) : ("PisoPhone " + String(slot));

        if (slot >= 1 && slot <= MAX_SUPPORTED_SLOTS && id.length() > 0) {
            bool res = pairDeviceToSlot(slot, id, ip, name);
            if (res) {
                sendCloudSnapshot();
                String successHtml = R"HTML(
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta name="color-scheme" content="light dark">
    <title>Phone paired</title>
    <link rel="stylesheet" href="/assets/portal.css">
    <script>setTimeout(function() { window.location.href = '/'; }, 3000);</script>
</head>
<body>
    <div class="center-page">
        <div class="card-narrow" style="text-align: center;">
            <h1>Phone paired</h1>
            <p class="muted">Slot _SLOT_ is now linked to your phone. Taking you back to the dashboard…</p>
            <div class="spinner"></div>
        </div>
    </div>
</body>
</html>
)HTML";
                successHtml.replace("_SLOT_", String(slot));
                webServer.send(200, "text/html", successHtml);
                return;
            }
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

void handleResetVault() {
    if (!checkAdminAuth()) return;
    if (webServer.hasArg("reset_pw")) {
        String enteredPw = webServer.arg("reset_pw");
        if (superAdminPasswordOk(enteredPw) || superAdminBasicAuthOk()) {
            totalCoinsLifetime = 0;
            totalCoinsSession = 0;
            totalCentavosLifetime = 0;
            totalCentavosSession = 0;
            lastSavedTotalCoins = 0;
            lastSavedTotalCentavos = 0;
            prefs.begin(NVS_NAMESPACE, false);
            prefs.putULong(NVS_KEY_TOTAL_COINS, 0);
            prefs.putULong(NVS_KEY_TOTAL_CENTAVOS, 0);
            prefs.end();
            Serial.println("[👑 VAULT] Lifetime revenue counter reset to 0 by Super Admin (Vendor).");
        } else {
            Serial.println("[⚠️ VAULT] Reset attempted without valid Super Admin credentials.");
        }
    }
    redirectHome();
}

void handleSave() {
    if (!checkAdminAuth()) return;

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
    if (webServer.hasArg(NVS_KEY_U_COIN_PIN)) {
        universalCoinPin = webServer.arg(NVS_KEY_U_COIN_PIN).toInt();
        prefs.putInt(NVS_KEY_U_COIN_PIN, universalCoinPin);
    }
    if (webServer.hasArg(NVS_KEY_LED_PIN)) {
        ledPin = webServer.arg(NVS_KEY_LED_PIN).toInt();
        prefs.putInt(NVS_KEY_LED_PIN, ledPin);
    }
    if (webServer.hasArg(NVS_KEY_LED_ACTIVE_LOW)) {
        ledActiveLow = (webServer.arg(NVS_KEY_LED_ACTIVE_LOW) == "1");
        prefs.putBool(NVS_KEY_LED_ACTIVE_LOW, ledActiveLow);
    }
    if (webServer.hasArg(NVS_KEY_RELAY_PIN)) {
        relayPin = webServer.arg(NVS_KEY_RELAY_PIN).toInt();
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
        targetPort = webServer.arg(NVS_KEY_PORT).toInt();
        prefs.putInt(NVS_KEY_PORT, targetPort);
    }
    if (webServer.hasArg(NVS_KEY_ADMIN_PW)) {
        String newPw = webServer.arg(NVS_KEY_ADMIN_PW);
        if (newPw != webPassword && !setupgate::passwordAcceptable(newPw.c_str())) {
            webServer.send(
                400, "application/json",
                "{\"success\":false,\"error\":\"WEAK_PASSWORD\",\"message\":\"Use at least 8 characters, and not the default password.\"}");
            return;
        }
        if (newPw != webPassword) {
            webPassword = newPw;
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
            String configParams = "admin_pin=" + webPassword + "&minutes=" + String(minutesPerCoin) + "&price=1.0";
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
