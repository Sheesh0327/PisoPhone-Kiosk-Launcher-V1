#include "WebServerModule.h"
#include "Config.h"
#include "Security.h"
#include "HardwareManager.h"
#include "DeviceManager.h"
#include "WebDashboardHtml.h"
#include <WiFi.h>
#include <WebServer.h>
#include <HTTPClient.h>
#include <WiFiUdp.h>
#include <ESPmDNS.h>
#include <Update.h>
#include "esp_wifi.h"

WebServer webServer(80);
WiFiServer wsServer(81);
WiFiClient wsClient;
bool isWsConnected = false;
WiFiUDP udpServer;
QueueHandle_t authQueue = NULL;

void setupWebServer() {
    // Initialize mDNS Responder ("kioskmanager.local")
    if (MDNS.begin("kioskmanager")) {
        MDNS.addService("kioskmanager", "tcp", 80);
        MDNS.addService("http", "tcp", 80);
        Serial.println("[+] mDNS service active at http://kioskmanager.local");
    } else {
        Serial.println("[-] Error setting up mDNS responder!");
    }

    // Initialize Authenticated Request Worker Queue & FreeRTOS Supervisor Task (8KB stack)
    authQueue = xQueueCreate(16, sizeof(AuthRequest));
    xTaskCreate(authWorkerTask, "AuthWorker", 8192, NULL, 1, NULL);

    // Port 80: HTTP Portal & API routes
    webServer.on("/", HTTP_GET, handlePortalRoot);
    webServer.on("/logout", HTTP_GET, handleLogout);
    webServer.on("/save", HTTP_POST, handleSave);
    webServer.on("/reboot", HTTP_POST, handleReboot);
    webServer.on("/factory_reset", HTTP_POST, handleFactoryReset);
    webServer.on("/add_time", HTTP_POST, handleAddTime);
    webServer.on("/one_vs_one", HTTP_POST, handleOneVsOne);
    webServer.on("/insert_coin", HTTP_POST, handleInsertCoin);
    webServer.on("/insert_ucoin", HTTP_POST, handleInsertUniversalCoin);
    webServer.on("/reset_vault", HTTP_POST, handleResetVault);
    webServer.on("/api/status", HTTP_GET, handleApiStatus);
    webServer.on("/check_qualification", HTTP_GET, handleCheckQualification);
    webServer.on("/identify", HTTP_GET, handleIdentify);
    webServer.on("/query_time", HTTP_GET, handleQueryTime);
    webServer.on("/heartbeat", HTTP_GET, handleHeartbeat);
    webServer.on("/get_config", HTTP_GET, handleGetConfig);
    webServer.on("/crash_report", HTTP_POST, handleCrashReport);
    webServer.on("/api/slots", HTTP_GET, handleApiSlots);
    webServer.on("/api/slots/pair", HTTP_ANY, handleApiSlotPair);
    webServer.on("/api/slots/unpair", HTTP_ANY, handleApiSlotUnpair);
    webServer.on("/api/slots/apply_token", HTTP_POST, handleApiSlotApplyToken);
    webServer.on("/api/slots/cloud_sync", HTTP_POST, handleApiSlotCloudSync);
    
    webServer.on("/api/relay", HTTP_ANY, []() {
        if (webServer.hasArg("invert")) {
            relayActiveLow = (webServer.arg("invert") == "1" || webServer.arg("invert") == "true");
            prefs.begin("kiosk_cfg", false);
            prefs.putBool("relay_active_low", relayActiveLow);
            prefs.end();
        }
        if (webServer.hasArg("mode")) {
            relayMode = webServer.arg("mode").toInt();
            prefs.begin("kiosk_cfg", false);
            prefs.putInt("relay_mode", relayMode);
            prefs.end();
        }
        if (webServer.hasArg("state")) {
            bool state = (webServer.arg("state") == "1" || webServer.arg("state") == "true");
            setRelayHardware(state);
            webServer.send(200, "application/json", "{\"status\":\"ok\",\"relay_pin\":" + String(relayPin) + ",\"state\":" + String(state ? 1 : 0) + ",\"active_low\":" + String(relayActiveLow ? 1 : 0) + ",\"mode\":" + String(relayMode) + "}");
            return;
        }
        webServer.send(200, "application/json", "{\"status\":\"ok\",\"relay_pin\":" + String(relayPin) + ",\"active_low\":" + String(relayActiveLow ? 1 : 0) + ",\"mode\":" + String(relayMode) + "}");
    });
    
    // Port 80: Web OTA Firmware Update Endpoints
    webServer.on("/update", HTTP_GET, handleOtaForm);
    webServer.on("/update", HTTP_POST, []() {
        if (!checkAuth()) return;
        webServer.sendHeader("Connection", "close");
        if (!otaIsValidBinary || Update.hasError() || !otaUpdateSuccess) {
            String errStr = otaErrorMsg.length() > 0 ? otaErrorMsg : ("Flash write failed (Error Code " + String(Update.getError()) + ")");
            webServer.send(400, "text/plain", errStr);
        } else {
            webServer.send(200, "text/plain", "SUCCESS");
            delay(1000);
            ESP.restart();
        }
    }, []() {
        if (!checkAuth()) return;
        HTTPUpload& upload = webServer.upload();
        
        if (upload.status == UPLOAD_FILE_START) {
            otaUpdateSuccess = false;
            otaFirstChunkReceived = false;
            otaIsValidBinary = true;
            otaErrorMsg = "";
            Update.clearError();
            
            Serial.printf("[OTA] Starting firmware flash: %s\n", upload.filename.c_str());
            
            if (!Update.begin(UPDATE_SIZE_UNKNOWN, U_FLASH)) {
                otaIsValidBinary = false;
                otaErrorMsg = "Failed to begin flash partition write (Error: " + String(Update.getError()) + ")";
                Serial.printf("[OTA] Error: %s\n", otaErrorMsg.c_str());
            }
        } else if (upload.status == UPLOAD_FILE_WRITE) {
            if (!otaIsValidBinary) return;

            if (upload.currentSize > 0) {
                if (Update.write(upload.buf, upload.currentSize) != upload.currentSize) {
                    otaIsValidBinary = false;
                    otaErrorMsg = "Flash write failed at offset " + String(Update.progress()) + " (Error: " + String(Update.getError()) + ")";
                    Serial.printf("[OTA] Error: %s\n", otaErrorMsg.c_str());
                } else {
                    Serial.print(".");
                }
            }
        } else if (upload.status == UPLOAD_FILE_END) {
            Serial.println();
            if (otaIsValidBinary) {
                if (Update.end(true)) {
                    Serial.printf("[OTA] Firmware flashing verified & completed successfully: %u bytes\n", upload.totalSize);
                    otaUpdateSuccess = true;
                } else {
                    otaIsValidBinary = false;
                    otaErrorMsg = "Firmware verification failed after write (Error: " + String(Update.getError()) + ")";
                    Serial.printf("[OTA] Error: %s\n", otaErrorMsg.c_str());
                }
            } else {
                Update.abort();
            }
        } else if (upload.status == UPLOAD_FILE_ABORTED) {
            Update.abort();
            otaIsValidBinary = false;
            otaErrorMsg = "Upload connection was aborted prematurely.";
            Serial.println("[OTA] Upload aborted by client.");
        }
    });
    webServer.begin();

    // Port 81: Real-time WebSocket Server
    wsServer.begin();

    // Port 8888: UDP Broadcast Discovery Service
    udpServer.begin(UDP_DISCOVERY_PORT);
    Serial.printf("[!] Port %d: UDP Discovery Server active\n", UDP_DISCOVERY_PORT);

    if (WiFi.status() == WL_CONNECTED) {
        Serial.printf("[!] Port 80: Management at http://%s:80\n", WiFi.localIP().toString().c_str());
        Serial.printf("[!] Port 81: WebSocket at ws://%s:81/ws\n\n", WiFi.localIP().toString().c_str());
        sendUdpDiscoveryResponse(IPAddress(255, 255, 255, 255), UDP_DISCOVERY_PORT);
    } else {
        Serial.printf("[!] Wi-Fi disconnected. Waiting for hotspot '%s' to become available...\n", wifiSsid.c_str());
    }
}
