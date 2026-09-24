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

            if (secKey.length() == 0) {
                newClient.print("HTTP/1.1 400 Bad Request\r\n\r\nMissing Sec-WebSocket-Key");
                newClient.stop();
                return;
            }

            // Universal Device / Session ID extraction
            String reqDeviceId = extractUrlParam(request, "device_id=");
            if (reqDeviceId.length() == 0) reqDeviceId = extractUrlParam(request, "session_id=");
            if (reqDeviceId.length() == 0) reqDeviceId = extractUrlParam(request, "client_id=");
            if (reqDeviceId.length() == 0) reqDeviceId = extractUrlParam(request, "id=");
            if (reqDeviceId.length() == 0) {
                reqDeviceId = "client_" + newClient.remoteIP().toString();
            }
            reqDeviceId.trim();

            // Maintenance mode check
            if (isMaintenanceMode()) {
                Serial.printf("[-] WS Rejected for %s: Maintenance mode active\n", reqDeviceId.c_str());
                newClient.print("HTTP/1.1 503 Service Unavailable\r\nContent-Type: application/json\r\n\r\n{\"error\":\"MAINTENANCE_MODE\"}");
                newClient.stop();
                return;
            }

            // Hardware Mutex Check: allow if idle or same session, reject if held by a different session
            if (isCoinSlotBusy(reqDeviceId, CoinSlotOwnerType::ANY)) {
                String activeSess = getActiveCoinSessionId();
                Serial.printf("[-] WS Mutex Rejected for %s: Slot BUSY with %s\n", reqDeviceId.c_str(), activeSess.c_str());
                newClient.print("HTTP/1.1 409 Conflict\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: 23\r\n\r\n{\"event\":\"SLOT_BUSY\"}");
                newClient.flush();
                delay(10);
                newClient.stop();
                return;
            }

            // Complete RFC6455 Handshake
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
            
            // Automatically arm the coin slot for the connected client/service
            bool reserved = reserveCoinSlot(reqDeviceId, CoinSlotOwnerType::ANY, ARM_TTL,
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
                sendWsText(wsClient, "{\"event\":\"ERROR\",\"reason\":\"SLOT_UNAVAILABLE\"}");
                wsClient.stop();
                isWsConnected = false;
                wsSessionDeviceId = "";
                return;
            }

            Serial.printf("[+] WS Client Connected & Coin Slot Armed: %s (IP: %s)\n", 
                          reqDeviceId.c_str(), newClient.remoteIP().toString().c_str());
            
            String armedMsg = "{\"event\":\"ARMED\",\"state\":\"ARMED\",\"session_id\":\"" + reqDeviceId +
                              "\",\"pulses\":" + String(getSessionAccumulatedPulses()) +
                              ",\"total_pulses\":" + String((int)totalCoinsLifetime) + "}";
            sendWsText(wsClient, armedMsg);
        }
    }
    
    // Process active WebSocket client frames or disconnection
    if (isWsConnected) {
        String boundDevId = wsSessionDeviceId;
        if (!wsClient.connected()) {
            Serial.printf("[*] WS Client %s disconnected. Releasing slot.\n", boundDevId.c_str());
            isWsConnected = false;
            wsSessionDeviceId = "";
            releaseCoinSlot(boundDevId, CoinSlotOwnerType::ANY, false);
            return;
        }
        
        if (wsClient.available()) {
            String frameText = readWsText(wsClient);
            if (frameText.length() > 0) {
                if (frameText == "DONE" || frameText == "CLOSE" || frameText == "deactivate" || frameText == "disarm" ||
                    frameText.indexOf("\"action\":\"deactivate\"") >= 0 || frameText.indexOf("\"action\":\"disarm\"") >= 0 ||
                    frameText.indexOf("\"command\":\"deactivate\"") >= 0 || frameText.indexOf("\"command\":\"disarm\"") >= 0) {
                    Serial.printf("[⚡ WS Port 81] Disarm requested for %s.\n", boundDevId.c_str());
                    int pulses = getSessionAccumulatedPulses();
                    releaseCoinSlot(boundDevId, CoinSlotOwnerType::ANY, true, "MANUAL_DISARM");
                    String doneJson = "{\"event\":\"DEACTIVATED\",\"state\":\"IDLE\",\"is_armed\":false,\"pulses\":" + String(pulses) +
                                      ",\"total_pulses\":" + String((int)totalCoinsLifetime) + "}";
                    sendWsText(wsClient, doneJson);
                    wsClient.stop();
                    isWsConnected = false;
                    wsSessionDeviceId = "";
                    return;
                }

                refreshCoinSlotTtl(boundDevId, CoinSlotOwnerType::ANY, ARM_TTL);

                if (frameText == "status" || frameText == "pulses" || frameText.indexOf("\"action\":\"status\"") >= 0) {
                    String statusJson = "{\"event\":\"STATUS\",\"state\":\"ARMED\",\"is_armed\":true,\"session_id\":\"" + boundDevId +
                                        "\",\"pulses\":" + String(getSessionAccumulatedPulses()) +
                                        ",\"total_pulses\":" + String((int)totalCoinsLifetime) + "}";
                    sendWsText(wsClient, statusJson);
                } else {
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

                        if (ackDoc["tx_id"].is<const char*>() && ackDoc["amount"].is<int>()) {
                            ackTxId = ackDoc["tx_id"].as<String>();
                            amountVal = ackDoc["amount"].as<int>();
                            secondsVal = ackDoc["seconds"].is<int>() ? ackDoc["seconds"].as<int>() : 0;
                            statusVal = ackDoc["status"].is<const char*>() ? ackDoc["status"].as<String>() : "OK";
                            ackDevId = ackDoc["device_id"].is<const char*>() ? ackDoc["device_id"].as<String>() : boundDevId;
                            
                            acknowledgePhonePayment(ackDevId, ackTxId, amountVal, secondsVal, statusVal);
                            Serial.printf("[⚡ WS Port 81] Clean ACK processed for tx_id='%s'\n", ackTxId.c_str());
                        }
                    }
                }
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
                    releaseCoinSlot(boundDevId, CoinSlotOwnerType::ANY, false);
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
