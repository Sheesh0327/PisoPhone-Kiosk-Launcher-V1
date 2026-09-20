#include "WebSocketsUdp.h"
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

static bool readExactBytes(WiFiClient& client, uint8_t* buf, size_t count, unsigned long deadlineMs) {
    size_t readCount = 0;
    unsigned long start = millis();
    while (readCount < count) {
        if (!client.connected()) return false;
        int avail = client.available();
        if (avail > 0) {
            int toRead = (int)min((size_t)avail, count - readCount);
            int bytesRead = client.read(buf + readCount, toRead);
            if (bytesRead <= 0) return false;
            readCount += bytesRead;
        } else {
            if ((long)(millis() - (start + deadlineMs)) >= 0) {
                return false;
            }
            delay(1);
        }
    }
    return true;
}

String readWsText(WiFiClient& client) {
    if (!client.available()) return "";

    const unsigned long FRAME_DEADLINE_MS = 300;
    const size_t MAX_WS_MESSAGE_SIZE = 2048;
    String assembledText = "";
    bool inFragmentedMessage = false;

    unsigned long overallStart = millis();

    auto getRemainingDeadlineMs = [overallStart, FRAME_DEADLINE_MS]() -> unsigned long {
        unsigned long elapsed = millis() - overallStart;
        if (elapsed >= FRAME_DEADLINE_MS) return 0;
        return FRAME_DEADLINE_MS - elapsed;
    };

    while (client.connected()) {
        unsigned long remainingMs = getRemainingDeadlineMs();
        if (remainingMs == 0) {
            Serial.println("[WS] Strict frame deadline exceeded. Dropping connection.");
            client.stop();
            return "";
        }

        if (!client.available()) {
            if (!inFragmentedMessage) {
                return "";
            }
            delay(2);
            continue;
        }

        uint8_t header[2];
        if (!readExactBytes(client, header, 2, remainingMs)) {
            Serial.println("[WS] Failed to read frame header within deadline. Dropping.");
            client.stop();
            return "";
        }

        bool fin = (header[0] & 0x80) != 0;
        uint8_t rsv = header[0] & 0x70;
        uint8_t opcode = header[0] & 0x0F;
        bool isMasked = (header[1] & 0x80) != 0;
        uint64_t payloadLen = header[1] & 0x7F;

        // RFC 6455: RSV bits must be 0 unless negotiated
        if (rsv != 0) {
            Serial.println("[WS] Protocol error: non-zero RSV bits. Dropping.");
            client.stop();
            return "";
        }

        // Client-to-server frames MUST be masked
        if (!isMasked) {
            Serial.println("[WS] Protocol error: unmasked client frame. Dropping.");
            client.stop();
            return "";
        }

        // Extended payload length parsing
        if (payloadLen == 126) {
            uint8_t extLen[2];
            remainingMs = getRemainingDeadlineMs();
            if (remainingMs == 0 || !readExactBytes(client, extLen, 2, remainingMs)) {
                Serial.println("[WS] Failed to read 16-bit extended length within deadline. Dropping.");
                client.stop();
                return "";
            }
            payloadLen = ((uint64_t)extLen[0] << 8) | extLen[1];
        } else if (payloadLen == 127) {
            uint8_t extLen[8];
            remainingMs = getRemainingDeadlineMs();
            if (remainingMs == 0 || !readExactBytes(client, extLen, 8, remainingMs)) {
                Serial.println("[WS] Failed to read 64-bit extended length within deadline. Dropping.");
                client.stop();
                return "";
            }
            payloadLen = 0;
            for (int i = 0; i < 8; i++) {
                payloadLen = (payloadLen << 8) | extLen[i];
            }
        }

        // Control frame checks (RFC 6455: payload <= 125, FIN must be 1)
        bool isControl = (opcode >= 0x08);
        if (isControl) {
            if (!fin || payloadLen > 125) {
                Serial.println("[WS] Protocol error: invalid control frame. Dropping.");
                client.stop();
                return "";
            }
        }

        // Enforce maximum buffer limit
        if (payloadLen > MAX_WS_MESSAGE_SIZE || (assembledText.length() + payloadLen > MAX_WS_MESSAGE_SIZE)) {
            Serial.printf("[WS] Payload length %llu exceeds safe limit %u. Dropping.\n", payloadLen, (unsigned int)MAX_WS_MESSAGE_SIZE);
            client.stop();
            return "";
        }

        // Read 4-byte masking key
        uint8_t mask[4] = {0};
        remainingMs = getRemainingDeadlineMs();
        if (remainingMs == 0 || !readExactBytes(client, mask, 4, remainingMs)) {
            Serial.println("[WS] Failed to read mask key within deadline. Dropping.");
            client.stop();
            return "";
        }

        // Read payload bytes with unified deadline
        uint8_t* payloadBuf = nullptr;
        if (payloadLen > 0) {
            payloadBuf = (uint8_t*)malloc((size_t)payloadLen);
            if (!payloadBuf) {
                Serial.println("[WS] Out of memory for payload. Dropping.");
                client.stop();
                return "";
            }
            remainingMs = getRemainingDeadlineMs();
            if (remainingMs == 0 || !readExactBytes(client, payloadBuf, (size_t)payloadLen, remainingMs)) {
                Serial.println("[WS] Failed to read full payload within deadline. Dropping.");
                free(payloadBuf);
                client.stop();
                return "";
            }
            // Unmask payload
            for (size_t i = 0; i < (size_t)payloadLen; i++) {
                payloadBuf[i] ^= mask[i % 4];
            }
        }

        // Handle Control Frames without breaking fragmented text assembly
        if (opcode == 0x08) { // Connection Close
            Serial.println("[WS] Received Close frame.");
            if (payloadBuf) free(payloadBuf);
            client.stop();
            return "CLOSE";
        }
        if (opcode == 0x09) { // Ping -> Send Pong
            sendWsPong(client, payloadBuf, (size_t)payloadLen);
            if (payloadBuf) free(payloadBuf);
            if (!inFragmentedMessage) {
                return "PING";
            }
            continue;
        }
        if (opcode == 0x0A) { // Pong -> Ignore
            if (payloadBuf) free(payloadBuf);
            if (!inFragmentedMessage) {
                return "PONG";
            }
            continue;
        }

        // Handle Data Frames (0x01 = Text, 0x00 = Continuation)
        if (opcode == 0x01 || (opcode == 0x00 && inFragmentedMessage)) {
            if (opcode == 0x01 && inFragmentedMessage) {
                Serial.println("[WS] Protocol error: new text frame before fragment complete. Dropping.");
                if (payloadBuf) free(payloadBuf);
                client.stop();
                return "";
            }

            if (payloadBuf && payloadLen > 0) {
                assembledText.concat((const char*)payloadBuf, (unsigned int)payloadLen);
            }
            if (payloadBuf) free(payloadBuf);

            if (fin) {
                return assembledText;
            } else {
                inFragmentedMessage = true;
                continue;
            }
        } else {
            Serial.printf("[WS] Unsupported or unexpected opcode 0x%02X. Dropping.\n", opcode);
            if (payloadBuf) free(payloadBuf);
            client.stop();
            return "";
        }
    }

    return assembledText;
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
                updateDynamicDeviceList(reqDeviceId, clientIp);
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
                                Serial.printf("[⚡ WS Port 81] Rejected ACK for '%s': Queue matching/NVS delete failed\n", ackTxId.c_str());
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

void sendUdpDiscoveryResponse(IPAddress targetIp, uint16_t targetPort) {
    if (WiFi.status() != WL_CONNECTED) return;

    String secKey = (sharedSecret.length() > 0) ? sharedSecret : String(MASTER_CRYPTO_SECRET);
    String ipStr = WiFi.localIP().toString();
    String sig = calculateHMAC("DISCOVERY:" + macAddressStr + ":" + ipStr, secKey);

    String resp = "{\"type\":\"PISOPHONE_ESP32_RESPONSE\","
                  "\"device\":\"PISOPHONE_MASTER\","
                  "\"mac\":\"" + macAddressStr + "\","
                  "\"ip\":\"" + ipStr + "\","
                  "\"sig\":\"" + sig + "\","
                  "\"port\":80,"
                  "\"ws_port\":81,"
                  "\"device_name\":\"PisoPhone Master\","
                  "\"slots\":" + String(maxLicensedSlots) + ","
                  "\"minutes\":" + String(minutesPerCoin) + ","
                  "\"price\":1.0,"
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

            if (msg.indexOf("PISOPHONE_DISCOVER") >= 0) {
                // If specific target_mac is specified in probe, only respond if matching this ESP32
                int targetMacIdx = msg.indexOf("\"target_mac\":\"");
                if (targetMacIdx >= 0) {
                    int valStart = targetMacIdx + 14;
                    int valEnd = msg.indexOf("\"", valStart);
                    if (valEnd > valStart) {
                        String reqMac = msg.substring(valStart, valEnd);
                        reqMac.toUpperCase();
                        String curMac = macAddressStr;
                        curMac.toUpperCase();
                        String cleanReq = "";
                        for (size_t i = 0; i < reqMac.length(); i++) if (reqMac[i] != ':') cleanReq += reqMac[i];
                        String cleanCur = "";
                        for (size_t i = 0; i < curMac.length(); i++) if (curMac[i] != ':') cleanCur += curMac[i];
                        if (cleanReq != cleanCur) {
                            return; // Probe targeted another box MAC
                        }
                    }
                }

                IPAddress remoteIp = udpServer.remoteIP();
                uint16_t remotePort = udpServer.remotePort();
                Serial.printf("[⚡ UDP Discovery] Valid probe received from %s:%d. Responding...\n",
                              remoteIp.toString().c_str(), remotePort);
                sendUdpDiscoveryResponse(remoteIp, remotePort);
            }
        }
    }

    // Periodic announcement beacon (every 2.5 seconds while connected to WiFi for fast DHCP recovery)
    static unsigned long lastUdpAnnounceMs = 0;
    if (millis() - lastUdpAnnounceMs > 2500 || lastUdpAnnounceMs == 0) {
        lastUdpAnnounceMs = millis();
        sendUdpDiscoveryResponse(IPAddress(255, 255, 255, 255), UDP_DISCOVERY_PORT);
    }
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
