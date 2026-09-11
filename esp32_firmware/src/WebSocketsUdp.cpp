#include "WebSocketsUdp.h"
#include "WebServerModule.h"
#include "Config.h"
#include "HardwareManager.h"
#include "Security.h"
#include "DeviceManager.h"
#include "DeviceNetwork.h"
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
                updateDeviceTelemetry(reqDeviceId, clientIp, 0, 0, 100, false, ts);
                Serial.printf("[-] WS Mutex Rejected for %s (%s): Device is not paired to any slot on this ESP32\n", 
                    reqDeviceId.c_str(), clientIp.c_str());
                newClient.print("HTTP/1.1 423 Locked\r\n\r\nSLOT_NOT_PAIRED");
                newClient.stop();
                return;
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
            unsigned long now = millis();
            if (armedIp.length() > 0 && armedIp != reqDeviceId && now < armedUntil && (sessionStartTime == 0 || (now - sessionStartTime < MAX_SESSION_DURATION))) {
                Serial.printf("[-] WS Mutex Rejected for %s: Slot BUSY with %s\n", reqDeviceId.c_str(), armedIp.c_str());
                newClient.print("HTTP/1.1 409 Conflict\r\n\r\nSLOT_BUSY");
                newClient.stop();
                return;
            }
            
            // Mutex passed, slot acquired. Save the nonce.
            recordDeviceNonce(reqDeviceId, ts);
            
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
            
            if (armedIp != reqDeviceId || sessionStartTime == 0) {
                sessionStartTime = now;
            }
            armedIp = reqDeviceId;
            lastArmedDeviceId = reqDeviceId;
            lastArmedIp = getIpFromDeviceId(reqDeviceId);
            lastArmedTimeMs = now;
            armedUntil = now + ARM_TTL;
            pendingWsGracefulClose = false;
            
            Serial.printf("[⚡ WS Port 81] WebSocket ARMED securely for %s (TTL: %lu s)\n", reqDeviceId.c_str(), ARM_TTL / 1000);
            sendWsText(wsClient, "{\"event\":\"ARMED\"}");
        }
    }
    
    // Process active WebSocket client frames or disconnection
    if (isWsConnected) {
        if (!wsClient.connected()) {
            Serial.printf("[*] WS Client %s disconnected. Slot released.\n", armedIp.c_str());
            isWsConnected = false;
            if (armedIp.length() > 0) {
                lastArmedDeviceId = armedIp;
                lastArmedIp = getIpFromDeviceId(armedIp);
                lastArmedTimeMs = millis();
            }
            armedIp = "";
            armedUntil = 0;
            sessionStartTime = 0;
            processRelayState();
            return;
        }
        
        unsigned long now = millis();

        if (wsClient.available()) {
            String frameText = readWsText(wsClient);
            if (frameText == "DONE" || frameText == "CLOSE" || frameText == "DISARM") {
                noInterrupts();
                int currentPulses = isrUniversalPulseCount;
                unsigned long lastPulse = isrLastPulseTimeMs;
                interrupts();

                // If coin pulses are actively in progress or arrived in the last 600ms, hold graceful close to deliver credit!
                if (currentPulses > 0 || (now - lastPulse < 600 && lastPulse > 0)) {
                    Serial.printf("[⚡ WS Port 81] '%s' received while coin pulses are active (%d pulses). Holding graceful close to finalize credit...\n", frameText.c_str(), currentPulses);
                    pendingWsGracefulClose = true;
                    pendingWsGracefulCloseUntil = now + 2000;
                    armedUntil = now + 3000; // Extend temporary guard so pulse train completes safely
                    wsClient.stop();
                    isWsConnected = false;
                    return;
                } else {
                    Serial.printf("[⚡ WS Port 81] '%s' received. Disarming coinslot relay immediately.\n", frameText.c_str());
                    wsClient.stop();
                    isWsConnected = false;
                    if (armedIp.length() > 0) {
                        lastArmedDeviceId = armedIp;
                        lastArmedIp = getIpFromDeviceId(armedIp);
                        lastArmedTimeMs = now;
                    }
                    armedIp = "";
                    armedUntil = 0;
                    sessionStartTime = 0;
                    processRelayState();
                    return;
                }
            } else if (frameText.length() > 0) {
                // Any regular active frame or ping/pong keeps arming TTL refreshed
                armedUntil = now + ARM_TTL;
            }
        } else {
            // Keep slot armed continuously while WebSocket client remains connected and session duration is valid
            if (sessionStartTime > 0 && (now - sessionStartTime < MAX_SESSION_DURATION)) {
                armedUntil = now + ARM_TTL;
            }
        }
        
        // Check session TTL expiration or pending graceful close timeout
        if (pendingWsGracefulClose && now >= pendingWsGracefulCloseUntil) {
            Serial.println("[*] Pending graceful close timed out after coin train window. Slot released.");
            pendingWsGracefulClose = false;
            wsClient.stop();
            isWsConnected = false;
            if (armedIp.length() > 0) {
                lastArmedDeviceId = armedIp;
                lastArmedIp = getIpFromDeviceId(armedIp);
                lastArmedTimeMs = now;
            }
            armedIp = "";
            armedUntil = 0;
            sessionStartTime = 0;
            return;
        }

        if (sessionStartTime > 0 && (now - sessionStartTime >= MAX_SESSION_DURATION)) {
            Serial.printf("[*] WS Session TTL expired for %s. Slot released.\n", armedIp.c_str());
            sendWsText(wsClient, "{\"event\":\"TIMEOUT\"}");
            wsClient.stop();
            isWsConnected = false;
            if (armedIp.length() > 0) {
                lastArmedDeviceId = armedIp;
                lastArmedIp = getIpFromDeviceId(armedIp);
                lastArmedTimeMs = now;
            }
            armedIp = "";
            armedUntil = 0;
            sessionStartTime = 0;
        }
    }
}

void sendUdpDiscoveryResponse(IPAddress targetIp, uint16_t targetPort) {
    if (WiFi.status() != WL_CONNECTED) return;

    String resp = "{\"type\":\"PISOPHONE_ESP32_RESPONSE\","
                  "\"device\":\"PISOPHONE_MASTER\","
                  "\"mac\":\"" + macAddressStr + "\","
                  "\"ip\":\"" + WiFi.localIP().toString() + "\","
                  "\"port\":80,"
                  "\"ws_port\":81,"
                  "\"device_name\":\"PisoPhone Master\","
                  "\"price\":" + String(coinPrice, 2) + ","
                  "\"minutes\":" + String(minutesPerCoin) + ","
                  "\"slots\":" + String(maxLicensedSlots) + ","
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
                IPAddress remoteIp = udpServer.remoteIP();
                uint16_t remotePort = udpServer.remotePort();
                Serial.printf("[⚡ UDP Discovery] Valid probe received from %s:%d. Responding...\n",
                              remoteIp.toString().c_str(), remotePort);
                sendUdpDiscoveryResponse(remoteIp, remotePort);
            }
        }
    }

    // Periodic announcement beacon (every 10 seconds while connected to WiFi)
    static unsigned long lastUdpAnnounceMs = 0;
    if (millis() - lastUdpAnnounceMs > 10000 || lastUdpAnnounceMs == 0) {
        lastUdpAnnounceMs = millis();
        sendUdpDiscoveryResponse(IPAddress(255, 255, 255, 255), UDP_DISCOVERY_PORT);
    }
}

void processSerialCli() {
    if (!Serial.available()) return;
    String line = Serial.readStringUntil('\n');
    line.trim();
    if (line.length() == 0) return;

    if (line.equalsIgnoreCase("coin")) {
        triggerCoinEvent();
    } else if (line.startsWith("ucoin ") || line.startsWith("ucoin")) {
        int firstSpace = line.indexOf(' ');
        int pulses = (firstSpace != -1) ? line.substring(firstSpace + 1).toInt() : 1;
        if (pulses <= 0) pulses = 1;
        triggerUniversalCoinEvent(pulses);
    } else if (line.startsWith("add ") || line.startsWith("add")) {
        int firstSpace = line.indexOf(' ');
        if (firstSpace != -1) {
            int minutes = line.substring(firstSpace + 1).toInt();
            sendAddTime(minutes, "ALL");
        }
    } else if (line.equalsIgnoreCase("help")) {
        Serial.println("\nCommands: coin | ucoin <1|5|10|20> | add <minutes> | help");
    }
}
