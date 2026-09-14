#include "ControllerWebSocket.h"
#include "CoinSlotManager.h"
#include "Config.h"
#include "DeviceManager.h"
#include "Security.h"
#include "WebSocketsUdp.h"

// Controller connection state
static WiFiClient controllerClient;
static bool controllerConnected = false;
static String controllerSessionId = "";

bool isControllerWsConnected() {
    return controllerConnected && controllerClient.connected();
}

String getControllerSessionId() {
    return controllerSessionId;
}

static String getControllerCredential() {
    return (sharedSecret.length() > 0) ? sharedSecret : String(MASTER_CRYPTO_SECRET);
}

bool handleControllerWebSocketHandshake(WiFiClient& client, const String& request, const String& secKey) {
    String sessionId = extractUrlParam(request, "session_id=");
    String tsStr = extractUrlParam(request, "ts=");
    String sig = extractUrlParam(request, "sig=");
    
    sessionId.trim();
    tsStr.trim();
    sig.trim();

    // 1. Parameter presence check
    if (sessionId.length() == 0 || tsStr.length() == 0 || sig.length() == 0 || secKey.length() == 0) {
        Serial.println("[-] Controller WS: Missing session_id, ts, sig, or secKey");
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

    // 3. Replay Protection & Monotonic Timestamp Verification
    unsigned long long ts = strtoull(tsStr.c_str(), NULL, 10);
    if (!checkReplayProtection(sessionId, ts)) {
        Serial.printf("[-] Controller WS Auth Failed for session '%s': Replay Detected (ts=%llu)\n", sessionId.c_str(), ts);
        client.print("HTTP/1.1 403 Forbidden\r\nContent-Type: application/json\r\n\r\n{\"error\":\"REPLAY_DETECTED\"}");
        client.stop();
        return false;
    }
    recordDeviceNonce(sessionId, ts);
    if (ts > 0) updateMasterTime(ts);

    // 4. Shared Single-Session Mutex Check (CoinSlotManager)
    if (isCoinSlotBusy(sessionId)) {
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

    // 6. Reserve Coin Slot and bind session-isolated callbacks
    bool ok = reserveCoinSlot(sessionId, ARM_TTL,
        // onPayment Callback (Pure pulses, no PisoPhone pricing or routing)
        [](const String& sessId, int pulses) {
            if (controllerConnected && controllerClient.connected()) {
                unsigned long long eventTs = (unsigned long long)getCurrentMasterTimeMs();
                String txId = "tx-" + String(eventTs) + "-" + String(random(10000, 99999));
                
                String json = "{\"event\":\"COIN_DETECTED\",\"session_id\":\"" + sessId + 
                              "\",\"pulses\":" + String(pulses) + 
                              ",\"tx_id\":\"" + txId + "\"}";
                Serial.printf("[⚡ CONTROLLER WS] Dispatching %d pulse(s) to session '%s' (tx_id=%s)\n", 
                              pulses, sessId.c_str(), txId.c_str());
                sendWsText(controllerClient, json);
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
                controllerClient.stop();
            }
            controllerConnected = false;
            controllerSessionId = "";
        }
    );

    if (!ok) {
        Serial.printf("[-] Controller WS failed to reserve slot for '%s'\n", sessionId.c_str());
        sendWsText(controllerClient, "{\"event\":\"BUSY\",\"session_id\":\"" + sessionId + "\"}");
        controllerClient.stop();
        controllerConnected = false;
        controllerSessionId = "";
        return false;
    }

    Serial.printf("[⚡ CONTROLLER WS] ARMED successfully for session '%s' (TTL: %lu s)\n", 
                  sessionId.c_str(), ARM_TTL / 1000);
    sendWsText(controllerClient, "{\"event\":\"ARMED\",\"session_id\":\"" + sessionId + "\"}");
    return true;
}

void processControllerWebSocket() {
    if (!controllerConnected) return;

    String boundSessionId = controllerSessionId;

    if (!controllerClient.connected()) {
        Serial.printf("[*] Controller WS Client '%s' disconnected. Releasing slot.\n", boundSessionId.c_str());
        controllerConnected = false;
        controllerSessionId = "";
        releaseCoinSlot(boundSessionId, false);
        return;
    }

    if (controllerClient.available()) {
        String frameText = readWsText(controllerClient);
        if (frameText.length() > 0) {
            refreshCoinSlotTtl(boundSessionId, ARM_TTL);
        }
        if (frameText == "DONE" || frameText == "CLOSE") {
            Serial.printf("[⚡ CONTROLLER WS] '%s' received for '%s'. Initiating release lifecycle without premature socket abort.\n", 
                          frameText.c_str(), boundSessionId.c_str());
            // Release slot through CoinSlotManager. If pulses are draining, socket stays open
            // until all pulses are credited and onSessionEnd fires SESSION_ENDED.
            releaseCoinSlot(boundSessionId, false);
            return;
        }
    } else {
        // Keep session armed while controller client socket remains open and connected
        refreshCoinSlotTtl(boundSessionId, ARM_TTL);
    }
}
