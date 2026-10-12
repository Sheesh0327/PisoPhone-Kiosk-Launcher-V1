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
#include "SuperAdminSession.h"
#include "WebAssetServer.h"
#include "RevenueVault.h"
#include "RevenueLedger.h"

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
    // True until the operator replaces the admin password that was generated for this box.
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
    if (superAdminSessionOk() || webServer.authenticate("admin", webPassword.c_str())) {
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

unsigned long adminLoginLockedMs() {
    return loginThrottle.lockedForMs((uint32_t)webServer.client().remoteIP(), millis());
}
void adminLoginRecordFailure() {
    loginThrottle.recordFailure((uint32_t)webServer.client().remoteIP(), millis());
}
void adminLoginRecordSuccess() {
    loginThrottle.recordSuccess((uint32_t)webServer.client().remoteIP());
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

// Where to go after signing in: the page that was asked for, only ever on this box ("/..." and not "//...").
static String safeNext(const String& next) {
    if (next.length() == 0 || next[0] != '/' || (next.length() > 1 && next[1] == '/') || next.indexOf('\\') >= 0 ||
        next.indexOf('\r') >= 0 || next.indexOf('\n') >= 0) {
        return "/";
    }
    return next;
}

static String htmlEscape(const String& in) {
    String out;
    for (size_t i = 0; i < in.length(); i++) {
        char c = in[i];
        if (c == '&')
            out += "&amp;";
        else if (c == '<')
            out += "&lt;";
        else if (c == '>')
            out += "&gt;";
        else if (c == '"')
            out += "&quot;";
        else if (c == '\'')
            out += "&#39;";
        else
            out += c;
    }
    return out;
}

// The page that was asked for, with its query (a phone pairing link carries one), so signing in leads back to it.
static String requestedPage() {
    String page = webServer.uri();
    for (int i = 0; i < webServer.args(); i++) {
        page += (i == 0 ? "?" : "&") + urlEncode(webServer.argName(i)) + "=" + urlEncode(webServer.arg(i));
    }
    return page;
}

static void sendLoginPage() {
    String next = htmlEscape(safeNext(requestedPage()));
    String page = F("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">"
                    "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
                    "<meta name=\"color-scheme\" content=\"light dark\"><title>Sign in - PisoPhone</title>"
                    "<link rel=\"stylesheet\" href=\"/assets/portal.css\"></head><body data-next=\"");
    page += next;
    page += F("\"><div class=\"center-page\"><div class=\"card-narrow\" style=\"text-align:center\">"
              "<h1>PisoPhone admin</h1><p class=\"muted\">Sign in to manage this coin box.</p>"
              "<p><a class=\"btn primary\" id=\"pw\" style=\"display:block\" href=\"/signin?next=");
    page += urlEncode(safeNext(requestedPage()));
    page += F("\">Sign in with the admin password</a></p>"
              "<p><label class=\"btn\" for=\"qr\" style=\"display:block\">Scan admin QR</label>"
              "<input id=\"qr\" type=\"file\" accept=\"image/*\" capture=\"environment\" hidden></p>"
              "<p id=\"msg\" class=\"muted\"></p>"
              "<p class=\"muted small\">Scan opens the camera: photograph your admin QR code.</p></div></div>"
              "<script src=\"/assets/jsqr.js?v=");
    page += webAssetVersion("jsqr.js");
    page += F("\"></script><script src=\"/assets/adminqr.js?v=");
    page += webAssetVersion("adminqr.js");
    page += F(
        "\"></script><script>PisoAdminQr.bindLoginPage(document.getElementById('qr'), document.getElementById('msg'));"
        "</script></body></html>");
    webServer.sendHeader("Cache-Control", "no-store");
    webServer.send(200, "text/html; charset=utf-8", page);
}

bool checkAdminAuthOrLoginPage() {
    bool lockedOut = false;
    if (adminCredentialsOk(lockedOut)) return true;
    if (lockedOut) return false;
    if (webServer.hasHeader("Authorization")) { // a wrong password: the browser's own prompt again
        webServer.requestAuthentication(BASIC_AUTH, "HARDWARE Admin Login",
                                        "Unauthorized: Admin credentials required.");
        return false;
    }
    sendLoginPage();
    return false;
}

void handleSignIn() {
    if (!checkAdminAuth()) return;
    webServer.sendHeader("Location", safeNext(webServer.arg("next")));
    webServer.send(303);
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
    // Signing out ends the vendor's collection, but only when the request really is the vendor's: /logout is a plain GET that
    // anyone (or a link prefetch) can send, and it must not be able to end someone else's collection window.
    if (isVaultUnmasked && superAdminSessionOk()) {
        vaultCollect(revenue::SUPERADMIN_LOGOUT);
        isVaultUnmasked = false;
        unmaskExpiryTimestamp = 0;
        Serial.println("[👑 SUPER ADMIN] Collection recorded at Super Admin logout.");
    }
    superAdminSessionEnd();
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
