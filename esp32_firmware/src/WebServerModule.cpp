#include "WebServerModule.h"
#include "Config.h"
#include "Security.h"
#include "HardwareManager.h"
#include "DeviceManager.h"
#include "WebDashboardHtml.h"
#include <WiFi.h>
#include <WebServer.h>
#include <HTTPClient.h>
#include <WiFiUdp.h>
#include <ESPmDNS.h>
#include <Update.h>
#include "esp_wifi.h"

WebServer webServer(80);
WiFiServer wsServer(81);
WiFiClient wsClient;
bool isWsConnected = false;
WiFiUDP udpServer;
QueueHandle_t authQueue = NULL;

bool otaUpdateSuccess = false;
bool otaFirstChunkReceived = false;
bool otaIsValidBinary = true;
String otaErrorMsg = "";

void authWorkerTask(void *pvParameters) {
    AuthRequest req;
    while (true) {
        if (xQueueReceive(authQueue, &req, portMAX_DELAY) == pdTRUE) {
            if (WiFi.status() != WL_CONNECTED) {
                vTaskDelay(pdMS_TO_TICKS(100));
                continue;
            }

            WiFiClient client;
            HTTPClient http;
            http.setConnectTimeout(req.timeoutMs);
            http.setTimeout(req.timeoutMs);
            http.setReuse(false);

            String ip = String(req.ip);
            if (ip.length() == 0) continue;

            String actionUrl = "http://" + ip + ":" + String(req.port) + String(req.actionPath);
            
            String finalParams = String(req.params);
            if (finalParams.length() > 0) {
                finalParams += "&ts=" + String(millis());
            } else {
                finalParams = "ts=" + String(millis());
            }
            
            if (finalParams.indexOf("tx_id=") == -1 && finalParams.indexOf("nonce=") == -1) {
                String txId = String(millis()) + "-" + String(random(1000, 9999));
                finalParams += "&tx_id=" + txId;
            }

            String encryptedPayload = aes_encrypt(finalParams, sharedSecret);
            actionUrl += "?payload=" + encryptedPayload;

            if (http.begin(client, actionUrl)) {
                int code = http.GET();
                Serial.printf("[⚡ AUTH WORKER] GET %s -> Response %d\n", actionUrl.c_str(), code);
                http.end();
            }
            vTaskDelay(pdMS_TO_TICKS(40)); // Prevent socket/radio contention
        }
    }
}

bool checkAuth() {
    if (webServer.hasArg("device_id") && webServer.hasArg("ts") && webServer.hasArg("sig")) {
        String devId = webServer.arg("device_id");
        String tsStr = webServer.arg("ts");
        String sig = webServer.arg("sig");
        if (verifyTelemetryAuth(devId, tsStr, sig)) {
            return true;
        }
    }
    if (webServer.hasArg("challenge") && webServer.hasArg("sig")) {
        String challenge = webServer.arg("challenge");
        String sig = webServer.arg("sig");
        if (sig.equals(calculateHMAC(challenge, sharedSecret))) {
            return true;
        }
    }
    if (!webServer.authenticate("admin", webPassword.c_str())) {
        webServer.requestAuthentication(BASIC_AUTH, "HARDWARE Admin Login", "Unauthorized: Please enter admin credentials.");
        return false;
    }
    return true;
}

void redirectHome() {
    webServer.sendHeader("Location", "/");
    webServer.send(303);
}

void handleLogout() {
    webServer.requestAuthentication(BASIC_AUTH, "HARDWARE Admin Login", "Logged out");
    webServer.send(401, "text/html; charset=utf-8", R"HTML(
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Logged Out - HARDWARE Controller</title>
    <style>
        :root {
            --bg: #0f172a;
            --card-bg: #1e293b;
            --text-main: #f8fafc;
            --text-muted: #94a3b8;
            --border: #334155;
            --primary: #10b981;
            --primary-hover: #059669;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            background-color: var(--bg);
            color: var(--text-main);
            display: flex;
            align-items: center;
            justify-content: center;
            min-height: 100vh;
            padding: 16px;
        }
        .logout-card {
            background-color: var(--card-bg);
            border: 1px solid var(--border);
            border-radius: 16px;
            padding: 32px 24px;
            max-width: 400px;
            width: 100%;
            text-align: center;
            box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.5);
        }
        .icon { font-size: 44px; margin-bottom: 14px; }
        h2 { font-size: 20px; font-weight: 800; margin-bottom: 8px; color: var(--text-main); }
        p { font-size: 14px; color: var(--text-muted); line-height: 1.5; margin-bottom: 24px; }
        .login-btn {
            display: inline-flex;
            align-items: center;
            justify-content: center;
            gap: 8px;
            width: 100%;
            padding: 12px 18px;
            background-color: var(--primary);
            color: white;
            text-decoration: none;
            border-radius: 8px;
            font-size: 14px;
            font-weight: 600;
            transition: background-color 0.2s, transform 0.1s;
        }
        .login-btn:hover {
            background-color: var(--primary-hover);
            transform: translateY(-1px);
        }
    </style>
</head>
<body>
    <div class="logout-card">
        <div class="icon">🔒</div>
        <h2>Logged Out</h2>
        <p>You have successfully logged out of the HARDWARE Admin Console. Session credentials have been invalidated.</p>
        <a href="/" class="login-btn">🔑 Log In Again</a>
    </div>
</body>
</html>
)HTML");
}

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
                // Send standard response but redirect to base root "/" after 3 seconds to clear query parameters
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
    prefs.begin("kiosk_cfg", false);
    if (webServer.hasArg("wifi_ssid")) { wifiSsid = webServer.arg("wifi_ssid"); prefs.putString("wifi_ssid", wifiSsid); }
    if (webServer.hasArg("wifi_pass")) { wifiPass = webServer.arg("wifi_pass"); prefs.putString("wifi_pass", wifiPass); }
    if (webServer.hasArg("coin_pin"))   { coinPin = webServer.arg("coin_pin").toInt(); prefs.putInt("coin_pin", coinPin); }
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
    if (webServer.hasArg("relay_mode")) {
        relayMode = webServer.arg("relay_mode").toInt();
        prefs.putInt("relay_mode", relayMode);
    }
    if (webServer.hasArg("shared_secret")) {
        sharedSecret = webServer.arg("shared_secret");
        prefs.putString("shared_secret", sharedSecret);
    }
    prefs.end();

    // Dynamic GPIO Pin re-binding & ISR attachment
    pinMode(coinPin, INPUT_PULLUP);
    detachInterrupt(digitalPinToInterrupt(universalCoinPin));
    pinMode(universalCoinPin, INPUT_PULLUP);
    attachInterrupt(digitalPinToInterrupt(universalCoinPin), universalCoinIsr, FALLING);
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

void handleAddTime() {
    if (!checkAuth()) return;
    int minutes = 60;
    if (webServer.hasArg("add_minutes")) {
        minutes = webServer.arg("add_minutes").toInt();
    }
    if (webServer.hasArg("adjust_action") && webServer.arg("adjust_action") == "subtract") {
        minutes = -abs(minutes);
    }
    String targetIp = webServer.hasArg("target_ip") ? webServer.arg("target_ip") : "ALL";

    // Enforce Expiration Check (RULE 6: Single verification path)
    uint64_t currentMs = getCurrentMasterTimeMs();
    if (targetIp != "ALL") {
        // Specific target IP
        DeviceConfig targetCfg;
        targetCfg.ip = targetIp;
        int startIdx = 0;
        while (startIdx < androidIps.length()) {
            int comma = androidIps.indexOf(',', startIdx);
            if (comma == -1) comma = androidIps.length();
            String entry = androidIps.substring(startIdx, comma);
            entry.trim();
            if (entry.length() > 0) {
                DeviceConfig cfg;
                if (parseDeviceEntry(entry, cfg) && cfg.ip == targetIp) {
                    targetCfg = cfg;
                    break;
                }
            }
            startIdx = comma + 1;
        }

        int slotIdx = findSlotIndexForDevice(targetCfg.id, targetCfg.ip);
        int daysLeft = -1;
        int expStatus = getSlotExpirationStatus(slotIdx, currentMs, daysLeft);
        if (expStatus == 2) {
            Serial.printf("[-] handleAddTime blocked: Target device %s (Slot #%d) is EXPIRED!\n",
                targetIp.c_str(), (slotIdx >= 0) ? licenseSlots[slotIdx].slotNum : 0);
            quickTimeStatusMsg = "<div style='background:#fee2e2;color:#dc2626;padding:10px 14px;border-radius:8px;margin-bottom:12px;font-size:12px;font-weight:700;border:1px solid rgba(239,68,68,0.3);'>❌ Adjustment Blocked: Target device " + targetIp + " is EXPIRED! Add credits in the Master Credit Vault to pair device.</div>";
            redirectHome();
            return;
        }
    }

    sendAddTime(minutes, targetIp);
    quickTimeStatusMsg = "<div style='background:#e8f5e9;color:#2e7d32;padding:10px 14px;border-radius:8px;margin-bottom:12px;font-size:12px;font-weight:700;border:1px solid rgba(16,185,129,0.3);'>✅ Adjusted " + String(minutes > 0 ? "+" : "") + String(minutes) + "m for " + (targetIp == "ALL" ? "All Active Devices" : targetIp) + ".</div>";
    redirectHome();
}

void handleOneVsOne() {
    if (!checkAuth()) return;
    p1Ip = webServer.hasArg("p1_ip") ? webServer.arg("p1_ip") : "";
    p2Ip = webServer.hasArg("p2_ip") ? webServer.arg("p2_ip") : "";
    if (webServer.hasArg("match_minutes")) {
        matchMinutes = webServer.arg("match_minutes").toInt();
    }
    
    prefs.begin("kiosk_cfg", false);
    prefs.putString("p1", p1Ip);
    prefs.putString("p2", p2Ip);
    prefs.putInt("match", matchMinutes);
    prefs.end();

    String winner = webServer.hasArg("winner") ? webServer.arg("winner") : "";

    if (winner != "" && p1Ip != "" && p2Ip != "") {
        if (p1Ip == p2Ip) {
            matchStatusMsg = "<div style='background:#ffebee;color:#c62828;padding:8px 12px;border-radius:4px;margin-bottom:10px;font-size:13px;'>❌ <b>Match Blocked:</b> Player 1 and Player 2 cannot be the same device!</div>";
            redirectHome();
            return;
        }

        if (matchMinutes <= 0) {
            matchStatusMsg = "<div style='background:#ffebee;color:#c62828;padding:8px 12px;border-radius:4px;margin-bottom:10px;font-size:13px;'>❌ <b>Match Blocked:</b> Stake minutes must be at least 1 minute!</div>";
            redirectHome();
            return;
        }

        int reqStakeSeconds = matchMinutes * 60;
        String p1Err = "", p2Err = "";
        int p1Sec = getDeviceTimeRemainingSeconds(p1Ip, &p1Err);
        int p2Sec = getDeviceTimeRemainingSeconds(p2Ip, &p2Err);

        if (p1Sec < 0 || p2Sec < 0) {
            String detail = "";
            if (p1Sec < 0) detail += "<br>• <b>Player 1 (" + p1Ip + "):</b> " + p1Err;
            if (p2Sec < 0) detail += "<br>• <b>Player 2 (" + p2Ip + "):</b> " + p2Err;
            matchStatusMsg = "<div style='background:#ffebee;color:#c62828;padding:8px 12px;border-radius:4px;margin-bottom:10px;font-size:13px;'>❌ <b>Match Failed:</b> Unable to connect or verify time balance:" + detail + "</div>";
            redirectHome();
            return;
        }

        int p1Mins = p1Sec / 60;
        int p2Mins = p2Sec / 60;

        if (p1Sec < reqStakeSeconds || p2Sec < reqStakeSeconds) {
            matchStatusMsg = "<div style='background:#ffebee;color:#c62828;padding:8px 12px;border-radius:4px;margin-bottom:10px;font-size:13px;'>❌ <b>Stake Denied:</b> Both devices must have at least " + String(matchMinutes) + "m of active time.<br>• Player 1 (" + p1Ip + "): <b>" + String(p1Mins) + "m remaining</b><br>• Player 2 (" + p2Ip + "): <b>" + String(p2Mins) + "m remaining</b></div>";
            redirectHome();
            return;
        }

        // Both devices meet the stake requirements
        if (winner == "p1") {
            sendAddTime(matchMinutes, p1Ip);
            yield();
            sendAddTime(-matchMinutes, p2Ip);
            matchStatusMsg = "<div style='background:#e8f5e9;color:#2e7d32;padding:8px 12px;border-radius:4px;margin-bottom:10px;font-size:13px;'>🏆 <b>Player 1 Won:</b> Transferred +" + String(matchMinutes) + "m to Player 1 (" + p1Ip + ") and deducted -" + String(matchMinutes) + "m from Player 2 (" + p2Ip + ").</div>";
        } else if (winner == "p2") {
            sendAddTime(matchMinutes, p2Ip);
            yield();
            sendAddTime(-matchMinutes, p1Ip);
            matchStatusMsg = "<div style='background:#e8f5e9;color:#2e7d32;padding:8px 12px;border-radius:4px;margin-bottom:10px;font-size:13px;'>🏆 <b>Player 2 Won:</b> Transferred +" + String(matchMinutes) + "m to Player 2 (" + p2Ip + ") and deducted -" + String(matchMinutes) + "m from Player 1 (" + p1Ip + ").</div>";
        }
    }

    redirectHome();
}

void handleInsertCoin() {
    if (!checkAuth()) return;
    triggerCoinEvent();
    webServer.send(200, "text/plain", "OK");
}

void handleInsertUniversalCoin() {
    if (!checkAuth()) return;
    int pulses = webServer.hasArg("pulses") ? webServer.arg("pulses").toInt() : 1;
    if (pulses <= 0) pulses = 1;
    triggerUniversalCoinEvent(pulses);
    webServer.send(200, "text/plain", "OK");
}

void handleQueryTime() {
    if (!checkAuth()) return;
    if (!webServer.hasArg("ip")) {
        webServer.send(400, "application/json", "{\"success\":false,\"error\":\"Missing ip parameter\"}");
        return;
    }
    String targetIp = webServer.arg("ip");
    targetIp.trim();
    if (targetIp.length() == 0) {
        webServer.send(400, "application/json", "{\"success\":false,\"error\":\"Empty ip parameter\"}");
        return;
    }

    String queryErr = "";
    int seconds = getDeviceTimeRemainingSeconds(targetIp, &queryErr);
    if (seconds < 0) {
        if (queryErr.length() == 0) {
            char defaultErr[64];
            snprintf(defaultErr, sizeof(defaultErr), "Device offline or unreachable on port %d", targetPort);
            queryErr = defaultErr;
        }
        char errBuf[256];
        snprintf(errBuf, sizeof(errBuf), "{\"success\":false,\"ip\":\"%s\",\"error\":\"%s\"}", targetIp.c_str(), queryErr.c_str());
        webServer.send(200, "application/json", errBuf);
        return;
    }

    int mins = seconds / 60;
    int secs = seconds % 60;
    char jsonBuf[256];
    snprintf(jsonBuf, sizeof(jsonBuf),
        "{\"success\":true,\"ip\":\"%s\",\"seconds\":%d,\"minutes\":%d,\"formatted\":\"%dm %ds\"}",
        targetIp.c_str(), seconds, mins, mins, secs);
    webServer.send(200, "application/json", jsonBuf);
}


void handleApiSlots() {
    if (!checkAuth()) return;
    uint64_t currentMs = getCurrentMasterTimeMs();
    String json = "{\"maxSlots\":" + String(maxLicensedSlots) + ",\"mac\":\"" + macAddressStr + "\",\"slots\":[";
    for (int i = 0; i < maxLicensedSlots; i++) {
        if (i > 0) json += ",";
        char expBuf[24];
        snprintf(expBuf, sizeof(expBuf), "%llu", (unsigned long long)licenseSlots[i].expiresAt);
        int daysLeft = 0;
        int expStatus = getSlotExpirationStatus(i, currentMs, daysLeft);
        bool isExpiredOrInactive = (expStatus == 2);

        json += "{";
        json += "\"slotNum\":" + String(licenseSlots[i].slotNum) + ",";
        json += "\"deviceId\":\"" + licenseSlots[i].deviceId + "\",";
        json += "\"ip\":\"" + licenseSlots[i].ip + "\",";
        json += "\"name\":\"" + licenseSlots[i].name + "\",";
        json += "\"expiresAt\":" + String(expBuf) + ",";
        json += "\"active\":" + String(licenseSlots[i].active ? "true" : "false") + ",";
        json += "\"isBound\":" + String(licenseSlots[i].deviceId.length() > 0 ? "true" : "false") + ",";
        json += "\"expStatus\":" + String(expStatus) + ",";
        json += "\"isExpiredOrInactive\":" + String(isExpiredOrInactive ? "true" : "false");
        json += "}";
    }
    json += "]}";
    webServer.send(200, "application/json", json);
}

void handleApiSlotPair() {
    if (!checkAuth()) return;
    int slot = webServer.hasArg("slot") ? webServer.arg("slot").toInt() : 0;
    String id = webServer.hasArg("id") ? webServer.arg("id") : "";
    String ip = webServer.hasArg("ip") ? webServer.arg("ip") : "";
    String name = webServer.hasArg("name") ? webServer.arg("name") : "";

    if (slot < 1 || slot > maxLicensedSlots || id.length() == 0) {
        webServer.send(400, "application/json", "{\"success\":false,\"error\":\"Invalid slot or device ID\"}");
        return;
    }

    bool res = pairDeviceToSlot(slot, id, ip, name);
    if (res) {
        sendCloudSnapshot();
        webServer.send(200, "application/json", "{\"success\":true,\"slot\":" + String(slot) + "}");
    } else {
        webServer.send(500, "application/json", "{\"success\":false,\"error\":\"Failed to pair\"}");
    }
}

void handleApiSlotUnpair() {
    if (!checkAuth()) return;
    int slot = webServer.hasArg("slot") ? webServer.arg("slot").toInt() : 0;
    if (slot < 1 || slot > maxLicensedSlots) {
        webServer.send(400, "application/json", "{\"success\":false,\"error\":\"Invalid slot number\"}");
        return;
    }

    bool res = unpairSlot(slot);
    if (res) {
        sendCloudSnapshot();
        webServer.send(200, "application/json", "{\"success\":true,\"slot\":" + String(slot) + "}");
    } else {
        webServer.send(500, "application/json", "{\"success\":false,\"error\":\"Failed to unpair\"}");
    }
}

void handleApiSlotApplyToken() {
    if (!checkAuth()) return;
    String token = webServer.hasArg("token") ? webServer.arg("token") : "";
    if (token.length() == 0) {
        webServer.send(400, "application/json", "{\"success\":false,\"error\":\"Missing token\"}");
        return;
    }

    bool ok = applySlotToken(token);
    if (ok) {
        sendCloudSnapshot();
        webServer.send(200, "application/json", "{\"success\":true,\"maxSlots\":" + String(maxLicensedSlots) + "}");
    } else {
        webServer.send(403, "application/json", "{\"success\":false,\"error\":\"Invalid slot token or cryptographic signature mismatch\"}");
    }
}

void handleApiSlotCloudSync() {
    if (!checkAuth()) return;
    sendCloudSnapshot();
    webServer.send(200, "application/json", "{\"success\":true,\"message\":\"Cloud snapshot sent\"}");
}

// Emulate Payment API: Allows browser/website emulator to credit the ESP32 vault
void handleApiCreditsEmulatePayment() {
    webServer.sendHeader("Access-Control-Allow-Origin", "*");
    webServer.sendHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
    webServer.sendHeader("Access-Control-Allow-Headers", "Content-Type");
    if (webServer.method() == HTTP_OPTIONS) {
        webServer.send(204);
        return;
    }

    String type = webServer.hasArg("type") ? webServer.arg("type") : "month";
    int count = webServer.hasArg("count") ? webServer.arg("count").toInt() : 1;
    if (count < 1) count = 1;
    if (count > 100) count = 100;

    type.toLowerCase();
    type.trim();

    if (type == "month") {
        monthlyCredits += count;
    } else if (type == "year") {
        annualCredits += count;
    } else if (type == "test") {
        testCredits += count;
    } else {
        webServer.send(400, "application/json", "{\"success\":false,\"error\":\"Invalid type. Must be 'month', 'year', or 'test'\"}");
        return;
    }

    saveCreditVault();
    Serial.printf("[+] Emulate Payment: Added %d x %s credit(s). Vault: M=%d, Y=%d, T=%d\n",
        count, type.c_str(), monthlyCredits, annualCredits, testCredits);

    String json = "{\"success\":true,\"type\":\"" + type + "\",\"added\":" + String(count) + 
        ",\"monthly\":" + String(monthlyCredits) + 
        ",\"annual\":" + String(annualCredits) + 
        ",\"test\":" + String(testCredits) + 
        ",\"message\":\"Successfully credited " + String(count) + " " + type + " credit(s) to ESP32 vault.\"}";
    webServer.send(200, "application/json", json);
}

// Allocate Credit API: Consumes 1 credit from vault and updates slot expiration
void handleApiCreditsAllocate() {
    webServer.sendHeader("Access-Control-Allow-Origin", "*");
    webServer.sendHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
    webServer.sendHeader("Access-Control-Allow-Headers", "Content-Type");
    if (webServer.method() == HTTP_OPTIONS) {
        webServer.send(204);
        return;
    }

    int slot = webServer.hasArg("slot") ? webServer.arg("slot").toInt() : 0;
    String type = webServer.hasArg("type") ? webServer.arg("type") : "month";
    String err = "";

    if (allocateCreditToSlot(slot, type, err)) {
        int idx = slot - 1;
        char expBuf[24];
        snprintf(expBuf, sizeof(expBuf), "%llu", (unsigned long long)licenseSlots[idx].expiresAt);
        String json = "{\"success\":true,\"slot\":" + String(slot) + 
            ",\"type\":\"" + type + "\"" + 
            ",\"expiresAt\":" + String(expBuf) + 
            ",\"monthly\":" + String(monthlyCredits) + 
            ",\"annual\":" + String(annualCredits) + 
            ",\"test\":" + String(testCredits) + "}";
        webServer.send(200, "application/json", json);
    } else {
        webServer.send(400, "application/json", "{\"success\":false,\"error\":\"" + err + "\"}");
    }
}

// Status API: Inspect credit vault counts
void handleApiCreditsStatus() {
    webServer.sendHeader("Access-Control-Allow-Origin", "*");
    String json = "{\"monthly\":" + String(monthlyCredits) + 
        ",\"annual\":" + String(annualCredits) + 
        ",\"test\":" + String(testCredits) + 
        ",\"maxSlots\":" + String(maxLicensedSlots) + "}";
    webServer.send(200, "application/json", json);
}

void handleApiStatus() {
    if (!checkAuth()) return;

    int rssi = WiFi.RSSI();
    int quality = 0;
    bool isConnected = (WiFi.status() == WL_CONNECTED);
    if (isConnected) {
        if (rssi <= -100) quality = 0;
        else if (rssi >= -50) quality = 100;
        else quality = 2 * (rssi + 100);
    }
    
    String qualityStatus = "Disconnected";
    if (isConnected) {
        if (quality >= 75) qualityStatus = "Excellent";
        else if (quality >= 50) qualityStatus = "Good";
        else if (quality >= 25) qualityStatus = "Fair";
        else qualityStatus = "Weak";
    }

    String json = "{";
    json += "\"wifi\":{";
    json += "\"rssi\":" + String(rssi) + ",";
    json += "\"quality\":" + String(quality) + ",";
    json += "\"status\":\"" + qualityStatus + "\",";
    json += "\"ssid\":\"" + String(wifiSsid) + "\",";
    json += "\"ip\":\"" + WiFi.localIP().toString() + "\",";
    json += "\"connected\":" + String(isConnected ? "true" : "false");
    json += "},";
    json += "\"devices\":[";

    bool first = true;
    uint64_t currentMs = getCurrentMasterTimeMs();
    for (int i = 0; i < maxLicensedSlots; i++) {
        if (!first) json += ",";
        first = false;
        
        int sNum = licenseSlots[i].slotNum;
        String devId = licenseSlots[i].deviceId;
        String ip = licenseSlots[i].ip;
        String name = licenseSlots[i].name.length() > 0 ? licenseSlots[i].name : ("PisoPhone " + String(sNum));
        if (name == devId || name.startsWith("Terminal") || (devId.length() > 0 && name.indexOf(devId) != -1)) {
            name = "PisoPhone " + String(sNum);
        }
        bool isBound = (devId.length() > 0);
        
        int daysLeft = 0;
        int expStatus = getSlotExpirationStatus(i, currentMs, daysLeft);
        bool isExpiredOrInactive = (expStatus == 2);

        char expBuf[24];
        snprintf(expBuf, sizeof(expBuf), "%llu", (unsigned long long)licenseSlots[i].expiresAt);

        int rem = -1;
        int bat = 100;
        bool chg = false;
        bool online = false;
        
        if (isBound) {
            rem = getTrackedTimeRemaining(ip, 15000, devId);
            bat = getTrackedBatteryLevel(ip, devId);
            chg = getTrackedChargingState(ip, devId);
            online = (rem >= 0);
        }
        
        json += "{";
        json += "\"slotNum\":" + String(sNum) + ",";
        json += "\"isBound\":" + String(isBound ? "true" : "false") + ",";
        json += "\"id\":\"" + devId + "\",";
        json += "\"ip\":\"" + ip + "\",";
        json += "\"name\":\"" + name + "\",";
        json += "\"time\":" + String(rem) + ",";
        json += "\"online\":" + String(online ? "true" : "false") + ",";
        json += "\"battery\":" + String(bat) + ",";
        json += "\"charging\":" + String(chg ? "true" : "false") + ",";
        json += "\"active\":" + String(licenseSlots[i].active ? "true" : "false") + ",";
        json += "\"expiresAt\":" + String(expBuf) + ",";
        json += "\"expStatus\":" + String(expStatus) + ",";
        json += "\"isExpiredOrInactive\":" + String(isExpiredOrInactive ? "true" : "false");
        json += "}";
    }
    json += "],\"unassigned_devices\":[";
    bool firstUnassigned = true;
    unsigned long nowMs = millis();
    for (int i = 0; i < trackedDeviceCount; i++) {
        if (trackedDevices[i].deviceId.length() == 0) continue;
        if (findSlotIndexForDevice(trackedDevices[i].deviceId, trackedDevices[i].lastKnownIp) >= 0) continue;
        
        bool isOnline = (nowMs - trackedDevices[i].lastSeenMs < 15000);
        if (nowMs - trackedDevices[i].lastSeenMs > 300000) continue;
        
        if (!firstUnassigned) json += ",";
        firstUnassigned = false;
        
        String dName = getDeviceNameByIpOrId(trackedDevices[i].lastKnownIp, trackedDevices[i].deviceId);
        if (dName.length() == 0) dName = "PisoPhone Terminal";
        
        json += "{";
        json += "\"id\":\"" + trackedDevices[i].deviceId + "\",";
        json += "\"ip\":\"" + trackedDevices[i].lastKnownIp + "\",";
        json += "\"name\":\"" + dName + "\",";
        json += "\"battery\":" + String(trackedDevices[i].batteryLevel) + ",";
        json += "\"charging\":" + String(trackedDevices[i].isCharging ? "true" : "false") + ",";
        json += "\"online\":" + String(isOnline ? "true" : "false");
        json += "}";
    }
    json += "]}";
    webServer.send(200, "application/json", json);
}

void handleCheckQualification() {
    if (!checkAuth()) return;
    String p1 = webServer.hasArg("p1") ? webServer.arg("p1") : "";
    String p2 = webServer.hasArg("p2") ? webServer.arg("p2") : "";
    int mins = webServer.hasArg("minutes") ? webServer.arg("minutes").toInt() : 15;
    if (mins <= 0) mins = 1;

    p1.trim();
    p2.trim();

    if (p1.length() == 0 || p2.length() == 0) {
        webServer.send(400, "application/json", "{\"success\":false,\"error\":\"Missing player IP parameters\"}");
        return;
    }
    if (p1 == p2) {
        webServer.send(200, "application/json", "{\"success\":true,\"qualified\":false,\"error\":\"Player 1 and Player 2 cannot be the same device!\"}");
        return;
    }

    String p1Err = "", p2Err = "";
    int p1Sec = getDeviceTimeRemainingSeconds(p1, &p1Err);
    int p2Sec = getDeviceTimeRemainingSeconds(p2, &p2Err);

    int stakeSec = mins * 60;
    bool p1Ok = (p1Sec >= stakeSec);
    bool p2Ok = (p2Sec >= stakeSec);
    bool bothQualified = (p1Ok && p2Ok);

    int p1M = (p1Sec >= 0) ? (p1Sec / 60) : 0;
    int p1S = (p1Sec >= 0) ? (p1Sec % 60) : 0;
    int p2M = (p2Sec >= 0) ? (p2Sec / 60) : 0;
    int p2S = (p2Sec >= 0) ? (p2Sec % 60) : 0;

    char p1FmtBuf[32], p2FmtBuf[32];
    if (p1Sec >= 0) snprintf(p1FmtBuf, sizeof(p1FmtBuf), "%dm %ds", p1M, p1S);
    else strncpy(p1FmtBuf, "Offline", sizeof(p1FmtBuf));

    if (p2Sec >= 0) snprintf(p2FmtBuf, sizeof(p2FmtBuf), "%dm %ds", p2M, p2S);
    else strncpy(p2FmtBuf, "Offline", sizeof(p2FmtBuf));

    char msgBuf[128];
    if (bothQualified) {
        snprintf(msgBuf, sizeof(msgBuf), "Both devices meet the %dm stake requirement.", mins);
    } else if (p1Sec < 0 || p2Sec < 0) {
        strncpy(msgBuf, "One or both devices cannot be reached.", sizeof(msgBuf));
    } else {
        if (!p1Ok && !p2Ok) {
            snprintf(msgBuf, sizeof(msgBuf), "Both players need to add more time to meet the %dm stake.", mins);
        } else if (!p1Ok) {
            snprintf(msgBuf, sizeof(msgBuf), "Player 1 needs at least %dm more active time.", mins - p1M);
        } else {
            snprintf(msgBuf, sizeof(msgBuf), "Player 2 needs at least %dm more active time.", mins - p2M);
        }
    }

    char jsonBuf[512];
    snprintf(jsonBuf, sizeof(jsonBuf),
        "{\"success\":true,\"qualified\":%s,\"stake_minutes\":%d,"
        "\"p1_ip\":\"%s\",\"p1_seconds\":%d,\"p1_formatted\":\"%s\",\"p1_ok\":%s,\"p1_err\":\"%s\","
        "\"p2_ip\":\"%s\",\"p2_seconds\":%d,\"p2_formatted\":\"%s\",\"p2_ok\":%s,\"p2_err\":\"%s\","
        "\"message\":\"%s\"}",
        bothQualified ? "true" : "false", mins,
        p1.c_str(), p1Sec, p1FmtBuf, p1Ok ? "true" : "false", p1Err.c_str(),
        p2.c_str(), p2Sec, p2FmtBuf, p2Ok ? "true" : "false", p2Err.c_str(),
        msgBuf);

    webServer.send(200, "application/json", jsonBuf);
}

void handleIdentify() {
    String reqIp = webServer.hasArg("ip") ? webServer.arg("ip") : webServer.client().remoteIP().toString();
    String devName = getDeviceNameByIpOrId(reqIp, "");
    int slotIdx = findSlotIndexForDevice("", reqIp);
    String json = "{\"device\":\"HARDWARE_kiosk\",\"mac\":\"" + macAddressStr + "\",\"version\":\"3.0\"";
    if (slotIdx >= 0) {
        json += ",\"price\":" + String(coinPrice) + ",\"minutes\":" + String(minutesPerCoin);
    }
    if (devName.length() > 0) {
        json += ",\"device_name\":\"" + devName + "\"";
    }
    json += "}";
    webServer.send(200, "application/json", json);
}

void handleHeartbeat() {
    if (webServer.hasArg("device_id") && webServer.hasArg("ts") && webServer.hasArg("sig")) {
        String deviceId = webServer.arg("device_id");
        String reqIp = webServer.hasArg("ip") ? webServer.arg("ip") : "";
        if (reqIp.length() == 0 || reqIp == "127.0.0.1") reqIp = webServer.client().remoteIP().toString();
        String tsStr = webServer.arg("ts");
        String sig = webServer.arg("sig");
        
        if (verifyTelemetryAuth(deviceId, tsStr, sig)) {
            unsigned long long ts = strtoull(tsStr.c_str(), NULL, 10);
            updateMasterTime(ts);
            int timeRem = webServer.hasArg("time") ? webServer.arg("time").toInt() : 0;
            int state = webServer.hasArg("state") ? webServer.arg("state").toInt() : 0;
            int battery = webServer.hasArg("battery") ? webServer.arg("battery").toInt() : 100;
            bool charging = webServer.hasArg("charging") ? (webServer.arg("charging").toInt() == 1 || webServer.arg("charging") == "true") : false;

            updateDeviceTelemetry(deviceId, reqIp, timeRem, state, battery, charging, ts);

            String devName = getDeviceNameByIpOrId(reqIp, deviceId);

            int slotIdx = findSlotIndexForDevice(deviceId, reqIp);
            int daysLeft = -1;
            int expStatus = getSlotExpirationStatus(slotIdx, ts, daysLeft);

            String status = (expStatus == 2) ? "slot_expired" : "ok";
            String json = "{\"status\":\"" + status + "\",\"device\":\"HARDWARE_kiosk\",\"mac\":\"" + macAddressStr + "\"";
            if (slotIdx >= 0) {
                String encPin = aes_encrypt("PIN:" + webPassword, sharedSecret);
                json += ",\"price\":" + String(coinPrice) + ",\"minutes\":" + String(minutesPerCoin) + ",\"admin_pin\":\"" + encPin + "\"";
            }
            if (devName.length() > 0) {
                json += ",\"device_name\":\"" + devName + "\"";
            }
            if (slotIdx >= 0) {
                json += ",\"slot_num\":" + String(licenseSlots[slotIdx].slotNum);
                char expBuf[24];
                snprintf(expBuf, sizeof(expBuf), "%llu", (unsigned long long)licenseSlots[slotIdx].expiresAt);
                json += ",\"expires_at\":" + String(expBuf);
            }
            if (slotIdx < 0) {
                // UNASSIGNED DEVICE: Connected to ESP32, awaiting operator confirmation/slot assignment
                json += ",\"slot_num\":0,\"is_paired\":false,\"slot_expired\":true,\"slot_status\":\"unassigned\",\"slot_warning\":false";
                json += ",\"message\":\"Connected to ESP32: Awaiting Slot Assignment in Admin Portal.\"";
            } else if (expStatus == 2) {
                // HARD LOCKDOWN: Slot is expired or uncredited on ESP32
                json += ",\"is_paired\":true,\"slot_expired\":true,\"slot_status\":\"expired\",\"slot_warning\":false";
                json += ",\"message\":\"Device Expired: Please add credits to pair device to ESP32.\"";
            } else if (expStatus == 1) {
                // WARNING: Slot nearing expiration
                json += ",\"is_paired\":true,\"slot_expired\":false,\"slot_status\":\"warning\",\"slot_warning\":true";
                json += ",\"slot_warning_days_left\":" + String(daysLeft);
                if (daysLeft == 0) {
                    json += ",\"warning_message\":\"Device slot expiring soon (< 24 hours). Add credits to extend.\"";
                } else {
                    json += ",\"warning_message\":\"Device slot expires in " + String(daysLeft) + " day(s). Add credits to extend.\"";
                }
            } else {
                json += ",\"is_paired\":true,\"slot_expired\":false,\"slot_status\":\"active\",\"slot_warning\":false";
            }
            json += "}";
            webServer.send(200, "application/json", json);
            return;
        } else {
            webServer.send(403, "application/json", "{\"error\":\"Forbidden\"}");
            return;
        }
    }
    webServer.send(400, "application/json", "{\"error\":\"Missing auth params\"}");
}

void handlePing() {
    handleHeartbeat();
}

void handleActivate() {
    if (webServer.hasArg("code")) {
        String code = webServer.arg("code");
        code.toUpperCase();
        
        String expected = generateActivationCode(macAddressStr);
        if (code == expected) {
            is_licensed = true;
            prefs.begin("kiosk_cfg", false);
            prefs.putBool("licensed", true);
            prefs.end();
            webServer.send(200, "application/json", "{\"success\":true}");
            return;
        }
    }
    webServer.send(403, "application/json", "{\"success\":false,\"error\":\"Invalid activation code\"}");
}

void handleGetConfig() {
    String json = "{\"device\":\"HARDWARE_kiosk\",\"price\":" + String(coinPrice) + ",\"minutes\":" + String(minutesPerCoin) + ",\"relay_pin\":" + String(relayPin) + "}";
    webServer.send(200, "application/json", json);
}

void handleAnnounce() {
    handleHeartbeat();
}

void handleCrashReport() {
    String body = webServer.arg("plain");
    Serial.printf("\n[⚠️ CRASH REPORT FROM CLIENT]\n%s\n", body.c_str());
    webServer.send(200, "text/plain", "OK");
}

void handleOtaForm() {
    if (!checkAuth()) return;
    String html = R"HTML(
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>HARDWARE Firmware Upgrade</title>
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap" rel="stylesheet">
    <style>
        :root {
            --bg: #f8fafc;
            --sub-bg: #ffffff;
            --text-main: #0f172a;
            --text-muted: #64748b;
            --primary: #4f46e5;
            --primary-hover: #4338ca;
            --border: #e2e8f0;
            --card-shadow: 0 4px 6px -1px rgba(0,0,0,0.05), 0 2px 4px -1px rgba(0,0,0,0.03);
            --input-bg: #fafafa;
        }
        [data-theme="dark"] {
            --bg: #0b1120;
            --sub-bg: #1e293b;
            --text-main: #f8fafc;
            --text-muted: #94a3b8;
            --primary: #6366f1;
            --primary-hover: #4f46e5;
            --border: #334155;
            --card-shadow: 0 4px 6px -1px rgba(0,0,0,0.3);
            --input-bg: #0f172a;
        }
        * { box-sizing: border-box; }
        body { font-family: 'Inter', sans-serif; background: var(--bg); margin: 20px; color: var(--text-main); transition: background-color 0.2s, color 0.2s; }
        .container { background: var(--sub-bg); padding: 28px; border-radius: 16px; max-width: 460px; margin: 20px auto; box-shadow: var(--card-shadow); text-align: center; border: 1px solid var(--border); }
        .top-bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
        h2 { margin: 0; color: var(--text-main); font-size: 20px; font-weight: 700; }
        .status-box { margin: 15px 0; padding: 12px; border-radius: 8px; font-size: 14px; display: none; line-height: 1.4; text-align: left; }
        .error { background: #ffebee; border: 1px solid #ffcdd2; color: #c62828; }
        .success { background: #e8f5e9; border: 1px solid #c8e6c9; color: #1b5e20; }
        .info { background: #e3f2fd; border: 1px solid #bbdefb; color: #0d47a1; }
        [data-theme="dark"] .error { background: #450a0a; border-color: #7f1d1d; color: #fca5a5; }
        [data-theme="dark"] .success { background: #052e16; border-color: #14532d; color: #86efac; }
        [data-theme="dark"] .info { background: #082f49; border-color: #075985; color: #7dd3fc; }
        .progress-container { width: 100%; background: var(--border); border-radius: 10px; height: 18px; margin: 15px 0; overflow: hidden; display: none; }
        .progress-bar { height: 100%; width: 0%; background: #10b981; transition: width 0.1s ease-in-out; }
        input[type=file] { margin: 15px 0; padding: 12px; width: 100%; box-sizing: border-box; border: 1px solid var(--border); border-radius: 8px; background: var(--input-bg); color: var(--text-main); cursor: pointer; }
        button { padding: 12px 20px; background: var(--primary); color: white; border: none; border-radius: 8px; font-weight: 600; width: 100%; cursor: pointer; font-size: 15px; transition: background 0.2s; }
        button:hover { background: var(--primary-hover); }
        button:disabled { background: #64748b; cursor: not-allowed; opacity: 0.6; }
        .theme-btn { background: transparent; border: 1px solid var(--border); color: var(--text-main); padding: 4px 10px; border-radius: 6px; font-size: 12px; cursor: pointer; width: auto; font-weight: 500; }
        .theme-btn:hover { background: var(--border); }
        a { display: inline-block; margin-top: 18px; color: var(--primary); text-decoration: none; font-size: 13px; font-weight: 600; }
        a:hover { text-decoration: underline; }
    </style>
</head>
<body>
    <div class="container">
        <div class="top-bar">
            <h2>📲 Firmware OTA Update</h2>
            <button type="button" class="theme-btn" id="theme_toggle_btn" onclick="toggleTheme()">🌙 Dark</button>
        </div>
        <!-- MAC display removed -->
        <p style="font-size:13px; color:var(--text-muted); margin-bottom: 16px; line-height: 1.5;">
            Update your Kiosk controller wirelessly directly from the official website server or upload a local compiled binary.
        </p>

        <!-- Cloud Server One-Click OTA Upgrade Card -->
        <div style="background: var(--input-bg); border: 1px solid var(--border); border-radius: 12px; padding: 16px; margin-bottom: 20px;">
            <div style="display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px;">
                <h3 style="font-size: 15px; margin: 0;">🌐 Cloud Server Update</h3>
                <span id="cloud_ver_badge" style="font-size: 11px; font-weight: 700; background: rgba(59,130,246,0.15); color: #3b82f6; padding: 3px 8px; border-radius: 12px;">Checking server...</span>
            </div>
            <p style="font-size: 12px; color: var(--text-muted); margin: 0 0 12px 0;">
                Directly download and flash <code>firmware.bin</code> from the same web directory hosting the APK (<code>https://pisophone.pages.dev/firmware.bin</code>).
            </p>
            <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 8px;">
                <button type="button" class="theme-btn" style="width:100%; border-color: var(--primary); color: var(--primary);" onclick="checkCloudUpdate()">🔍 Check Version</button>
                <button type="button" id="cloud_update_btn" style="background: #10b981;" onclick="installCloudFirmware()">⚡ Install Cloud Update</button>
            </div>
        </div>

        <p style="font-size:12px; color:var(--text-muted); margin-bottom: 8px; font-weight:600;">
            Or manually select a local .bin firmware file:
        </p>
        <input type="file" id="file_input" accept=".bin">
        
        <div class="progress-container" id="progress_wrapper">
            <div class="progress-bar" id="progress_bar"></div>
        </div>
        
        <div class="status-box" id="status_message"></div>
        
        <button type="button" id="upload_button" onclick="startUpdate()">Upload & Flash Firmware</button>
        <br>
        <a href="/">&larr; Back to Kiosk Dashboard</a>
    </div>

    <script>
    (function() {
        const savedTheme = localStorage.getItem('kiosk_theme') || 'light';
        document.documentElement.setAttribute('data-theme', savedTheme);
    })();

    window.toggleTheme = function() {
        const cur = document.documentElement.getAttribute('data-theme') || 'light';
        const next = cur === 'dark' ? 'light' : 'dark';
        document.documentElement.setAttribute('data-theme', next);
        localStorage.setItem('kiosk_theme', next);
        updateThemeButton();
    };

    function updateThemeButton() {
        const btn = document.getElementById('theme_toggle_btn');
        if (btn) {
            const cur = document.documentElement.getAttribute('data-theme') || 'light';
            btn.innerHTML = cur === 'dark' ? '☀️ Light' : '🌙 Dark';
        }
    }
    document.addEventListener('DOMContentLoaded', updateThemeButton);

    window.startUpdate = function() {
        const fileInput = document.getElementById('file_input');
        const uploadBtn = document.getElementById('upload_button');
        const progressWrapper = document.getElementById('progress_wrapper');
        const progressBar = document.getElementById('progress_bar');
        const statusBox = document.getElementById('status_message');
        
        if (!fileInput.files || fileInput.files.length === 0) {
            showStatus('Please choose a .bin file to upload.', 'error');
            return;
        }
        
        const file = fileInput.files[0];
        
        // Prepare form data
        const formData = new FormData();
        formData.append('update', file, file.name);
        
        // Disable UI during flashing
        fileInput.disabled = true;
        uploadBtn.disabled = true;
        progressWrapper.style.display = 'block';
        progressBar.style.width = '0%';
        progressBar.style.background = '#10b981';
        
        showStatus('Uploading firmware binary (' + (file.size/1024).toFixed(1) + ' KB)...', 'info');
        
        const xhr = new XMLHttpRequest();
        xhr.open('POST', '/update', true);
        
        // Track Upload Progress
        xhr.upload.addEventListener('progress', function(e) {
            if (e.lengthComputable) {
                const percent = (e.loaded / e.total) * 100;
                progressBar.style.width = percent + '%';
                showStatus('Uploading: ' + Math.round(percent) + '% (' + (e.loaded/1024).toFixed(0) + ' KB / ' + (e.total/1024).toFixed(0) + ' KB)...', 'info');
                if (percent >= 99) {
                    showStatus('Flashing binary to HARDWARE partition... Please do not power off.', 'info');
                }
            }
        });
        
        // Handle response
        xhr.onload = function() {
            if (xhr.status === 200) {
                progressBar.style.width = '100%';
                progressBar.style.background = '#10b981';
                showStatus('<b>✅ SUCCESS: Firmware Updated!</b><br>Rebooting HARDWARE Controller now... returning to dashboard in 5 seconds.', 'success');
                setTimeout(function() {
                    window.location.href = '/';
                }, 5000);
            } else {
                progressBar.style.background = '#ef4444';
                showStatus('<b>❌ FAILED:</b> ' + (xhr.responseText || 'Flash error occurred'), 'error');
                fileInput.disabled = false;
                uploadBtn.disabled = false;
            }
        };
        
        xhr.onerror = function() {
            progressBar.style.background = '#ef4444';
            showStatus('<b>❌ Connection Error:</b> Network disconnected or connection was lost during upload.', 'error');
            fileInput.disabled = false;
            uploadBtn.disabled = false;
        };
        
        xhr.send(formData);
    }
    
    window.showStatus = function(text, type) {
        const box = document.getElementById('status_message');
        box.style.display = 'block';
        box.className = 'status-box ' + type;
        box.innerHTML = text;
    };

    window.checkCloudUpdate = async function() {
        const badge = document.getElementById('cloud_ver_badge');
        if (badge) badge.textContent = 'Checking...';
        try {
            const res = await fetch('https://pisophone.pages.dev/firmware.json', { cache: 'no-store' });
            if (res.ok) {
                const data = await res.json();
                if (badge) badge.textContent = 'Server v' + (data.version || '3.0.0');
                showStatus('<b>🎉 Server Firmware Available:</b> v' + (data.version || '3.0.0') + '<br>' + (data.changelog || 'Latest build ready to install.'), 'info');
            } else {
                if (badge) badge.textContent = 'Server Ready';
                showStatus('<b>Server Connected:</b> Firmware endpoint ready at https://pisophone.pages.dev/firmware.bin', 'info');
            }
        } catch(e) {
            if (badge) badge.textContent = 'Server Ready';
            showStatus('<b>Server Update Endpoint:</b> Ready to download directly from https://pisophone.pages.dev/firmware.bin', 'info');
        }
    };

    window.installCloudFirmware = async function() {
        const uploadBtn = document.getElementById('upload_button');
        const cloudBtn = document.getElementById('cloud_update_btn');
        const progressWrapper = document.getElementById('progress_wrapper');
        const progressBar = document.getElementById('progress_bar');
        
        if (!confirm('Download and flash the latest ESP32 firmware directly from https://pisophone.pages.dev/firmware.bin?')) return;
        
        if (cloudBtn) cloudBtn.disabled = true;
        if (uploadBtn) uploadBtn.disabled = true;
        
        progressWrapper.style.display = 'block';
        progressBar.style.width = '0%';
        progressBar.style.background = '#3b82f6';
        
        showStatus('📥 Downloading latest firmware.bin from server (https://pisophone.pages.dev/firmware.bin)...', 'info');
        
        try {
            const fwRes = await fetch('https://pisophone.pages.dev/firmware.bin', { cache: 'no-store' });
            if (!fwRes.ok) {
                throw new Error('Server returned HTTP ' + fwRes.status + ' when downloading firmware.bin');
            }
            const fwBlob = await fwRes.blob();
            
            showStatus('⚡ Download complete (' + (fwBlob.size/1024).toFixed(1) + ' KB). Preparing to flash HARDWARE partition...', 'info');
            
            const formData = new FormData();
            formData.append('update', fwBlob, 'firmware.bin');
            
            const xhr = new XMLHttpRequest();
            xhr.open('POST', '/update', true);
            
            xhr.upload.addEventListener('progress', function(e) {
                if (e.lengthComputable) {
                    const percent = (e.loaded / e.total) * 100;
                    progressBar.style.width = percent + '%';
                    showStatus('Flashing: ' + Math.round(percent) + '% (' + (e.loaded/1024).toFixed(0) + ' KB / ' + (e.total/1024).toFixed(0) + ' KB)...', 'info');
                    if (percent >= 99) {
                        showStatus('Flashing binary to HARDWARE partition... Please do not power off.', 'info');
                    }
                }
            });
            
            xhr.onload = function() {
                if (xhr.status === 200) {
                    progressBar.style.width = '100%';
                    progressBar.style.background = '#10b981';
                    showStatus('<b>✅ SUCCESS: Firmware Updated via Server!</b><br>Rebooting HARDWARE Controller now... returning to dashboard in 5 seconds.', 'success');
                    setTimeout(function() { window.location.href = '/'; }, 5000);
                } else {
                    progressBar.style.background = '#ef4444';
                    showStatus('<b>❌ Flash Error:</b> ' + (xhr.responseText || 'Error flashing downloaded binary'), 'error');
                    if (cloudBtn) cloudBtn.disabled = false;
                    if (uploadBtn) uploadBtn.disabled = false;
                }
            };
            
            xhr.onerror = function() {
                progressBar.style.background = '#ef4444';
                showStatus('<b>❌ Connection Error during upload to ESP32 controller.</b>', 'error');
                if (cloudBtn) cloudBtn.disabled = false;
                if (uploadBtn) uploadBtn.disabled = false;
            };
            
            xhr.send(formData);
        } catch(err) {
            progressBar.style.background = '#ef4444';
            showStatus('<b>❌ Server Fetch Failed:</b> ' + err.message, 'error');
            if (cloudBtn) cloudBtn.disabled = false;
            if (uploadBtn) uploadBtn.disabled = false;
        }
    };
    document.addEventListener('DOMContentLoaded', function() { checkCloudUpdate(); });
    </script>
</body>
</html>
)HTML";
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


// ============================================================================
// PORT 81 WEBSOCKET CONNECTION & HANDSHAKE SUPERVISOR
// ============================================================================

String extractUrlParam(String url, String param) {
    int start = url.indexOf(param);
    if (start == -1) return "";
    start += param.length();
    int end = url.indexOf('&', start);
    if (end == -1) {
        end = url.indexOf(' ', start);
    }
    if (end == -1) {
        end = url.length();
    }
    return url.substring(start, end);
}

void sendWsText(WiFiClient& client, String text) {
    if (!client.connected()) return;
    size_t len = text.length();
    uint8_t header[10];
    size_t headerLen = 0;

    header[0] = 0x81; // FIN + text frame
    if (len <= 125) {
        header[1] = (uint8_t)len;
        headerLen = 2;
    } else if (len <= 65535) {
        header[1] = 126;
        header[2] = (uint8_t)((len >> 8) & 0xFF);
        header[3] = (uint8_t)(len & 0xFF);
        headerLen = 4;
    } else {
        header[1] = 127;
        for (int i = 0; i < 8; i++) {
            header[2 + i] = (uint8_t)((len >> ((7 - i) * 8)) & 0xFF);
        }
        headerLen = 10;
    }

    client.write(header, headerLen);
    client.write((const uint8_t*)text.c_str(), len);
    client.flush();
}

String readWsText(WiFiClient& client) {
    if (!client.available()) return "";
    int b0 = client.read();
    if (b0 < 0) return "";
    int opcode = b0 & 0x0F;
    if (opcode == 0x08) { // Connection Close
        return "CLOSE";
    }

    if (!client.available()) return "";
    int b1 = client.read();
    if (b1 < 0) return "";
    bool isMasked = (b1 & 0x80) != 0;
    uint64_t payloadLen = (b1 & 0x7F);

    if (payloadLen == 126) {
        if (client.available() < 2) return "";
        payloadLen = (client.read() << 8) | client.read();
    } else if (payloadLen == 127) {
        if (client.available() < 8) return "";
        payloadLen = 0;
        for (int i = 0; i < 8; i++) {
            payloadLen = (payloadLen << 8) | client.read();
        }
    }

    uint8_t mask[4] = {0, 0, 0, 0};
    if (isMasked) {
        if (client.available() < 4) return "";
        client.read(mask, 4);
    }

    if (payloadLen > 2048) {
        return "";
    }

    String result = "";
    result.reserve((size_t)payloadLen);
    for (size_t i = 0; i < payloadLen; i++) {
        if (!client.available()) {
            unsigned long wStart = millis();
            while (!client.available() && (millis() - wStart < 50));
            if (!client.available()) break;
        }
        uint8_t b = client.read();
        if (isMasked) {
            b ^= mask[i % 4];
        }
        result += (char)b;
    }
    return result;
}

void processWebSocketServer() {
    if (wsServer.hasClient()) {
        WiFiClient newClient = wsServer.available();
        if (newClient) {
            newClient.setTimeout(100);
            
            String request = "";
            String secKey = "";
            bool isUpgrade = false;
            
            while (newClient.connected()) {
                String line = newClient.readStringUntil('\n');
                if (request.length() == 0) request = line;
                line.trim();
                if (line.startsWith("Sec-WebSocket-Key:") || line.startsWith("sec-websocket-key:")) {
                    secKey = line.substring(18);
                    secKey.trim();
                }
                if (line.equalsIgnoreCase("Upgrade: websocket")) {
                    isUpgrade = true;
                }
                if (line.length() == 0) break;
            }
            
            String reqDeviceId = extractUrlParam(request, "device_id=");
            String tsStr = extractUrlParam(request, "ts=");
            String sig = extractUrlParam(request, "sig=");
            
            if (reqDeviceId.length() == 0 || tsStr.length() == 0 || sig.length() == 0 || secKey.length() == 0) {
                newClient.print("HTTP/1.1 400 Bad Request\r\n\r\nMissing auth/WebSocket headers");
                newClient.stop();
                return;
            }
            
            // 1. Verify HMAC Signature
            if (sharedSecret.length() > 0) {
                String expectedSig = calculateHMAC(reqDeviceId + ":" + tsStr, sharedSecret);
                if (!sig.equalsIgnoreCase(expectedSig)) {
                    Serial.printf("[-] WS Auth Failed for %s: Signature Mismatch\n", reqDeviceId.c_str());
                    newClient.print("HTTP/1.1 403 Forbidden\r\n\r\nInvalid Signature");
                    newClient.stop();
                    return;
                }
            }

            // 1b. Verify Replay Protection
            unsigned long long ts = strtoull(tsStr.c_str(), NULL, 10);
            if (!checkReplayProtection(reqDeviceId, ts)) {
                Serial.printf("[-] WS Auth Failed for %s: Replay Detected\n", reqDeviceId.c_str());
                newClient.print("HTTP/1.1 403 Forbidden\r\n\r\nReplay Detected");
                newClient.stop();
                return;
            }

            // 1c. Verify Slot Expiration & Lockdown
            String clientIp = newClient.remoteIP().toString();
            int wsSlotIdx = findSlotIndexForDevice(reqDeviceId, clientIp);
            if (wsSlotIdx < 0) {
                Serial.printf("[-] WS Mutex Rejected for %s (%s): Device is not paired to any slot on this ESP32\n", 
                    reqDeviceId.c_str(), clientIp.c_str());
                newClient.print("HTTP/1.1 423 Locked\r\n\r\nSLOT_NOT_PAIRED");
                newClient.stop();
                return;
            }
            int wsDaysLeft = -1;
            int wsExpStatus = getSlotExpirationStatus(wsSlotIdx, ts, wsDaysLeft);
            if (wsExpStatus == 2) {
                Serial.printf("[-] WS Mutex Rejected for %s: Slot Expired / Lockdown Active (Slot #%d)\n", 
                    reqDeviceId.c_str(), licenseSlots[wsSlotIdx].slotNum);
                newClient.print("HTTP/1.1 423 Locked\r\n\r\nSLOT_EXPIRED");
                newClient.stop();
                return;
            }
            
            // 2. Hardware Mutex Check (Single-Client Lock)
            unsigned long now = millis();
            if (armedIp.length() > 0 && armedIp != reqDeviceId && now < armedUntil && (sessionStartTime == 0 || (now - sessionStartTime < MAX_SESSION_DURATION))) {
                Serial.printf("[-] WS Mutex Rejected for %s: Slot BUSY with %s\n", reqDeviceId.c_str(), armedIp.c_str());
                newClient.print("HTTP/1.1 409 Conflict\r\n\r\nSLOT_BUSY");
                newClient.stop();
                return;
            }
            
            // Mutex passed, slot acquired. Save the nonce.
            recordDeviceNonce(reqDeviceId, ts);
            
            // 3. Complete RFC6455 Handshake
            String acceptKey = computeSecWebSocketAccept(secKey);
            String response = "HTTP/1.1 101 Switching Protocols\r\n";
            response += "Upgrade: websocket\r\n";
            response += "Connection: Upgrade\r\n";
            response += "Sec-WebSocket-Accept: " + acceptKey + "\r\n\r\n";
            
            newClient.print(response);
            newClient.flush();
            newClient.setNoDelay(true);
            
            if (isWsConnected && wsClient.connected()) {
                wsClient.stop();
            }
            wsClient = newClient;
            isWsConnected = true;
            
            if (armedIp != reqDeviceId || sessionStartTime == 0) {
                sessionStartTime = now;
            }
            armedIp = reqDeviceId;
            lastArmedDeviceId = reqDeviceId;
            lastArmedIp = getIpFromDeviceId(reqDeviceId);
            lastArmedTimeMs = now;
            armedUntil = now + ARM_TTL;
            pendingWsGracefulClose = false;
            
            Serial.printf("[⚡ WS Port 81] WebSocket ARMED securely for %s (TTL: %lu s)\n", reqDeviceId.c_str(), ARM_TTL / 1000);
            sendWsText(wsClient, "{\"event\":\"ARMED\"}");
        }
    }
    
    // Process active WebSocket client frames or disconnection
    if (isWsConnected) {
        if (!wsClient.connected()) {
            Serial.printf("[*] WS Client %s disconnected. Slot released.\n", armedIp.c_str());
            isWsConnected = false;
            if (armedIp.length() > 0) {
                lastArmedDeviceId = armedIp;
                lastArmedIp = getIpFromDeviceId(armedIp);
                lastArmedTimeMs = millis();
            }
            armedIp = "";
            armedUntil = 0;
            sessionStartTime = 0;
            return;
        }
        
        if (wsClient.available()) {
            String frameText = readWsText(wsClient);
            if (frameText == "DONE" || frameText == "CLOSE") {
                noInterrupts();
                int currentPulses = isrUniversalPulseCount;
                unsigned long lastPulse = isrLastPulseTimeMs;
                interrupts();

                // If coin pulses are actively in progress or arrived in the last 600ms, hold graceful close to deliver credit!
                if (currentPulses > 0 || (millis() - lastPulse < 600 && lastPulse > 0)) {
                    Serial.printf("[⚡ WS Port 81] 'DONE' received while coin pulses are active (%d pulses). Holding graceful close to finalize credit...\n", currentPulses);
                    pendingWsGracefulClose = true;
                    pendingWsGracefulCloseUntil = millis() + 2000;
                    armedUntil = millis() + 3000; // Extend temporary guard so pulse train completes safely
                } else {
                    wsClient.stop();
                    isWsConnected = false;
                    if (armedIp.length() > 0) {
                        lastArmedDeviceId = armedIp;
                        lastArmedIp = getIpFromDeviceId(armedIp);
                        lastArmedTimeMs = millis();
                    }
                    armedIp = "";
                    armedUntil = 0;
                    sessionStartTime = 0;
                    return;
                }
            }
        }
        
        // Check session TTL expiration or pending graceful close timeout
        unsigned long now = millis();
        if (pendingWsGracefulClose && now >= pendingWsGracefulCloseUntil) {
            Serial.println("[*] Pending graceful close timed out after coin train window. Slot released.");
            pendingWsGracefulClose = false;
            wsClient.stop();
            isWsConnected = false;
            if (armedIp.length() > 0) {
                lastArmedDeviceId = armedIp;
                lastArmedIp = getIpFromDeviceId(armedIp);
                lastArmedTimeMs = millis();
            }
            armedIp = "";
            armedUntil = 0;
            sessionStartTime = 0;
            return;
        }

        if (now >= armedUntil || (sessionStartTime > 0 && (now - sessionStartTime >= MAX_SESSION_DURATION))) {
            Serial.printf("[*] WS Session TTL expired for %s. Slot released.\n", armedIp.c_str());
            sendWsText(wsClient, "{\"event\":\"TIMEOUT\"}");
            wsClient.stop();
            isWsConnected = false;
            if (armedIp.length() > 0) {
                lastArmedDeviceId = armedIp;
                lastArmedIp = getIpFromDeviceId(armedIp);
                lastArmedTimeMs = millis();
            }
            armedIp = "";
            armedUntil = 0;
            sessionStartTime = 0;
        }
    }
}

// ============================================================================
// UDP BROADCAST DISCOVERY SERVICE (Port 8888)
// Listens for UDP broadcasts from Android Kiosk devices and responds
// with the ESP32 Master box identity, IP, MAC address, and configuration.
// ============================================================================
void sendUdpDiscoveryResponse(IPAddress targetIp, uint16_t targetPort) {
    if (WiFi.status() != WL_CONNECTED) return;

    String resp = "{\"type\":\"PISOPHONE_ESP32_RESPONSE\","
                  "\"device\":\"PISOPHONE_MASTER\","
                  "\"mac\":\"" + macAddressStr + "\","
                  "\"ip\":\"" + WiFi.localIP().toString() + "\","
                  "\"port\":80,"
                  "\"ws_port\":81,"
                  "\"device_name\":\"PisoPhone Master\","
                  "\"price\":" + String(coinPrice, 2) + ","
                  "\"minutes\":" + String(minutesPerCoin) + ","
                  "\"slots\":" + String(maxLicensedSlots) + ","
                  "\"uptime\":" + String(millis() / 1000) + "}";

    // 1. Direct unicast response to client
    if (targetIp != IPAddress(0, 0, 0, 0) && targetPort > 0) {
        udpServer.beginPacket(targetIp, targetPort);
        udpServer.write((const uint8_t*)resp.c_str(), resp.length());
        udpServer.endPacket();
    }

    // 2. Local broadcast on discovery port 8888 (handles clients listening on fixed port)
    IPAddress bcast(255, 255, 255, 255);
    udpServer.beginPacket(bcast, UDP_DISCOVERY_PORT);
    udpServer.write((const uint8_t*)resp.c_str(), resp.length());
    udpServer.endPacket();
}

void processUdpDiscovery() {
    if (WiFi.status() != WL_CONNECTED) return;

    int packetSize = udpServer.parsePacket();
    if (packetSize > 0) {
        char packetBuffer[512];
        int len = udpServer.read(packetBuffer, sizeof(packetBuffer) - 1);
        if (len > 0) {
            packetBuffer[len] = '\0';
            String msg = String(packetBuffer);
            msg.trim();

            // Canonical UDP discovery protocol
            if (msg.indexOf("PISOPHONE_DISCOVER") >= 0) {
                IPAddress remoteIp = udpServer.remoteIP();
                uint16_t remotePort = udpServer.remotePort();
                Serial.printf("[⚡ UDP Discovery] Valid probe received from %s:%d. Responding...\n",
                              remoteIp.toString().c_str(), remotePort);
                sendUdpDiscoveryResponse(remoteIp, remotePort);
            }
        }
    }

    // Periodic announcement beacon (every 10 seconds while connected to WiFi)
    static unsigned long lastUdpAnnounceMs = 0;
    if (millis() - lastUdpAnnounceMs > 10000 || lastUdpAnnounceMs == 0) {
        lastUdpAnnounceMs = millis();
        sendUdpDiscoveryResponse(IPAddress(255, 255, 255, 255), UDP_DISCOVERY_PORT);
    }
}

// ============================================================================
// SERIAL CLI (For USB Console Testing)
// ============================================================================
void processSerialCli() {
    if (!Serial.available()) return;
    String line = Serial.readStringUntil('\n');
    line.trim();
    if (line.length() == 0) return;

    if (line.equalsIgnoreCase("coin")) {
        triggerCoinEvent();
    } else if (line.startsWith("ucoin ") || line.startsWith("ucoin")) {
        int firstSpace = line.indexOf(' ');
        int pulses = (firstSpace != -1) ? line.substring(firstSpace + 1).toInt() : 1;
        if (pulses <= 0) pulses = 1;
        triggerUniversalCoinEvent(pulses);
    } else if (line.startsWith("add ") || line.startsWith("add")) {
        int firstSpace = line.indexOf(' ');
        if (firstSpace != -1) {
            int minutes = line.substring(firstSpace + 1).toInt();
            sendAddTime(minutes, "ALL");
        }
    } else if (line.equalsIgnoreCase("help")) {
        Serial.println("\nCommands: coin | ucoin <1|5|10|20> | add <minutes> | help");
    }
}

void setupWebServer() {
    // Initialize mDNS Responder ("kioskmanager.local")
    if (MDNS.begin("kioskmanager")) {
        MDNS.addService("kioskmanager", "tcp", 80);
        MDNS.addService("http", "tcp", 80);
        Serial.println("[+] mDNS service active at http://kioskmanager.local");
    } else {
        Serial.println("[-] Error setting up mDNS responder!");
    }

    // Initialize Authenticated Request Worker Queue & FreeRTOS Supervisor Task (8KB stack)
    authQueue = xQueueCreate(16, sizeof(AuthRequest));
    xTaskCreate(authWorkerTask, "AuthWorker", 8192, NULL, 1, NULL);

    // Port 80: HTTP Portal & API routes
    webServer.on("/", HTTP_GET, handlePortalRoot);
    webServer.on("/install", HTTP_GET, handlePortalRoot);
    webServer.on("/provision", HTTP_GET, handlePortalRoot);
    webServer.on("/logout", HTTP_GET, handleLogout);
    webServer.on("/save", HTTP_POST, handleSave);
    webServer.on("/reboot", HTTP_POST, handleReboot);
    webServer.on("/factory_reset", HTTP_POST, handleFactoryReset);
    webServer.on("/add_time", HTTP_POST, handleAddTime);
    webServer.on("/one_vs_one", HTTP_POST, handleOneVsOne);
    webServer.on("/insert_coin", HTTP_POST, handleInsertCoin);
    webServer.on("/insert_ucoin", HTTP_POST, handleInsertUniversalCoin);
    webServer.on("/reset_vault", HTTP_POST, handleResetVault);
    webServer.on("/api/status", HTTP_GET, handleApiStatus);
    webServer.on("/check_qualification", HTTP_GET, handleCheckQualification);
    webServer.on("/identify", HTTP_GET, handleIdentify);
    webServer.on("/ping", HTTP_GET, handlePing);
    webServer.on("/heartbeat", HTTP_GET, handleHeartbeat);
    webServer.on("/activate", HTTP_POST, handleActivate);
    webServer.on("/get_config", HTTP_GET, handleGetConfig);
    webServer.on("/config", HTTP_GET, handleGetConfig);
    webServer.on("/announce", HTTP_GET, handleAnnounce);
    webServer.on("/crash_report", HTTP_POST, handleCrashReport);
    webServer.on("/api/slots", HTTP_GET, handleApiSlots);
    webServer.on("/api/slots/pair", HTTP_ANY, handleApiSlotPair);
    webServer.on("/api/slots/unpair", HTTP_ANY, handleApiSlotUnpair);
    webServer.on("/api/slots/apply_token", HTTP_POST, handleApiSlotApplyToken);
    webServer.on("/api/slots/cloud_sync", HTTP_POST, handleApiSlotCloudSync);
    webServer.on("/api/credits/emulate_payment", HTTP_ANY, handleApiCreditsEmulatePayment);
    webServer.on("/api/credits/allocate", HTTP_ANY, handleApiCreditsAllocate);
    webServer.on("/api/credits/status", HTTP_GET, handleApiCreditsStatus);
    webServer.on("/api/relay", HTTP_ANY, []() {
        if (webServer.hasArg("invert")) {
            relayActiveLow = (webServer.arg("invert") == "1" || webServer.arg("invert") == "true");
            prefs.begin("kiosk_cfg", false);
            prefs.putBool("relay_active_low", relayActiveLow);
            prefs.end();
        }
        if (webServer.hasArg("mode")) {
            relayMode = webServer.arg("mode").toInt();
            prefs.begin("kiosk_cfg", false);
            prefs.putInt("relay_mode", relayMode);
            prefs.end();
        }
        if (webServer.hasArg("state")) {
            bool state = (webServer.arg("state") == "1" || webServer.arg("state") == "true");
            setRelayHardware(state);
            webServer.send(200, "application/json", "{\"status\":\"ok\",\"relay_pin\":" + String(relayPin) + ",\"state\":" + String(state ? 1 : 0) + ",\"active_low\":" + String(relayActiveLow ? 1 : 0) + ",\"mode\":" + String(relayMode) + "}");
            return;
        }
        webServer.send(200, "application/json", "{\"status\":\"ok\",\"relay_pin\":" + String(relayPin) + ",\"active_low\":" + String(relayActiveLow ? 1 : 0) + ",\"mode\":" + String(relayMode) + "}");
    });
    
    // Port 80: Web OTA Firmware Update Endpoints
    webServer.on("/update", HTTP_GET, handleOtaForm);
    webServer.on("/update", HTTP_POST, []() {
        if (!checkAuth()) return;
        webServer.sendHeader("Connection", "close");
        if (!otaIsValidBinary || Update.hasError() || !otaUpdateSuccess) {
            String errStr = otaErrorMsg.length() > 0 ? otaErrorMsg : ("Flash write failed (Error Code " + String(Update.getError()) + ")");
            webServer.send(400, "text/plain", errStr);
        } else {
            webServer.send(200, "text/plain", "SUCCESS");
            delay(1000);
            ESP.restart();
        }
    }, []() {
        if (!checkAuth()) return;
        HTTPUpload& upload = webServer.upload();
        
        if (upload.status == UPLOAD_FILE_START) {
            otaUpdateSuccess = false;
            otaFirstChunkReceived = false;
            otaIsValidBinary = true;
            otaErrorMsg = "";
            Update.clearError();
            
            Serial.printf("[OTA] Starting firmware flash: %s\n", upload.filename.c_str());
            
            if (!Update.begin(UPDATE_SIZE_UNKNOWN, U_FLASH)) {
                otaIsValidBinary = false;
                otaErrorMsg = "Failed to begin flash partition write (Error: " + String(Update.getError()) + ")";
                Serial.printf("[OTA] Error: %s\n", otaErrorMsg.c_str());
            }
        } else if (upload.status == UPLOAD_FILE_WRITE) {
            if (!otaIsValidBinary) return;

            if (upload.currentSize > 0) {
                if (Update.write(upload.buf, upload.currentSize) != upload.currentSize) {
                    otaIsValidBinary = false;
                    otaErrorMsg = "Flash write failed at offset " + String(Update.progress()) + " (Error: " + String(Update.getError()) + ")";
                    Serial.printf("[OTA] Error: %s\n", otaErrorMsg.c_str());
                } else {
                    Serial.print(".");
                }
            }
        } else if (upload.status == UPLOAD_FILE_END) {
            Serial.println();
            if (otaIsValidBinary) {
                if (Update.end(true)) {
                    Serial.printf("[OTA] Firmware flashing verified & completed successfully: %u bytes\n", upload.totalSize);
                    otaUpdateSuccess = true;
                } else {
                    otaIsValidBinary = false;
                    otaErrorMsg = "Firmware verification failed after write (Error: " + String(Update.getError()) + ")";
                    Serial.printf("[OTA] Error: %s\n", otaErrorMsg.c_str());
                }
            } else {
                Update.abort();
            }
        } else if (upload.status == UPLOAD_FILE_ABORTED) {
            Update.abort();
            otaIsValidBinary = false;
            otaErrorMsg = "Upload connection was aborted prematurely.";
            Serial.println("[OTA] Upload aborted by client.");
        }
    });
    webServer.begin();

    // Port 81: Real-time WebSocket Server
    wsServer.begin();

    // Port 8888: UDP Broadcast Discovery Service
    udpServer.begin(UDP_DISCOVERY_PORT);
    Serial.printf("[!] Port %d: UDP Discovery Server active\n", UDP_DISCOVERY_PORT);

    if (WiFi.status() == WL_CONNECTED) {
        Serial.printf("[!] Port 80: Management at http://%s:80\n", WiFi.localIP().toString().c_str());
        Serial.printf("[!] Port 81: WebSocket at ws://%s:81/ws\n\n", WiFi.localIP().toString().c_str());
        sendUdpDiscoveryResponse(IPAddress(255, 255, 255, 255), UDP_DISCOVERY_PORT);
    } else {
        Serial.printf("[!] Wi-Fi disconnected. Waiting for hotspot '%s' to become available...\n", wifiSsid.c_str());
    }
}
