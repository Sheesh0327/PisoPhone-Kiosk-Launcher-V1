#include "WebSocketServer.h"
#include "CoinSlotManager.h"
#include "ControllerWebSocket.h"
#include "WebServerModule.h"
#include "Config.h"
#include "HardwareManager.h"
#include "Security.h"
#include "DeviceManager.h"
#include "DeviceNetwork.h"
#include <WiFi.h>

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

void sendWsPong(WiFiClient& client, const uint8_t* payload, size_t len) {
    if (!client.connected()) return;
    uint8_t header[2];
    header[0] = 0x8A; // FIN + Pong frame
    header[1] = (uint8_t)(len & 0x7F);
    client.write(header, 2);
    if (len > 0 && payload != NULL) {
        client.write(payload, len);
    }
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

    unsigned long waitStart = millis();
    while (!client.available() && (millis() - waitStart < 100));
    if (!client.available()) return "";

    int b1 = client.read();
    if (b1 < 0) return "";
    bool isMasked = (b1 & 0x80) != 0;
    uint64_t payloadLen = (b1 & 0x7F);

    if (payloadLen == 126) {
        waitStart = millis();
        while (client.available() < 2 && (millis() - waitStart < 100));
        if (client.available() < 2) return "";
        payloadLen = (client.read() << 8) | client.read();
    } else if (payloadLen == 127) {
        waitStart = millis();
        while (client.available() < 8 && (millis() - waitStart < 100));
        if (client.available() < 8) return "";
        payloadLen = 0;
        for (int i = 0; i < 8; i++) {
            payloadLen = (payloadLen << 8) | client.read();
        }
    }

    uint8_t mask[4] = {0, 0, 0, 0};
    if (isMasked) {
        waitStart = millis();
        while (client.available() < 4 && (millis() - waitStart < 100));
        if (client.available() < 4) return "";
        client.read(mask, 4);
    }

    if (payloadLen > 2048) {
        return "";
    }

    uint8_t* payloadBuf = NULL;
    if (payloadLen > 0) {
        payloadBuf = (uint8_t*)malloc((size_t)payloadLen);
    }

    String result = "";
    result.reserve((size_t)payloadLen);
    for (size_t i = 0; i < payloadLen; i++) {
        if (!client.available()) {
            unsigned long wStart = millis();
            while (!client.available() && (millis() - wStart < 100));
            if (!client.available()) break;
        }
        uint8_t b = client.read();
        if (isMasked) {
            b ^= mask[i % 4];
        }
        if (payloadBuf) payloadBuf[i] = b;
        result += (char)b;
    }

    if (opcode == 0x09) { // Ping frame -> Respond with Pong (0x8A)
        sendWsPong(client, payloadBuf, (size_t)payloadLen);
        if (payloadBuf) free(payloadBuf);
        return "PING";
    }
    if (opcode == 0x0A) { // Pong frame
        if (payloadBuf) free(payloadBuf);
        return "PONG";
    }

    if (payloadBuf) free(payloadBuf);
    return result;
}

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
            // 1. Controller endpoints: /coinslot or /ws/coinslot (never registers to trackedDevices or pairing queue)
            if (reqPath == "/coinslot" || reqPath == "/ws/coinslot") {
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
            String expectedSig = calculateHMAC(reqDeviceId + ":" + tsStr, sharedSecret);
            if (!sig.equalsIgnoreCase(expectedSig)) {
                Serial.printf("[-] WS Auth Failed for %s: Signature Mismatch (sig=%s, expected=%s)\n", 
                    reqDeviceId.c_str(), sig.c_str(), expectedSig.c_str());
                newClient.print("HTTP/1.1 403 Forbidden\r\n\r\nInvalid Signature");
                newClient.stop();
                return;
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
                updateDeviceTelemetry(reqDeviceId, clientIp, 0, 0, 100, false, ts);
                wsSlotIdx = findSlotIndexForDevice(reqDeviceId, clientIp);
            }
            if (wsSlotIdx < 0) {
                Serial.printf("[-] WS Mutex Rejected for %s (%s): Device is not paired to any slot on this ESP32\n", 
                    reqDeviceId.c_str(), clientIp.c_str());
                newClient.print("HTTP/1.1 423 Locked\r\n\r\nSLOT_NOT_PAIRED");
                newClient.stop();
                return;
            }
            if (wsSlotIdx >= 0) {
                bool slotChanged = false;
                if (licenseSlots[wsSlotIdx].ip != clientIp && clientIp.length() > 0 && clientIp != "127.0.0.1") {
                    licenseSlots[wsSlotIdx].ip = clientIp;
                    slotChanged = true;
                }
                if (licenseSlots[wsSlotIdx].deviceId.length() == 0 || licenseSlots[wsSlotIdx].deviceId.startsWith("DEV_")) {
                    licenseSlots[wsSlotIdx].deviceId = reqDeviceId;
                    slotChanged = true;
                }
                if (slotChanged) {
                    saveSlotLicenses();
                }
            }
            bool wsIsActive = isSlotActive(wsSlotIdx);
            if (!wsIsActive) {
                Serial.printf("[-] WS Mutex Rejected for %s: Slot Expired / Lockdown Active (Slot #%d)\n", 
                    reqDeviceId.c_str(), licenseSlots[wsSlotIdx].slotNum);
                newClient.print("HTTP/1.1 423 Locked\r\n\r\nSLOT_EXPIRED");
                newClient.stop();
                return;
            }
            
            // 2. Hardware Mutex Check (Single-Client Lock)
            if (isCoinSlotBusy(reqDeviceId, CoinSlotOwnerType::PHONE)) {
                Serial.printf("[-] WS Mutex Rejected for %s: Slot BUSY with %s\n", reqDeviceId.c_str(), getActiveCoinSessionId().c_str());
                newClient.print("HTTP/1.1 409 Conflict\r\n\r\nSLOT_BUSY");
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
                [](const String& devId, int pulses) {
                    triggerUniversalCoinEvent(pulses, devId);
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
