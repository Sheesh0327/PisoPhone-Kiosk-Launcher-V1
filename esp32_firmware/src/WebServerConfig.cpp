#include "WebServerConfig.h"
#include "WebServerModule.h"
#include "WebServerAuth.h"
#include "Config.h"
#include "Security.h"
#include "HardwareManager.h"
#include "DeviceManager.h"
#include "WebDashboardHtml.h"
#include "WebDashboardOta.h"
#include <WiFi.h>
#include <WebServer.h>
#include "esp_wifi.h"

bool otaUpdateSuccess = false;
bool otaFirstChunkReceived = false;
bool otaIsValidBinary = true;
String otaErrorMsg = "";

void handlePortalRoot() {
    if (!checkAuth()) return;
    if (macAddressStr.length() == 0) {
        uint8_t mac[6];
        esp_read_mac(mac, ESP_MAC_WIFI_STA);
        char macBuf[18];
        snprintf(macBuf, sizeof(macBuf), "%02X:%02X:%02X:%02X:%02X:%02X", mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
        macAddressStr = String(macBuf);
    }
    
    // Check for redirection-based pairing action from the HTTPS Installer
    if (webServer.hasArg("action") && webServer.arg("action") == "pair") {
        int slot = webServer.hasArg("slot") ? webServer.arg("slot").toInt() : 0;
        String id = webServer.hasArg("id") ? webServer.arg("id") : "";
        String ip = webServer.hasArg("ip") ? webServer.arg("ip") : "";
        String name = webServer.hasArg("name") ? webServer.arg("name") : ("PisoPhone " + String(slot));

        if (slot >= 1 && slot <= maxLicensedSlots && id.length() > 0) {
            bool res = pairDeviceToSlot(slot, id, ip, name);
            if (res) {
                sendCloudSnapshot();
                String successHtml = R"HTML(
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta charset="UTF-8">
                        <title>Pairing Success</title>
                        <style>
                            body { background: #0b0f19; color: #10b981; font-family: sans-serif; display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100vh; margin: 0; }
                            .card { background: #111827; padding: 32px; border-radius: 16px; border: 1px solid #10b981; box-shadow: 0 4px 20px rgba(16, 185, 129, 0.2); text-align: center; max-width: 400px; }
                            h1 { margin-top: 0; font-size: 24px; }
                            p { color: #9ca3af; font-size: 14px; margin-bottom: 20px; }
                            .spinner { border: 4px solid rgba(16, 185, 129, 0.1); border-top: 4px solid #10b981; border-radius: 50%; width: 36px; height: 36px; animation: spin 1s linear infinite; margin: 0 auto; }
                            @keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }
                        </style>
                        <script>
                            setTimeout(function() { window.location.href = '/'; }, 3000);
                        </script>
                    </head>
                    <body>
                        <div class="card">
                            <h1>🎉 Device Paired Successfully!</h1>
                            <p>Slot #_SLOT_ is now linked to your PisoPhone terminal.</p>
                            <p>Returning to your local Admin Console dashboard...</p>
                            <div class="spinner"></div>
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

void handleReboot() {
    if (!checkAuth()) return;
    Serial.println("\n[🔄 HTTP API] Reboot request received from Web Portal.");
    if (revenueDirty || totalCoinsLifetime != lastSavedTotalCoins || totalEarningsLifetime != lastSavedTotalEarnings) {
        prefs.begin("kiosk_cfg", false);
        prefs.putULong("total_coins", totalCoinsLifetime);
        prefs.putFloat("total_earnings", totalEarningsLifetime);
        prefs.end();
        lastSavedTotalCoins = totalCoinsLifetime;
        lastSavedTotalEarnings = totalEarningsLifetime;
        revenueDirty = false;
    }
    webServer.send(200, "text/plain", "REBOOTING");
    delay(500);
    ESP.restart();
}

void handleFactoryReset() {
    if (!checkAuth()) return;
    Serial.println("\n[⚠️ HTTP API] Factory reset request received from Web Portal.");
    factoryResetDefaults();
    webServer.send(200, "text/plain", "OK");
    delay(1000);
    ESP.restart();
}

void handleResetVault() {
    if (!checkAuth()) return;
    if (webServer.hasArg("reset_pw")) {
        String enteredPw = webServer.arg("reset_pw");
        if (enteredPw == webPassword) {
            totalCoinsLifetime = 0;
            totalCoinsSession = 0;
            totalEarningsLifetime = 0.0f;
            totalEarningsSession = 0.0f;
            lastSavedTotalCoins = 0;
            lastSavedTotalEarnings = 0.0f;
            prefs.begin("kiosk_cfg", false);
            prefs.putULong("total_coins", 0);
            prefs.putFloat("total_earnings", 0.0f);
            prefs.end();
            Serial.println("[💰 VAULT] Lifetime revenue counter reset to 0 by Admin.");
        } else {
            Serial.println("[⚠️ VAULT] Reset attempted with incorrect password.");
        }
    }
    redirectHome();
}

void handleSave() {
    if (!checkAuth()) return;

    // Immediately flush any dirty revenue to NVS flash on manual save
    if (revenueDirty || totalCoinsLifetime != lastSavedTotalCoins || totalEarningsLifetime != lastSavedTotalEarnings) {
        prefs.begin("kiosk_cfg", false);
        prefs.putULong("total_coins", totalCoinsLifetime);
        prefs.putFloat("total_earnings", totalEarningsLifetime);
        prefs.end();
        lastSavedTotalCoins = totalCoinsLifetime;
        lastSavedTotalEarnings = totalEarningsLifetime;
        revenueDirty = false;
        Serial.println("[💰 VAULT] Revenue counters flushed to NVS flash on config save.");
    }

    prefs.begin("kiosk_cfg", false);
    if (webServer.hasArg("wifi_ssid")) { wifiSsid = webServer.arg("wifi_ssid"); prefs.putString("wifi_ssid", wifiSsid); }
    if (webServer.hasArg("wifi_pass")) { wifiPass = webServer.arg("wifi_pass"); prefs.putString("wifi_pass", wifiPass); }
    if (webServer.hasArg("u_coin_pin")) { universalCoinPin = webServer.arg("u_coin_pin").toInt(); prefs.putInt("u_coin_pin", universalCoinPin); }
    if (webServer.hasArg("led_pin"))    { ledPin  = webServer.arg("led_pin").toInt();  prefs.putInt("led_pin", ledPin); }
    if (webServer.hasArg("led_active_low")) {
        ledActiveLow = (webServer.arg("led_active_low") == "1");
        prefs.putBool("led_active_low", ledActiveLow);
    }
    if (webServer.hasArg("relay_pin"))  { relayPin = webServer.arg("relay_pin").toInt(); prefs.putInt("relay_pin", relayPin); }
    if (webServer.hasArg("ips")) {
        String rawIps = webServer.arg("ips");
        rawIps.trim();
        String cleanIps = "";
        int startIdx = 0;
        while (startIdx < rawIps.length()) {
            int comma = rawIps.indexOf(',', startIdx);
            if (comma == -1) comma = rawIps.length();
            String entry = rawIps.substring(startIdx, comma);
            entry.trim();
            if (entry.length() > 0) {
                DeviceConfig cfg;
                if (parseDeviceEntry(entry, cfg)) {
                    if (cleanIps.length() > 0) cleanIps += ",";
                    cleanIps += cfg.id + "|" + cfg.ip + "|" + cfg.name;
                }
            }
            startIdx = comma + 1;
        }
        androidIps = cleanIps;
        prefs.putString("ips", androidIps);

        // Prune or clear in-memory telemetry tracking
        if (androidIps.length() == 0) {
            trackedDeviceCount = 0;
        } else {
            int newCount = 0;
            for (int i = 0; i < trackedDeviceCount; i++) {
                bool keep = false;
                int sIdx = 0;
                while (sIdx < androidIps.length()) {
                    int c = androidIps.indexOf(',', sIdx);
                    if (c == -1) c = androidIps.length();
                    String e = androidIps.substring(sIdx, c);
                    DeviceConfig cCfg;
                    if (parseDeviceEntry(e, cCfg)) {
                        if ((cCfg.id.length() > 0 && cCfg.id == trackedDevices[i].deviceId) || cCfg.ip == trackedDevices[i].lastKnownIp) {
                            keep = true;
                            break;
                        }
                    }
                    sIdx = c + 1;
                }
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
    if (webServer.hasArg("port"))       { targetPort = webServer.arg("port").toInt(); prefs.putInt("port", targetPort); }
    if (webServer.hasArg("admin_pw"))   { webPassword = webServer.arg("admin_pw"); prefs.putString("admin_pw", webPassword); }
    if (webServer.hasArg("price"))      { coinPrice = webServer.arg("price").toFloat(); prefs.putFloat("price", coinPrice); }
    if (webServer.hasArg("minutes"))    { minutesPerCoin = webServer.arg("minutes").toInt(); prefs.putInt("minutes", minutesPerCoin); }
    if (webServer.hasArg("debounce"))   { lockoutDebounceMs = webServer.arg("debounce").toInt(); prefs.putInt("debounce", lockoutDebounceMs); }
    if (webServer.hasArg("relay_active_low")) {
        relayActiveLow = (webServer.arg("relay_active_low") == "1" || webServer.arg("relay_active_low") == "true");
        prefs.putBool("relay_active_low", relayActiveLow);
    }
    if (webServer.hasArg("shared_secret")) {
        sharedSecret = webServer.arg("shared_secret");
        prefs.putString("shared_secret", sharedSecret);
    }
    prefs.end();

    // Dynamic Hardware Pin and Coin Slot reconfiguration
    applyCoinSlotHardwareConfig();
    setLedHardware(currentLedState == LED_STATE_CONNECTED);
    processRelayState();

    Serial.println("\n[+] Config updated and saved. Pushing live config to registered Android terminals...");

    // True Push Configuration to all registered Android terminals
    int startIdx = 0;
    while (startIdx < androidIps.length()) {
        int comma = androidIps.indexOf(',', startIdx);
        if (comma == -1) comma = androidIps.length();
        String entry = androidIps.substring(startIdx, comma);
        entry.trim();
        if (entry.length() > 0) {
            DeviceConfig cfg;
            if (parseDeviceEntry(entry, cfg)) {
                int slotIdx = findSlotIndexForDevice(cfg.id, cfg.ip);
                if (slotIdx >= 0 && cfg.ip.length() > 0 && cfg.ip != "127.0.0.1") {
                    String configParams = "price=" + String(coinPrice) + "&minutes=" + String(minutesPerCoin) + "&admin_pin=" + webPassword;
                    if (cfg.name.length() > 0) {
                        configParams += "&device_name=" + urlEncode(cfg.name);
                    }
                    sendAuthenticated(cfg.ip, targetPort, "/config", "/challenge", configParams, 1000);
                }
            }
        }
        startIdx = comma + 1;
    }

    webServer.send(200, "text/plain", "OK");
}

void handleOtaForm() {
    if (!checkAuth()) return;
    String html = FPSTR(OTA_FORM_HTML);
    if (macAddressStr.length() == 0) {
        uint8_t mac[6];
        esp_read_mac(mac, ESP_MAC_WIFI_STA);
        char macBuf[18];
        snprintf(macBuf, sizeof(macBuf), "%02X:%02X:%02X:%02X:%02X:%02X", mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
        macAddressStr = String(macBuf);
    }
    html.replace("{MAC_ADDRESS}", macAddressStr);
    webServer.send(200, "text/html; charset=utf-8", html);
}
