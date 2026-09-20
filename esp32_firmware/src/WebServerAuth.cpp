#include "WebServerAuth.h"
#include "PaymentQueueManager.h"
#include "WebServerModule.h"
#include "Config.h"
#include "Security.h"
#include "DeviceManager.h"
#include "DeviceNetwork.h"
#include "SuperAdminManager.h"
#include <WiFi.h>
#include <HTTPClient.h>

static String parseAckField(const String& body, const String& key) {
    int pos = body.indexOf(key + "=");
    if (pos == -1) return "";
    int start = pos + key.length() + 1;
    int end = body.indexOf(':', start);
    if (end == -1) end = body.length();
    return body.substring(start, end);
}

void authWorkerTask(void *pvParameters) {
    AuthRequest req;
    while (true) {
        if (xQueueReceive(authQueue, &req, portMAX_DELAY) == pdTRUE) {
            String ip = String(req.ip);
            if (ip.length() == 0) continue;

            String finalParams = String(req.params);
            uint64_t currentMasterMs = getCurrentMasterTimeMs();
            if (finalParams.indexOf("ts=") == -1) {
                if (finalParams.length() > 0) {
                    finalParams += "&ts=" + String(currentMasterMs);
                } else {
                    finalParams = "ts=" + String(currentMasterMs);
                }
            }
            
            String currentTxId = "";
            int txPos = finalParams.indexOf("tx_id=");
            if (txPos != -1) {
                int endPos = finalParams.indexOf('&', txPos);
                if (endPos == -1) endPos = finalParams.length();
                currentTxId = finalParams.substring(txPos + 6, endPos);
            } else if (finalParams.indexOf("nonce=") == -1) {
                currentTxId = "tx-" + String(currentMasterMs) + "-" + String(random(10000, 99999));
                finalParams += "&tx_id=" + currentTxId;
            }

            String currentDevId = "";
            int devPos = finalParams.indexOf("device_id=");
            if (devPos != -1) {
                int endDev = finalParams.indexOf('&', devPos);
                if (endDev == -1) endDev = finalParams.length();
                currentDevId = finalParams.substring(devPos + 10, endDev);
            }
            if (currentDevId.length() == 0) {
                currentDevId = getDeviceIdFromIp(ip);
            }

            String currentAmount = "0";
            int amtPos = finalParams.indexOf("amount=");
            if (amtPos != -1) {
                int endAmt = finalParams.indexOf('&', amtPos);
                if (endAmt == -1) endAmt = finalParams.length();
                currentAmount = finalParams.substring(amtPos + 7, endAmt);
            }

            String currentTs = String(currentMasterMs);
            int pTsPos = finalParams.indexOf("ts=");
            if (pTsPos != -1) {
                int endTs = finalParams.indexOf('&', pTsPos);
                if (endTs == -1) endTs = finalParams.length();
                currentTs = finalParams.substring(pTsPos + 3, endTs);
            }

            if (currentDevId.length() > 0 && finalParams.indexOf("device_id=") == -1) {
                if (finalParams.length() > 0) finalParams += "&device_id=" + currentDevId;
                else finalParams = "device_id=" + currentDevId;
            }
            if (currentTxId.length() > 0 && finalParams.indexOf("tx_id=") == -1) {
                if (finalParams.length() > 0) finalParams += "&tx_id=" + currentTxId;
                else finalParams = "tx_id=" + currentTxId;
            }

            int retries = 0;
            const int maxAttempts = 3;
            bool delivered = false;

            while (!delivered && retries < maxAttempts) {
                if (WiFi.status() != WL_CONNECTED) {
                    vTaskDelay(pdMS_TO_TICKS(1000));
                    retries++;
                    continue;
                }

                WiFiClient client;
                HTTPClient http;
                http.setConnectTimeout(req.timeoutMs);
                http.setTimeout(req.timeoutMs);
                http.setReuse(false);

                String actionUrl = "http://" + ip + ":" + String(req.port) + String(req.actionPath);
                String encryptedPayload = aes_encrypt(finalParams, sharedSecret);
                String hmacSig = calculateHttpReqSignature("GET", String(req.actionPath), currentDevId, currentTxId, currentTs, encryptedPayload, sharedSecret);
                actionUrl += "?payload=" + encryptedPayload + "&hmac=" + hmacSig + "&device_id=" + currentDevId + "&tx_id=" + currentTxId + "&ts=" + currentTs;

                if (http.begin(client, actionUrl)) {
                    int code = http.GET();
                    Serial.printf("[AUTH WORKER] %s -> HTTP %d (attempt %d/%d)\n",
                                  req.actionPath, code, retries + 1, maxAttempts);
                    String respBody = "";
                    if (code >= 200 && code < 300) {
                        respBody = http.getString();
                    }
                    http.end();

                    if (code >= 200 && code < 300) {
                        if (currentTxId.length() > 0 && currentDevId.length() > 0) {
                            bool ackValid = false;
                            String status = "";
                            int ackAmt = 0;
                            int ackSec = 0;
                            String ackDev = "";
                            String ackTx = "";
                            if (respBody.startsWith("OK")) {
                                status = "OK";
                            } else if (respBody.startsWith("ALREADY_PROCESSED")) {
                                status = "ALREADY_PROCESSED";
                            }

                            if (status.length() > 0) {
                                ackTx = parseAckField(respBody, "tx_id");
                                ackDev = parseAckField(respBody, "device_id");
                                String ackAmtStr = parseAckField(respBody, "amount");
                                String ackSecStr = parseAckField(respBody, "seconds");
                                String ackTs = parseAckField(respBody, "ts");
                                String ackSig = parseAckField(respBody, "v_sig");

                                if (ackTx.length() > 0 && ackDev.length() > 0 && ackTs.length() > 0 && ackSig.length() > 0) {
                                    // Parse amount as nonnegative integer (reject empty, fractional, non-digits, out-of-range)
                                    bool amtIsNum = (ackAmtStr.length() > 0 && ackAmtStr.length() <= 10);
                                    for (unsigned int i = 0; i < ackAmtStr.length(); i++) {
                                        if (!isDigit(ackAmtStr[i])) { amtIsNum = false; break; }
                                    }
                                    long long amtVal = amtIsNum ? atoll(ackAmtStr.c_str()) : -1;
                                    if (amtVal < 0 || amtVal > 1000000) amtIsNum = false;

                                    // Parse seconds as signed integer with optional leading minus sign
                                    bool secIsNum = (ackSecStr.length() > 0);
                                    size_t startSecIdx = 0;
                                    if (ackSecStr[0] == '-') {
                                        if (ackSecStr.length() == 1) secIsNum = false;
                                        startSecIdx = 1;
                                    }
                                    if (secIsNum && (ackSecStr.length() - startSecIdx) <= 10) {
                                        for (size_t i = startSecIdx; i < ackSecStr.length(); i++) {
                                            if (!isDigit(ackSecStr[i])) { secIsNum = false; break; }
                                        }
                                    } else {
                                        secIsNum = false;
                                    }
                                    long long secValLL = 0;
                                    if (secIsNum) {
                                        secValLL = atoll(ackSecStr.c_str());
                                        if (secValLL < -2147483648LL || secValLL > 2147483647LL) {
                                            secIsNum = false;
                                        }
                                    }

                                    if (amtIsNum && secIsNum) {
                                        ackAmt = (int)amtVal;
                                        ackSec = (int)secValLL;

                                        // Verify recipient, transaction ID, amount, and seconds against outgoing adjustment
                                        int expectedAmt = currentAmount.toInt();
                                        int expectedSec = 0;
                                        int secParamPos = finalParams.indexOf("seconds=");
                                        if (secParamPos != -1) {
                                            int endSec = finalParams.indexOf('&', secParamPos);
                                            if (endSec == -1) endSec = finalParams.length();
                                            expectedSec = finalParams.substring(secParamPos + 8, endSec).toInt();
                                        }

                                        if (ackTx == currentTxId && ackDev == currentDevId) {
                                            if (ackAmt == expectedAmt && ackSec == expectedSec) {
                                                if (verifyAckSignature(ackDev, ackTx, ackAmt, ackSec, ackTs, status, ackSig, sharedSecret)) {
                                                    ackValid = true;
                                                } else {
                                                    Serial.printf("[AUTH WORKER] Invalid ACK signature for tx_id='%s'\n", currentTxId.c_str());
                                                }
                                            } else {
                                                Serial.printf("[AUTH WORKER] Mismatched ACK adjustment values for tx_id='%s': expected (amt=%d, sec=%d) vs got (amt=%d, sec=%d)\n",
                                                              currentTxId.c_str(), expectedAmt, expectedSec, ackAmt, ackSec);
                                            }
                                        } else {
                                            Serial.printf("[AUTH WORKER] Mismatched ACK recipient/tx: (dev=%s, tx=%s) vs received (dev=%s, tx=%s)\n",
                                                          currentDevId.c_str(), currentTxId.c_str(), ackDev.c_str(), ackTx.c_str());
                                        }
                                    } else {
                                        Serial.printf("[AUTH WORKER] Non-numeric ACK fields: amountStr='%s', secondsStr='%s'\n", ackAmtStr.c_str(), ackSecStr.c_str());
                                    }
                                } else {
                                    Serial.printf("[AUTH WORKER] Empty required ACK fields for tx_id='%s'\n", currentTxId.c_str());
                                }
                            } else {
                                Serial.printf("[AUTH WORKER] Unsigned/malformed response body for tx_id='%s': '%s'\n",
                                              currentTxId.c_str(), respBody.c_str());
                            }

                            if (ackValid) {
                                delivered = true;
                                if (currentTxId.startsWith("tx-")) {
                                    if (acknowledgePhonePayment(ackDev, ackTx, ackAmt, ackSec, status)) {
                                        Serial.printf("[AUTH WORKER] Durable phone ACK accepted for tx_id='%s' (device: %s)\n",
                                                      currentTxId.c_str(), ackDev.c_str());
                                    }
                                } else {
                                    Serial.printf("[AUTH WORKER] Verified adjustment ACK confirmed for tx_id='%s' (device: %s, seconds: %d)\n",
                                                  currentTxId.c_str(), ackDev.c_str(), ackSec);
                                    recordAdjustmentConfirmed(currentTxId, ackDev, ackSec);
                                }
                            } else {
                                Serial.printf("[AUTH WORKER] Payment ACK rejected due to mismatched recipient/signature (tx_id=%s)\n",
                                              currentTxId.c_str());
                            }
                        } else {
                            delivered = true;
                        }
                    }
                }

                if (!delivered) {
                    retries++;
                    if (retries < maxAttempts) {
                        vTaskDelay(pdMS_TO_TICKS(1000UL * retries));
                    }
                }
            }

            if (!delivered) {
                Serial.printf("[AUTH WORKER] Delivery deferred after %d failed attempts: %s -> %s.\n",
                              maxAttempts, req.actionPath, ip.c_str());
            }

            vTaskDelay(pdMS_TO_TICKS(40)); // Prevent socket/radio contention
        }
    }
}

bool checkAdminAuth() {
    // Strictly require administrator credentials.
    // Phone or controller telemetry signatures MUST NOT grant administrative access.
    if (webServer.authenticate("superadmin", superAdminPassword.c_str())) {
        return true;
    }
    if (webServer.authenticate("admin", webPassword.c_str())) {
        return true;
    }
    webServer.requestAuthentication(BASIC_AUTH, "HARDWARE Admin Login", "Unauthorized: Admin credentials required.");
    return false;
}

bool checkAuth() {
    // Check admin credentials first
    if (webServer.authenticate("superadmin", superAdminPassword.c_str()) ||
        webServer.authenticate("admin", webPassword.c_str())) {
        return true;
    }
    // Device telemetry signatures are ONLY accepted for telemetry / device status checks
    if (webServer.hasArg("device_id") && webServer.hasArg("ts") && webServer.hasArg("sig")) {
        String devId = webServer.arg("device_id");
        String tsStr = webServer.arg("ts");
        String sig = webServer.arg("sig");
        if (verifyTelemetryAuth(devId, tsStr, sig)) {
            return true;
        }
    }
    webServer.requestAuthentication(BASIC_AUTH, "HARDWARE Admin Login", "Unauthorized: Access denied.");
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
        prefs.begin(NVS_NAMESPACE, false);
        prefs.putULong(NVS_KEY_TOTAL_COINS, 0);
        prefs.putFloat(NVS_KEY_TOTAL_EARNINGS, 0.0f);
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
