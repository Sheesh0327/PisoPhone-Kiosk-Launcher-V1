#include "WebServerAuth.h"
#include "WebServerModule.h"
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
