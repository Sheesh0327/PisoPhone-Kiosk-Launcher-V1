#include "WebServer.h"
#include "Config.h"
#include "Security.h"
#include "HardwareManager.h"
#include "DeviceManager.h"
#include "DeviceNetwork.h"
#include <WiFi.h>
#include <WebServer.h>

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
    if (targetIp != "ALL") {
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
        bool isActive = isSlotActive(slotIdx);
        if (!isActive) {
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
    String json = "{\"maxSlots\":" + String(maxLicensedSlots) + ",\"mac\":\"" + macAddressStr + "\",\"slots\":[";
    for (int i = 0; i < maxLicensedSlots; i++) {
        if (i > 0) json += ",";
        bool isActive = isSlotActive(i);
        bool isExpiredOrInactive = (!isActive);
        int expStatus = isActive ? 0 : 2;

        json += "{";
        json += "\"slotNum\":" + String(licenseSlots[i].slotNum) + ",";
        json += "\"deviceId\":\"" + licenseSlots[i].deviceId + "\",";
        json += "\"ip\":\"" + licenseSlots[i].ip + "\",";
        json += "\"name\":\"" + licenseSlots[i].name + "\",";
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
        json += "\"expStatus\":" + String(licenseSlots[i].active ? 0 : 2) + ",";
        json += "\"isExpiredOrInactive\":" + String(!licenseSlots[i].active ? "true" : "false");
        json += "}";
    }
    json += "],\"unassigned_devices\":[";
    bool firstUnassigned = true;
    unsigned long nowMs = millis();
    for (int i = 0; i < trackedDeviceCount; i++) {
        if (trackedDevices[i].deviceId.length() == 0 && trackedDevices[i].lastKnownIp.length() == 0) continue;
        String dId = trackedDevices[i].deviceId;
        if (dId.length() == 0) dId = trackedDevices[i].lastKnownIp;
        if (findSlotIndexForDevice(dId, trackedDevices[i].lastKnownIp) >= 0) continue;
        
        bool isOnline = (nowMs - trackedDevices[i].lastSeenMs < 30000);
        if (nowMs - trackedDevices[i].lastSeenMs > 300000) continue;
        
        if (!firstUnassigned) json += ",";
        firstUnassigned = false;
        
        String dName = getDeviceNameByIpOrId(trackedDevices[i].lastKnownIp, dId);
        if (dName.length() == 0 || dName == dId) dName = "PisoPhone Terminal";
        
        json += "{";
        json += "\"id\":\"" + dId + "\",";
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

void handleIdentify() {
    String reqIp = webServer.hasArg("ip") ? webServer.arg("ip") : webServer.client().remoteIP().toString();
    String devId = webServer.hasArg("device_id") ? webServer.arg("device_id") : (webServer.hasArg("id") ? webServer.arg("id") : "");
    if (devId.length() == 0 && reqIp.length() > 0 && reqIp != "127.0.0.1" && reqIp != "0.0.0.0") {
        devId = "DEV_" + reqIp;
    }
    if (reqIp.length() > 0 && reqIp != "127.0.0.1" && reqIp != "0.0.0.0") {
        updateDeviceTelemetry(devId, reqIp, 0, 0, 100, false, 0);
    }
    String devName = getDeviceNameByIpOrId(reqIp, devId);
    int slotIdx = findSlotIndexForDevice(devId, reqIp);
    String json = "{\"device\":\"HARDWARE_kiosk\",\"mac\":\"" + macAddressStr + "\",\"version\":\"3.0\",\"minutes\":" + String(minutesPerCoin) + ",\"price\":1.0";
    if (devName.length() > 0) {
        json += ",\"device_name\":\"" + devName + "\"";
    }
    json += "}";
    webServer.send(200, "application/json", json);
}
#include "WebServer.h"
#include "Config.h"
#include "Security.h"
#include "DeviceManager.h"
#include "SuperAdminManager.h"
#include <WiFi.h>
#include <HTTPClient.h>

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
            uint64_t currentMasterMs = getCurrentMasterTimeMs();
            if (finalParams.indexOf("ts=") == -1) {
                if (finalParams.length() > 0) {
                    finalParams += "&ts=" + String(currentMasterMs);
                } else {
                    finalParams = "ts=" + String(currentMasterMs);
                }
            }
            
            if (finalParams.indexOf("tx_id=") == -1 && finalParams.indexOf("nonce=") == -1) {
                String txId = "tx-" + String(currentMasterMs) + "-" + String(random(10000, 99999));
                finalParams += "&tx_id=" + txId;
            }

            String encryptedPayload = aes_encrypt(finalParams, sharedSecret);
            String hmacSig = calculateHMAC(encryptedPayload, sharedSecret);
            actionUrl += "?payload=" + encryptedPayload + "&hmac=" + hmacSig;

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
    if (webServer.authenticate("superadmin", superAdminPassword.c_str())) {
        return true;
    }
    if (webServer.authenticate("admin", webPassword.c_str())) {
        return true;
    }
    webServer.requestAuthentication(BASIC_AUTH, "HARDWARE Admin Login", "Unauthorized: Please enter admin credentials.");
    return false;
}

void redirectHome() {
    webServer.sendHeader("Location", "/");
    webServer.send(303);
}

void handleLogout() {
    if (isVaultUnmasked) {
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
        isVaultUnmasked = false;
        unmaskExpiryTimestamp = 0;
        Serial.println("[👑 SUPER ADMIN] Vault reset triggered by Super Admin logout.");
    }
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
#include "WebServer.h"
#include "Config.h"
#include "Security.h"
#include "HardwareManager.h"
#include "DeviceManager.h"
#include "SuperAdminManager.h"
#include "WebDashboard.h"
#include "WebDashboardResources.h"
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
        if (enteredPw == superAdminPassword || webServer.authenticate("superadmin", superAdminPassword.c_str())) {
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
            Serial.println("[👑 VAULT] Lifetime revenue counter reset to 0 by Super Admin (Vendor).");
        } else {
            Serial.println("[⚠️ VAULT] Reset attempted without valid Super Admin credentials.");
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
    if (webServer.hasArg("minutes_per_coin")) {
        int m = webServer.arg("minutes_per_coin").toInt();
        if (m >= 1) {
            minutesPerCoin = m;
            prefs.putInt("mins_per_coin", minutesPerCoin);
        }
    }
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
                    String configParams = "admin_pin=" + webPassword + "&minutes=" + String(minutesPerCoin) + "&price=1.0";
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
#include "WebServer.h"
#include "Config.h"
#include "Security.h"
#include "HardwareManager.h"
#include "DeviceManager.h"
#include "SuperAdminManager.h"
#include "WebDashboard.h"
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
String wsSessionDeviceId = "";
WiFiUDP udpServer;
QueueHandle_t authQueue = NULL;

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
    webServer.on("/logout", HTTP_GET, handleLogout);
    webServer.on("/save", HTTP_POST, handleSave);
    webServer.on("/reboot", HTTP_POST, handleReboot);
    webServer.on("/factory_reset", HTTP_POST, handleFactoryReset);
    webServer.on("/add_time", HTTP_POST, handleAddTime);
    webServer.on("/one_vs_one", HTTP_POST, handleOneVsOne);
    webServer.on("/insert_ucoin", HTTP_POST, handleInsertUniversalCoin);
    webServer.on("/reset_vault", HTTP_POST, handleResetVault);
    webServer.on("/api/superadmin/auth", HTTP_POST, handleSuperAdminAuth);
    webServer.on("/api/superadmin/unmask", HTTP_POST, handleSuperAdminUnmask);
    webServer.on("/api/superadmin/reset_vault", HTTP_POST, handleSuperAdminResetVault);
    webServer.on("/api/superadmin/save_split", HTTP_POST, handleSuperAdminSaveSplit);
    webServer.on("/api/superadmin/change_pw", HTTP_POST, handleSuperAdminChangePassword);
    webServer.on("/api/status", HTTP_GET, handleApiStatus);
    webServer.on("/check_qualification", HTTP_GET, handleCheckQualification);
    webServer.on("/identify", HTTP_GET, handleIdentify);
    webServer.on("/query_time", HTTP_GET, handleQueryTime);
    webServer.on("/heartbeat", HTTP_GET, handleHeartbeat);
    webServer.on("/get_config", HTTP_GET, handleGetConfig);
    webServer.on("/crash_report", HTTP_POST, handleCrashReport);
    webServer.on("/api/slots", HTTP_GET, handleApiSlots);
    webServer.on("/api/slots/pair", HTTP_ANY, handleApiSlotPair);
    webServer.on("/api/slots/unpair", HTTP_ANY, handleApiSlotUnpair);
    webServer.on("/api/slots/apply_token", HTTP_POST, handleApiSlotApplyToken);
    webServer.on("/api/slots/cloud_sync", HTTP_POST, handleApiSlotCloudSync);
    
    webServer.on("/api/relay", HTTP_ANY, []() {
        bool hasInvert = webServer.hasArg("invert");
        if (hasInvert) {
            prefs.begin("kiosk_cfg", false);
            relayActiveLow = (webServer.arg("invert") == "1" || webServer.arg("invert") == "true");
            prefs.putBool("relay_active_low", relayActiveLow);
            prefs.end();
        }
        if (webServer.hasArg("state")) {
            bool state = (webServer.arg("state") == "1" || webServer.arg("state") == "true");
            setRelayHardware(state);
            webServer.send(200, "application/json", "{\"status\":\"ok\",\"relay_pin\":" + String(relayPin) + ",\"state\":" + String(state ? 1 : 0) + ",\"active_low\":" + String(relayActiveLow ? 1 : 0) + "}");
            return;
        }
        webServer.send(200, "application/json", "{\"status\":\"ok\",\"relay_pin\":" + String(relayPin) + ",\"active_low\":" + String(relayActiveLow ? 1 : 0) + "}");
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
#include "WebServer.h"
#include "Config.h"
#include "Security.h"
#include "DeviceManager.h"
#include "DeviceNetwork.h"
#include <WiFi.h>
#include <WebServer.h>

void handleHeartbeat() {
    String deviceId = webServer.hasArg("device_id") ? webServer.arg("device_id") : (webServer.hasArg("id") ? webServer.arg("id") : "");
    String reqIp = webServer.hasArg("ip") ? webServer.arg("ip") : "";
    if (reqIp.length() == 0 || reqIp == "127.0.0.1" || reqIp == "0.0.0.0") reqIp = webServer.client().remoteIP().toString();
    String tsStr = webServer.hasArg("ts") ? webServer.arg("ts") : "0";
    String sig = webServer.hasArg("sig") ? webServer.arg("sig") : "";
    unsigned long long ts = strtoull(tsStr.c_str(), NULL, 10);
    
    int timeRem = webServer.hasArg("time") ? webServer.arg("time").toInt() : 0;
    int state = webServer.hasArg("state") ? webServer.arg("state").toInt() : 0;
    int battery = webServer.hasArg("battery") ? webServer.arg("battery").toInt() : 100;
    bool charging = webServer.hasArg("charging") ? (webServer.arg("charging").toInt() == 1 || webServer.arg("charging") == "true") : false;

    if (deviceId.length() == 0 && reqIp.length() > 0 && reqIp != "127.0.0.1" && reqIp != "0.0.0.0") {
        deviceId = "DEV_" + reqIp;
    }

    bool isAuth = verifyTelemetryAuth(deviceId, tsStr, sig);
    int slotIdx = findSlotIndexForDevice(deviceId, reqIp);

    if (!isAuth && slotIdx >= 0) {
        webServer.send(403, "application/json", "{\"error\":\"AUTH_FAILED_OR_REPLAY\"}");
        return;
    }

    if (slotIdx < 0 || !isAuth) {
        String devName = getDeviceNameByIpOrId(reqIp, deviceId);
        if (devName.length() == 0 || devName == deviceId) devName = "PisoPhone Terminal";

        String json = "{\"status\":\"unassigned\",\"device\":\"HARDWARE_kiosk\",\"mac\":\"" + macAddressStr + "\"";
        json += ",\"slot_num\":0,\"is_paired\":false,\"slot_expired\":true,\"slot_status\":\"unassigned\",\"slot_warning\":false";
        json += ",\"message\":\"Connected to ESP32: Awaiting Slot Assignment in Admin Portal.\"";
        json += ",\"device_name\":\"" + devName + "\"}";
        webServer.send(200, "application/json", json);
        return;
    }

    if (deviceId.length() > 0 || reqIp.length() > 0) {
        updateDeviceTelemetry(deviceId, reqIp, timeRem, state, battery, charging, ts);
    }

    if (ts > 0) updateMasterTime(ts);
    bool isActive = isSlotActive(slotIdx);

    String status = (!isActive) ? "slot_expired" : "ok";
    String devName = getDeviceNameByIpOrId(reqIp, deviceId);
    String json = "{\"status\":\"" + status + "\",\"device\":\"HARDWARE_kiosk\",\"mac\":\"" + macAddressStr + "\"";
    if (slotIdx >= 0) {
        String encPin = aes_encrypt("PIN:" + webPassword, sharedSecret);
        json += ",\"admin_pin\":\"" + encPin + "\"";
    }
    if (devName.length() > 0) {
        json += ",\"device_name\":\"" + devName + "\"";
    }
    if (slotIdx >= 0) {
        json += ",\"slot_num\":" + String(licenseSlots[slotIdx].slotNum);
    }
    if (!isActive) {
        json += ",\"is_paired\":true,\"slot_expired\":true,\"slot_status\":\"expired\",\"slot_warning\":false";
        json += ",\"message\":\"Device Inactive: Please activate device slot on ESP32 Portal.\"";
    } else {
        json += ",\"is_paired\":true,\"slot_expired\":false,\"slot_status\":\"active\",\"slot_warning\":false";
    }
    json += "}";
    webServer.send(200, "application/json", json);
}

void handleGetConfig() {
    String json = "{\"device\":\"HARDWARE_kiosk\",\"relay_pin\":" + String(relayPin) + "}";
    webServer.send(200, "application/json", json);
}

void handleCrashReport() {
    String body = webServer.arg("plain");
    Serial.printf("\n[⚠️ CRASH REPORT FROM CLIENT]\n%s\n", body.c_str());
    webServer.send(200, "text/plain", "OK");
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
    } else if (!p1Ok && !p2Ok) {
        snprintf(msgBuf, sizeof(msgBuf), "Both players need to add more time to meet the %dm stake.", mins);
    } else if (!p1Ok) {
        snprintf(msgBuf, sizeof(msgBuf), "Player 1 needs at least %dm more active time.", mins - p1M);
    } else {
        snprintf(msgBuf, sizeof(msgBuf), "Player 2 needs at least %dm more active time.", mins - p2M);
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
