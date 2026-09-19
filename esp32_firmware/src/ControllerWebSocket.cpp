#include "ControllerWebSocket.h"
#include "CoinSlotManager.h"
#include "PaymentQueueManager.h"
#include "Config.h"
#include "DeviceManager.h"
#include "Security.h"
#include "WebSocketsUdp.h"
#include <ArduinoJson.h>

// Controller connection state
static WiFiClient controllerClient;
static bool controllerConnected = false;
static String controllerSessionId = "";
static bool controllerSessionEnding = false;
static unsigned long controllerCloseAfterMs = 0;

bool isControllerWsConnected() {
    return controllerConnected && controllerClient.connected();
}

String getControllerSessionId() {
    return controllerSessionId;
}

bool sendControllerPaymentEvent(const String& sessionId, const String& txId, int pulses) {
    if (!controllerConnected || controllerSessionEnding || !controllerClient.connected() ||
        controllerSessionId != sessionId || txId.length() == 0 || pulses <= 0) {
        return false;
    }

    String json = "{\"event\":\"COIN_DETECTED\",\"session_id\":\"" + sessionId +
                  "\",\"pulses\":" + String(pulses) +
                  ",\"tx_id\":\"" + txId + "\"}";
    sendWsText(controllerClient, json);
    return controllerClient.connected();
}

static String getControllerCredential() {
    return (sharedSecret.length() > 0) ? sharedSecret : String(MASTER_CRYPTO_SECRET);
}

bool handleControllerWebSocketHandshake(WiFiClient& client, const String& request, const String& secKey) {
    String sessionId = extractUrlParam(request, "session_id=");
    if (sessionId.length() == 0) {
        sessionId = extractUrlParam(request, "device_id=");
    }
    String tsStr = extractUrlParam(request, "ts=");
    String sig = extractUrlParam(request, "sig=");
    
    sessionId.trim();
    tsStr.trim();
    sig.trim();

    // 1. Parameter presence check
    if (sessionId.length() == 0 || tsStr.length() == 0 || sig.length() == 0 || secKey.length() == 0) {
        Serial.println("[-] Controller WS: Missing session_id/device_id, ts, sig, or secKey");
        client.print("HTTP/1.1 400 Bad Request\r\nContent-Type: application/json\r\n\r\n{\"error\":\"MISSING_AUTH_PARAMS\"}");
        client.stop();
        return false;
    }

    // 2. Controller HMAC Authentication (Single authoritative credential check)
    String cred = getControllerCredential();
    String payload = sessionId + ":" + tsStr;
    String expectedSig = calculateHMAC(payload, cred);

    if (!sig.equalsIgnoreCase(expectedSig)) {
        Serial.printf("[-] Controller WS Auth Failed for session '%s': Signature Mismatch\n", sessionId.c_str());
        client.print("HTTP/1.1 403 Forbidden\r\nContent-Type: application/json\r\n\r\n{\"error\":\"INVALID_SIGNATURE\"}");
        client.stop();
        return false;
    }

    // 3. Replay Protection (Isolated to controllers - does not pollute PisoPhone trackedDevices)
    unsigned long long ts = strtoull(tsStr.c_str(), NULL, 10);
    static unsigned long long lastControllerNonceTs = 0;
    unsigned long long currentMasterTs = getCurrentMasterTimeMs();
    if (currentMasterTs > 300000ULL) {
        if (ts < (currentMasterTs - 300000ULL) || ts > (currentMasterTs + 300000ULL)) {
            Serial.printf("[-] Controller WS Auth Failed: Timestamp out of master window (ts=%llu)\n", ts);
            client.print("HTTP/1.1 403 Forbidden\r\nContent-Type: application/json\r\n\r\n{\"error\":\"TIMESTAMP_OUT_OF_WINDOW\"}");
            client.stop();
            return false;
        }
    }
    if (lastControllerNonceTs > 30000ULL && ts + 30000ULL < lastControllerNonceTs) {
        Serial.printf("[-] Controller WS Auth Failed for session '%s': Replay Detected (ts=%llu)\n", sessionId.c_str(), ts);
        client.print("HTTP/1.1 403 Forbidden\r\nContent-Type: application/json\r\n\r\n{\"error\":\"REPLAY_DETECTED\"}");
        client.stop();
        return false;
    }
    if (ts > lastControllerNonceTs) lastControllerNonceTs = ts;
    if (ts > 0) updateMasterTime(ts);

    // Allow the previous controller a short interval to acknowledge the final coin.
    if (controllerSessionEnding && controllerClient.connected()) {
        client.print("HTTP/1.1 409 Conflict\r\nContent-Type: application/json\r\n\r\n{\"event\":\"BUSY\",\"reason\":\"FINAL_ACK_PENDING\"}");
        client.stop();
        return false;
    }

    // 4. Shared Single-Session Mutex Check & Atomic Arming Claim (CoinSlotManager)
    if (!tryClaimCoinSlotForArming(sessionId, CoinSlotOwnerType::CONTROLLER, 5000)) {
        String activeOwner = getActiveCoinSessionId();
        Serial.printf("[-] Controller WS Mutex Rejected for '%s': Slot BUSY with '%s'\n", 
                      sessionId.c_str(), activeOwner.c_str());
        client.print("HTTP/1.1 409 Conflict\r\nContent-Type: application/json\r\n\r\n{\"event\":\"BUSY\",\"session_id\":\"" + sessionId + "\",\"busy_with\":\"" + activeOwner + "\"}");
        client.stop();
        return false;
    }

    // 5. Upgrade to RFC6455 WebSocket
    String acceptKey = computeSecWebSocketAccept(secKey);
    String response = "HTTP/1.1 101 Switching Protocols\r\n";
    response += "Upgrade: websocket\r\n";
    response += "Connection: Upgrade\r\n";
    response += "Sec-WebSocket-Accept: " + acceptKey + "\r\n\r\n";

    client.print(response);
    client.flush();
    client.setNoDelay(true);

    if (controllerConnected && controllerClient.connected()) {
        controllerClient.stop();
    }
    controllerClient = client;
    controllerConnected = true;
    controllerSessionId = sessionId;
    controllerSessionEnding = false;
    controllerCloseAfterMs = 0;

    // 6. Reserve Coin Slot and bind session-isolated callbacks
    bool ok = reserveCoinSlot(sessionId, CoinSlotOwnerType::CONTROLLER, ARM_TTL,
        // onPayment Callback (Pure pulses, no PisoPhone pricing or routing)
        [](const String& sessId, int pulses) {
            unsigned long long eventTs = (unsigned long long)getCurrentMasterTimeMs();
            String txId = "tx-" + String(eventTs) + "-" + String(random(10000, 99999));
            
            bool retained = enqueuePendingPayment(
                txId, sessId, pulses, CoinSlotOwnerType::CONTROLLER);
            if (!retained) {
                Serial.printf("[CONTROLLER WS] CRITICAL: Could not retain tx_id='%s'.\n",
                              txId.c_str());
            }

            if (sendControllerPaymentEvent(sessId, txId, pulses)) {
                Serial.printf("[⚡ CONTROLLER WS] Dispatching %d pulse(s) to session '%s' (tx_id=%s)\n", 
                              pulses, sessId.c_str(), txId.c_str());
            }
        },
        // onSessionEnd Callback (Session ended/timeout/released/drained)
        [](const String& sessId, const char* reason) {
            if (controllerConnected && controllerClient.connected()) {
                String endReason = (reason != nullptr && strlen(reason) > 0) ? String(reason) : "RELEASED";
                String json = "{\"event\":\"SESSION_ENDED\",\"session_id\":\"" + sessId + 
                              "\",\"reason\":\"" + endReason + "\"}";
                Serial.printf("[⚡ CONTROLLER WS] Session ended for '%s' (Reason: %s)\n", 
                              sessId.c_str(), endReason.c_str());
                sendWsText(controllerClient, json);
                controllerClient.flush();
                controllerSessionEnding = true;
                controllerCloseAfterMs = millis() + 2000;
                return;
            }
            controllerConnected = false;
            controllerSessionId = "";
            controllerSessionEnding = false;
            controllerCloseAfterMs = 0;
        }
    );

    if (!ok) {
        cancelCoinSlotClaim(sessionId, CoinSlotOwnerType::CONTROLLER);
        Serial.printf("[-] Controller WS failed to reserve slot for '%s'\n", sessionId.c_str());
        sendWsText(controllerClient, "{\"event\":\"BUSY\",\"session_id\":\"" + sessionId + "\"}");
        controllerClient.stop();
        controllerConnected = false;
        controllerSessionId = "";
        controllerSessionEnding = false;
        controllerCloseAfterMs = 0;
        return false;
    }

    Serial.printf("[⚡ CONTROLLER WS] ARMED successfully for session '%s' (TTL: %lu s)\n", 
                  sessionId.c_str(), ARM_TTL / 1000);
    sendWsText(controllerClient, "{\"event\":\"ARMED\",\"session_id\":\"" + sessionId + "\"}");
    dispatchPendingControllerPayments(sessionId);
    return true;
}

void processControllerWebSocket() {
    if (!controllerConnected) return;

    String boundSessionId = controllerSessionId;

    if (!controllerClient.connected()) {
        Serial.printf("[*] Controller WS Client '%s' disconnected. Releasing slot.\n", boundSessionId.c_str());
        controllerClient.stop();
        controllerConnected = false;
        controllerSessionId = "";
        controllerSessionEnding = false;
        controllerCloseAfterMs = 0;
        releaseCoinSlot(boundSessionId, CoinSlotOwnerType::CONTROLLER, false, "DISCONNECTED");
        return;
    }

    if (controllerClient.available()) {
        String frameText = readWsText(controllerClient);
        if (frameText.length() > 0) {
            if (!controllerSessionEnding && frameText != "CLOSE" && frameText != "DONE") {
                refreshCoinSlotTtl(boundSessionId, CoinSlotOwnerType::CONTROLLER, ARM_TTL);
            }

            StaticJsonDocument<256> ackDoc;
            DeserializationError ackError = deserializeJson(ackDoc, frameText);
            if (!ackError && String(ackDoc["event"] | "") == "ACK") {
                String ackSessionId = String(ackDoc["session_id"] | "");
                String ackTxId = String(ackDoc["tx_id"] | "");
                if (ackSessionId == boundSessionId &&
                    acknowledgeControllerPayment(boundSessionId, ackTxId)) {
                    Serial.printf("[CONTROLLER WS] Durable ACK accepted for tx_id='%s'.\n",
                                  ackTxId.c_str());
                } else {
                    Serial.println("[CONTROLLER WS] Rejected unmatched payment ACK.");
                }
            }
        }
        if (controllerSessionEnding) {
            if ((long)(millis() - controllerCloseAfterMs) >= 0) {
                controllerClient.stop();
                controllerConnected = false;
                controllerSessionId = "";
                controllerSessionEnding = false;
                controllerCloseAfterMs = 0;
            }
            return;
        }
        if (frameText == "DONE" || frameText == "CLOSE") {
            Serial.printf("[⚡ CONTROLLER WS] '%s' received for '%s'. Releasing slot and draining.\n", 
                          frameText.c_str(), boundSessionId.c_str());
            controllerSessionEnding = true;
            controllerCloseAfterMs = millis() + 500;
            releaseCoinSlot(boundSessionId, CoinSlotOwnerType::CONTROLLER, false, "CLIENT_CLOSED");
            return;
        }
    } else if (controllerSessionEnding) {
        if ((long)(millis() - controllerCloseAfterMs) >= 0) {
            controllerClient.stop();
            controllerConnected = false;
            controllerSessionId = "";
            controllerSessionEnding = false;
            controllerCloseAfterMs = 0;
        }
    } else {
        // Active ping probe every 3s to detect abrupt client termination
        static unsigned long lastCtrlPingMs = 0;
        if (millis() - lastCtrlPingMs >= 3000) {
            lastCtrlPingMs = millis();
            uint8_t pingFrame[2] = {0x89, 0x00};
            if (controllerClient.write(pingFrame, 2) != 2) {
                Serial.printf("[*] Controller WS Client '%s' ping write failed. Releasing.\n", boundSessionId.c_str());
                controllerClient.stop();
                controllerConnected = false;
                controllerSessionId = "";
                controllerSessionEnding = false;
                controllerCloseAfterMs = 0;
                releaseCoinSlot(boundSessionId, CoinSlotOwnerType::CONTROLLER, false, "SOCKET_DEAD");
                return;
            }
        }
    }
}
