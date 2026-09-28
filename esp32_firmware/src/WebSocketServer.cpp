#include "WebSocketServer.h"
#include "CoinSlotManager.h"
#include "ControllerWebSocket.h"
#include "PaymentQueueManager.h"
#include "WebServerModule.h"
#include "Config.h"
#include "HardwareManager.h"
#include "Security.h"
#include "DeviceManager.h"
#include "DeviceNetwork.h"
#include <ArduinoJson.h>
#include <WiFi.h>
#include <WiFiUdp.h>

void processWebSocketServer() {
    if (wsServer.hasClient()) {
        WiFiClient newClient = wsServer.available();
        if (newClient) {
            newClient.setTimeout(500);
            
            String request = "";
            String secKey = "";
            
            unsigned long hsStart = millis();
            while (newClient.connected() && (millis() - hsStart < 2000)) {
                if (newClient.available()) {
                    String line = newClient.readStringUntil('\n');
                    if (request.length() == 0) request = line;
                    line.trim();
                    String lowerLine = line;
                    lowerLine.toLowerCase();
                    if (lowerLine.startsWith("sec-websocket-key:")) {
                        secKey = line.substring(18);
                        secKey.trim();
                    }
                    if (line.length() == 0) break; // Blank line signals end of headers
                } else {
                    delay(5);
                }
            }
            
            // Extract URI Path from HTTP Request line (e.g., "GET /ws/coinslot?session_id=... HTTP/1.1")
            String reqPath = "";
            int firstSpace = request.indexOf(' ');
            if (firstSpace != -1) {
                int secondSpace = request.indexOf(' ', firstSpace + 1);
                String fullUri = (secondSpace != -1) ? request.substring(firstSpace + 1, secondSpace) : request.substring(firstSpace + 1);
                int qMark = fullUri.indexOf('?');
                reqPath = (qMark != -1) ? fullUri.substring(0, qMark) : fullUri;
            }
            reqPath.trim();

            // Strict Endpoint Routing:
            // 1. Controller endpoints: /coinslot or /ws/coinslot, or query requests targeting coinslot only
            bool isCoinslotRequest = (reqPath == "/coinslot" || reqPath == "/ws/coinslot") ||
                                     request.indexOf("mode=coinslot") >= 0 ||
                                     request.indexOf("coinslot=1") >= 0 ||
                                     request.indexOf("coinslot=true") >= 0 ||
                                     request.indexOf("type=controller") >= 0 ||
                                     request.indexOf("client=controller") >= 0 ||
                                     request.indexOf("client=coinslot") >= 0 ||
                                     request.indexOf("op_kind=5") >= 0 ||
                                     (request.indexOf("session_id=") >= 0 && request.indexOf("source=app") == -1);
            if (isCoinslotRequest) {
                handleControllerWebSocketHandshake(newClient, request, secKey);
                return;
            }

            // 2. PisoPhone Terminal endpoints: must target /ws (or /ws/terminal)
            if (reqPath != "/ws" && reqPath != "/ws/terminal" && reqPath != "/") {
                Serial.printf("[-] WS Rejected: Unknown endpoint '%s'\n", reqPath.c_str());
                newClient.print("HTTP/1.1 404 Not Found\r\n\r\nInvalid WebSocket Endpoint");
                newClient.stop();
                return;
            }

            String reqDeviceId = extractUrlParam(request, "device_id=");
            String tsStr = extractUrlParam(request, "ts=");
            String sig = extractUrlParam(request, "sig=");
            reqDeviceId.trim();
            tsStr.trim();
            sig.trim();
            
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
                bool isAppReq = (request.indexOf("source=app") >= 0 || request.indexOf("client=pisophone_app") >= 0 || request.indexOf("app=1") >= 0);
                if (isAppReq) {
                    updateDynamicDeviceList(reqDeviceId, clientIp);
                }
                Serial.printf("[-] WS Mutex Rejected for %s (%s): Device is not paired to any slot on this ESP32\n", 
                    reqDeviceId.c_str(), clientIp.c_str());
                newClient.print("HTTP/1.1 423 Locked\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: 29\r\n\r\n{\"error\":\"SLOT_NOT_PAIRED\"}");
                newClient.flush();
                delay(10);
                newClient.stop();
                return;
            }
            bool wsIsActive = isSlotActive(wsSlotIdx);
            if (!wsIsActive) {
                Serial.printf("[-] WS Mutex Rejected for %s: Slot Expired / Lockdown Active (Slot #%d)\n", 
                    reqDeviceId.c_str(), licenseSlots[wsSlotIdx].slotNum);
                newClient.print("HTTP/1.1 423 Locked\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: 26\r\n\r\n{\"error\":\"SLOT_EXPIRED\"}");
                newClient.flush();
                delay(10);
                newClient.stop();
                return;
            }
            
            // 2. Hardware Mutex Check & Atomic Arming Claim (Prevents TOCTOU race)
            if (!tryClaimCoinSlotForArming(reqDeviceId, CoinSlotOwnerType::PHONE, 5000)) {
                Serial.printf("[-] WS Mutex Rejected for %s: Slot BUSY with %s\n", reqDeviceId.c_str(), getActiveCoinSessionId().c_str());
                newClient.print("HTTP/1.1 409 Conflict\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: 23\r\n\r\n{\"event\":\"SLOT_BUSY\"}");
                newClient.flush();
                delay(10);
                newClient.stop();
                return;
            }
            
            // Mutex passed, slot acquired. Save the nonce and synchronize master clock.
            recordDeviceNonce(reqDeviceId, ts);
            if (ts > 0) updateMasterTime(ts);
            
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
            wsSessionDeviceId = reqDeviceId;
            
            bool reserved = reserveCoinSlot(reqDeviceId, CoinSlotOwnerType::PHONE, ARM_TTL,
                [](const String& devId, int pulses) -> bool {
                    return triggerUniversalCoinEvent(pulses, devId);
                },
                [](const String& devId, const char* reason) {
                    if (isWsConnected && wsClient.connected()) {
                        if (strcmp(reason, "TTL_EXPIRED") == 0 || strcmp(reason, "MAX_DURATION") == 0) {
                            sendWsText(wsClient, "{\"event\":\"TIMEOUT\"}");
                        }
                        wsClient.stop();
                    }
                    isWsConnected = false;
                    wsSessionDeviceId = "";
                }
            );

            if (!reserved) {
                cancelCoinSlotClaim(reqDeviceId, CoinSlotOwnerType::PHONE);
                sendWsText(wsClient, "{\"event\":\"ERROR\",\"reason\":\"SLOT_UNAVAILABLE\"}");
                wsClient.stop();
                isWsConnected = false;
                wsSessionDeviceId = "";
                Serial.printf("[-] WS reservation failed for %s after handshake.\n", reqDeviceId.c_str());
                return;
            }
            
            Serial.printf("[⚡ WS Port 81] WebSocket ARMED securely for %s (TTL: %lu s)\n", reqDeviceId.c_str(), ARM_TTL / 1000);
            sendWsText(wsClient, "{\"event\":\"ARMED\"}");
        }
    }
    
    // Process active WebSocket client frames or disconnection
    if (isWsConnected) {
        String boundDevId = wsSessionDeviceId;
        if (!wsClient.connected()) {
            Serial.printf("[*] WS Client %s disconnected. Releasing slot.\n", boundDevId.c_str());
            isWsConnected = false;
            wsSessionDeviceId = "";
            releaseCoinSlot(boundDevId, CoinSlotOwnerType::PHONE, false);
            return;
        }
        
        if (wsClient.available()) {
            String frameText = readWsText(wsClient);
            if (frameText.length() > 0) {
                refreshCoinSlotTtl(boundDevId, CoinSlotOwnerType::PHONE, ARM_TTL);

                StaticJsonDocument<512> ackDoc;
                DeserializationError ackErr = deserializeJson(ackDoc, frameText);
                if (!ackErr && String(ackDoc["event"] | "") == "ACK") {
                    bool ackValid = true;
                    String ackDevId = "";
                    String ackTxId = "";
                    String ackSig = "";
                    String ackTs = "";
                    int amountVal = 0;
                    int secondsVal = 0;
                    String statusVal = "";

                    // Validate all required fields
                    if (!ackDoc["device_id"].is<const char*>() || !ackDoc["tx_id"].is<const char*>() ||
                        !ackDoc["v_sig"].is<const char*>() || !ackDoc["ts"].is<const char*>() ||
                        !ackDoc["status"].is<const char*>() || !ackDoc["amount"].is<int>() ||
                        !ackDoc["seconds"].is<int>()) {
                        Serial.println("[⚡ WS Port 81] Rejected ACK: Missing or invalid JSON types");
                        ackValid = false;
                    } else {
                        ackDevId = ackDoc["device_id"].as<String>();
                        ackTxId = ackDoc["tx_id"].as<String>();
                        ackSig = ackDoc["v_sig"].as<String>();
                        ackTs = ackDoc["ts"].as<String>();
                        amountVal = ackDoc["amount"].as<int>();
                        secondsVal = ackDoc["seconds"].as<int>();
                        statusVal = ackDoc["status"].as<String>();

                        if (ackDevId.length() == 0 || ackTxId.length() == 0 || ackSig.length() == 0 || ackTs.length() == 0) {
                            Serial.println("[⚡ WS Port 81] Rejected ACK: Empty string fields");
                            ackValid = false;
                        } else if (statusVal != "OK" && statusVal != "ALREADY_PROCESSED") {
                            Serial.printf("[⚡ WS Port 81] Rejected ACK: Invalid status '%s'\n", statusVal.c_str());
                            ackValid = false;
                        } else if (ackDevId != boundDevId) {
                            Serial.printf("[⚡ WS Port 81] Rejected ACK: dev='%s' vs bound='%s'\n", ackDevId.c_str(), boundDevId.c_str());
                            ackValid = false;
                        }
                    }

                    if (ackValid) {
                        if (verifyAckSignature(ackDevId, ackTxId, amountVal, secondsVal, ackTs, statusVal, ackSig, sharedSecret)) {
                            if (acknowledgePhonePayment(ackDevId, ackTxId, amountVal, secondsVal, statusVal)) {
                                Serial.printf("[⚡ WS Port 81] Durable phone ACK accepted for tx_id='%s' (device: %s)\n",
                                              ackTxId.c_str(), ackDevId.c_str());
                            } else {
                                // Not in queue (e.g. 0-pulse adjustment or already acknowledged); confirm manual adjustment
                                recordAdjustmentConfirmed(ackTxId, ackDevId, secondsVal);
                                Serial.printf("[⚡ WS Port 81] Verified adjustment ACK confirmed for tx_id='%s' (device: %s, seconds: %d)\n",
                                              ackTxId.c_str(), ackDevId.c_str(), secondsVal);
                            }
                        } else {
                            Serial.printf("[⚡ WS Port 81] Rejected ACK for '%s': Invalid signature\n", ackTxId.c_str());
                        }
                    }
                }
            }
            if (frameText == "DONE" || frameText == "CLOSE") {
                Serial.printf("[⚡ WS Port 81] 'DONE' received for %s. Requesting slot release.\n", boundDevId.c_str());
                releaseCoinSlot(boundDevId, CoinSlotOwnerType::PHONE, false);
                return;
            }
        } else {
            // Actively ping client every 3 seconds to detect socket disconnect promptly
            static unsigned long lastPhoneWsPingMs = 0;
            if (millis() - lastPhoneWsPingMs >= 3000) {
                lastPhoneWsPingMs = millis();
                uint8_t pingFrame[2] = {0x89, 0x00};
                if (wsClient.write(pingFrame, 2) != 2) {
                    Serial.printf("[*] WS Client %s ping write failed. Releasing.\n", boundDevId.c_str());
                    wsClient.stop();
                    isWsConnected = false;
                    wsSessionDeviceId = "";
                    releaseCoinSlot(boundDevId, CoinSlotOwnerType::PHONE, false);
                    return;
                }
            }
        }
    }

    // Process active Controller WebSocket connection
    processControllerWebSocket();
}

void processSerialCli() {
    if (!Serial.available()) return;
    String line = Serial.readStringUntil('\n');
    line.trim();
    if (line.length() == 0) return;

    if (line.startsWith("add ") || line.startsWith("add")) {
        int firstSpace = line.indexOf(' ');
        if (firstSpace != -1) {
            int minutes = line.substring(firstSpace + 1).toInt();
            sendAddTime(minutes, "ALL");
        }
    } else if (line.equalsIgnoreCase("help")) {
        Serial.println("\nCommands: add <minutes> | help");
    }
}
