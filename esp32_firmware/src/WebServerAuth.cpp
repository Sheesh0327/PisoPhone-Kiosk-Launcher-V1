// HTTP authentication for the web server. Admin pages use Basic auth (operator "admin" or
// "superadmin") with per-client lockout after repeated failures; phone telemetry uses signed requests.
// Also hosts the background AuthWorker task that sends encrypted, signed commands to phones.

#include "WebServerAuth.h"
#include "PaymentQueueManager.h"
#include "WebServerModule.h"
#include "Config.h"
#include "Security.h"
#include "DeviceManager.h"
#include "SuperAdminManager.h"
#include "Diagnostics.h"
#include "InputSafety.h"
#include <WiFi.h>
#include <HTTPClient.h>
#include "SuperAdminCreds.h"

// A phone acknowledgement the AuthWorker confirmed, waiting for loop() to apply it (see WebServerAuth.h).
struct WorkerAck {
    char deviceId[97]; // PaymentRecord::targetId is the same size
    char txId[64];     // PaymentRecord::txId is the same size
};
static const int WORKER_ACK_QUEUE_LEN = 16;
static QueueHandle_t workerAckQueue = NULL;

void initWorkerAckQueue() {
    if (workerAckQueue == NULL) workerAckQueue = xQueueCreate(WORKER_ACK_QUEUE_LEN, sizeof(WorkerAck));
}

// Runs on the AuthWorker task: only copies the ids into the queue. If the queue is full or missing the payment simply stays
// queued on the box and is offered again in 10 s; the phone then answers ALREADY_PROCESSED with a signed acknowledgement,
// so nothing is lost by dropping an event here.
static bool postWorkerAck(const String& deviceId, const String& txId) {
    if (workerAckQueue == NULL || deviceId.length() >= sizeof(WorkerAck::deviceId) ||
        txId.length() >= sizeof(WorkerAck::txId)) {
        return false;
    }
    WorkerAck ack;
    memset(&ack, 0, sizeof(ack));
    strncpy(ack.deviceId, deviceId.c_str(), sizeof(ack.deviceId) - 1);
    strncpy(ack.txId, txId.c_str(), sizeof(ack.txId) - 1);
    return xQueueSend(workerAckQueue, &ack, 0) == pdTRUE;
}

// Runs on the loop() task, the only task that may change the account table and phone slots.
void processWorkerAcks() {
    if (workerAckQueue == NULL) return;
    WorkerAck ack;
    // bounded per pass so a burst cannot stall the coin loop
    for (int n = 0; n < WORKER_ACK_QUEUE_LEN && xQueueReceive(workerAckQueue, &ack, 0) == pdTRUE; n++) {
        if (acknowledgePhonePayment(String(ack.deviceId), String(ack.txId))) {
            Serial.printf("[AUTH WORKER] Durable phone ACK accepted for tx_id='%s' (device: %s)\n", ack.txId,
                          ack.deviceId);
        }
    }
}

void authWorkerTask(void* pvParameters) {
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
                currentTxId = generateTxId("tx-");
                finalParams += "&tx_id=" + currentTxId;
            }

            String currentDevId = "";
            int devPos = finalParams.indexOf("device_id=");
            if (devPos != -1) {
                int endDev = finalParams.indexOf('&', devPos);
                if (endDev == -1) endDev = finalParams.length();
                currentDevId = finalParams.substring(devPos + 10, endDev);
            }

            String currentAmount = "1";
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

            // Ensure versioned signature on all /add_time requests
            if (String(req.actionPath) == "/add_time" && finalParams.indexOf("v_sig=") == -1 &&
                currentDevId.length() > 0) {
                String vPayload = "v1:" + currentDevId + ":" + currentTxId + ":" + currentAmount + ":" + currentTs;
                String vSig = calculateHMAC(vPayload, getSharedSecret());
                finalParams += "&v_sig=" + vSig;
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
                String encryptedPayload = aes_encrypt(finalParams, getSharedSecret());
                String hmacSig = calculateHMAC(encryptedPayload, getSharedSecret());
                actionUrl += "?payload=" + encryptedPayload + "&hmac=" + hmacSig;

                if (http.begin(client, actionUrl)) {
                    int code = http.GET();
                    Serial.printf("[AUTH WORKER] %s -> HTTP %d (attempt %d/%d)\n", req.actionPath, code, retries + 1,
                                  maxAttempts);
                    String respBody = "";
                    if (code >= 200 && code < 300) {
                        respBody = http.getString();
                    }
                    http.end();

                    if (code >= 200 && code < 300) {
                        if (currentTxId.length() > 0 && currentDevId.length() > 0) {
                            bool ackValid = false;
                            int ackTxPos = respBody.indexOf("tx_id=");
                            int ackDevPos = respBody.indexOf("device_id=");
                            if (ackTxPos != -1 && ackDevPos != -1) {
                                int ackTxEnd = respBody.indexOf(':', ackTxPos);
                                if (ackTxEnd == -1) ackTxEnd = respBody.length();
                                String ackTx = respBody.substring(ackTxPos + 6, ackTxEnd);

                                int ackDevEnd = respBody.indexOf(':', ackDevPos);
                                if (ackDevEnd == -1) ackDevEnd = respBody.length();
                                String ackDev = respBody.substring(ackDevPos + 10, ackDevEnd);

                                if (ackTx == currentTxId && ackDev == currentDevId) {
                                    int sigPos = respBody.indexOf("v_sig=");
                                    int tsPosIdx = respBody.indexOf("ts=");
                                    if (sigPos != -1 && tsPosIdx != -1) {
                                        int sigEnd = respBody.indexOf(':', sigPos);
                                        if (sigEnd == -1) sigEnd = respBody.length();
                                        String ackSig = respBody.substring(sigPos + 6, sigEnd);

                                        int tsEnd = respBody.indexOf(':', tsPosIdx);
                                        if (tsEnd == -1) tsEnd = respBody.length();
                                        String ackTs = respBody.substring(tsPosIdx + 3, tsEnd);

                                        String expectedSig = calculateHMAC("v1:" + currentDevId + ":" + currentTxId +
                                                                               ":" + currentAmount + ":" + ackTs,
                                                                           getSharedSecret());
                                        if (ackSig.equalsIgnoreCase(expectedSig)) {
                                            ackValid = true;
                                        } else {
                                            Serial.printf("[AUTH WORKER] Invalid ACK signature for tx_id='%s'\n",
                                                          currentTxId.c_str());
                                        }
                                    } else {
                                        // Unsigned: only before the box has a shared secret. Whatever answers at the
                                        // phone's address must not be able to clear a paid coin with a plain "OK".
                                        ackValid = getSharedSecret().length() == 0;
                                    }
                                } else {
                                    Serial.printf(
                                        "[AUTH WORKER] Mismatched ACK: (dev=%s, tx=%s) vs received (dev=%s, tx=%s)\n",
                                        currentDevId.c_str(), currentTxId.c_str(), ackDev.c_str(), ackTx.c_str());
                                }
                            } else if (respBody.startsWith("OK") || respBody.startsWith("ALREADY_PROCESSED")) {
                                ackValid = getSharedSecret().length() == 0; // see above: signed once a secret is set
                            }

                            if (ackValid) {
                                delivered = true;
                                if (!postWorkerAck(currentDevId, currentTxId)) {
                                    Serial.printf(
                                        "[AUTH WORKER] ACK for tx_id='%s' not queued; the box will offer it again.\n",
                                        currentTxId.c_str());
                                }
                            } else {
                                Serial.printf(
                                    "[AUTH WORKER] Payment ACK rejected due to mismatched recipient/signature (tx_id=%s)\n",
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
                Serial.printf("[AUTH WORKER] Delivery deferred after %d failed attempts: %s -> %s.\n", maxAttempts,
                              req.actionPath, ip.c_str());
            }

            vTaskDelay(pdMS_TO_TICKS(40)); // Prevent socket/radio contention
        }
    }
}

static inputsafety::LoginThrottle loginThrottle;

bool defaultCredentialsActive() {
    // True until the operator replaces the admin password that was generated for this box. Only the operator-controlled admin password counts. The super-admin password cannot be
    // changed on the box (it is published from the website), so counting it kept this warning on
    // permanently even after the operator had changed their password.
    return !adminPwChanged;
}

// Checks Basic-auth admin credentials with per-client throttling: five wrong passwords lock that
// client out for a minute. A request without an Authorization header is only the browser's first
// probe and is not counted. Returns true when authenticated; otherwise the caller still has to
// answer (unless a lockout response was already sent, see lockedOut).
static bool adminCredentialsOk(bool& lockedOut) {
    lockedOut = false;
    uint32_t client = (uint32_t)webServer.client().remoteIP();
    unsigned long now = millis();
    unsigned long waitMs = loginThrottle.lockedForMs(client, now);
    if (waitMs > 0) {
        lockedOut = true;
        webServer.sendHeader("Retry-After", String((waitMs + 999) / 1000));
        webServer.send(429, "text/plain", "Too many failed logins. Try again later.");
        return false;
    }
    if (superAdminBasicAuthOk() || webServer.authenticate("admin", webPassword.c_str())) {
        loginThrottle.recordSuccess(client);
        return true;
    }
    if (webServer.hasHeader("Authorization")) {
        loginThrottle.recordFailure(client, now);
        diagLog("[AUTH] Failed admin login from %s\n", webServer.client().remoteIP().toString().c_str());
        delay(250); // slows guessing without stalling the coin loop for long
    }
    return false;
}

bool checkAdminAuth() {
    // Strictly require administrator credentials.
    // Phone or controller telemetry signatures MUST NOT grant administrative access.
    bool lockedOut = false;
    if (adminCredentialsOk(lockedOut)) return true;
    if (!lockedOut) {
        webServer.requestAuthentication(BASIC_AUTH, "HARDWARE Admin Login",
                                        "Unauthorized: Admin credentials required.");
    }
    return false;
}

bool checkAuth() {
    // Check admin credentials first
    bool lockedOut = false;
    if (adminCredentialsOk(lockedOut)) return true;
    if (lockedOut) return false;
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
        totalCentavosLifetime = 0;
        totalCentavosSession = 0;
        lastSavedTotalCoins = 0;
        lastSavedTotalCentavos = 0;
        prefs.begin(NVS_NAMESPACE, false);
        prefs.putULong(NVS_KEY_TOTAL_COINS, 0);
        prefs.putULong(NVS_KEY_TOTAL_CENTAVOS, 0);
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
    <meta name="color-scheme" content="light dark">
    <title>Signed out - PisoPhone</title>
    <link rel="stylesheet" href="/assets/portal.css">
</head>
<body>
    <div class="center-page">
        <div class="card-narrow" style="text-align: center;">
            <h1>You are signed out</h1>
            <p class="muted">Your session on the PisoPhone coin box has ended.</p>
            <a href="/" class="btn primary block">Sign in again</a>
        </div>
    </div>
</body>
</html>
)HTML");
}
