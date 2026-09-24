#include "WebServerModule.h"
#include "CoinSlotManager.h"
#include "PaymentQueueManager.h"
#include "Config.h"
#include "Security.h"
#include "HardwareManager.h"
#include "DeviceManager.h"
#include "SuperAdminManager.h"
#include "WebDashboardHtml.h"
#include <WiFi.h>
#include <WebServer.h>
#include <HTTPClient.h>
#include <ESPmDNS.h>
#include <Update.h>
#include "esp_wifi.h"

WebServer webServer(80);
WiFiServer wsServer(81);
WiFiClient wsClient;
bool isWsConnected = false;
String wsSessionDeviceId = "";
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
    webServer.on("/reset_vault", HTTP_POST, handleResetVault);
    webServer.on("/api/superadmin/auth", HTTP_POST, handleSuperAdminAuth);
    webServer.on("/api/superadmin/unmask", HTTP_POST, handleSuperAdminUnmask);
    webServer.on("/api/superadmin/reset_vault", HTTP_POST, handleSuperAdminResetVault);
    webServer.on("/api/superadmin/save_split", HTTP_POST, handleSuperAdminSaveSplit);
    webServer.on("/api/superadmin/change_pw", HTTP_POST, handleSuperAdminChangePassword);
    webServer.on("/api/status", HTTP_GET, handleApiStatus);
    webServer.on("/check_qualification", HTTP_GET, handleCheckQualification);
    webServer.on("/identify", HTTP_GET, handleIdentify);
    webServer.on("/query_time", HTTP_GET, handleQueryTime);
    webServer.on("/heartbeat", HTTP_GET, handleHeartbeat);
    webServer.on("/get_config", HTTP_GET, handleGetConfig);
    webServer.on("/crash_report", HTTP_POST, handleCrashReport);
    webServer.on("/api/slots", HTTP_GET, handleApiSlots);
    webServer.on("/api/slots/pair", HTTP_ANY, handleApiSlotPair);
    webServer.on("/api/slots/pair_request", HTTP_ANY, handleApiSlotPairRequest);
    webServer.on("/api/slots/unpair", HTTP_ANY, handleApiSlotUnpair);
    webServer.on("/api/slots/apply_token", HTTP_POST, handleApiSlotApplyToken);
    webServer.on("/api/slots/cloud_sync", HTTP_POST, handleApiSlotCloudSync);
    
    // Simple Universal Coinslot Endpoints for scripts / services
    webServer.on("/api/coinslot/activate", HTTP_ANY, handleCoinslotActivate);
    webServer.on("/api/coinslot/status", HTTP_GET, handleCoinslotStatus);
    webServer.on("/api/coinslot/pulses", HTTP_GET, handleCoinslotStatus);
    webServer.on("/api/coinslot/deactivate", HTTP_ANY, handleCoinslotDeactivate);
    webServer.on("/api/coinslot/disarm", HTTP_ANY, handleCoinslotDeactivate);
    webServer.on("/coinslot/activate", HTTP_ANY, handleCoinslotActivate);
    webServer.on("/coinslot/status", HTTP_GET, handleCoinslotStatus);
    webServer.on("/coinslot/pulses", HTTP_GET, handleCoinslotStatus);
    webServer.on("/coinslot/deactivate", HTTP_ANY, handleCoinslotDeactivate);
    webServer.on("/coinslot/disarm", HTTP_ANY, handleCoinslotDeactivate);
    
    webServer.on("/api/relay", HTTP_ANY, []() {
        if (!checkAdminAuth()) return;

        // Reject manual relay or polarity changes during an active or draining payment session
        if (isCoinSlotBusy("")) {
            webServer.send(409, "application/json", "{\"status\":\"error\",\"message\":\"Coin slot is currently active or draining. Manual relay override rejected.\"}");
            return;
        }

        bool hasInvert = webServer.hasArg("invert");
        if (hasInvert) {
            prefs.begin(NVS_NAMESPACE, false);
            relayActiveLow = (webServer.arg("invert") == "1" || webServer.arg("invert") == "true");
            prefs.putBool(NVS_KEY_RELAY_ACTIVE_LOW, relayActiveLow);
            prefs.end();
        }
        if (webServer.hasArg("state")) {
            bool state = (webServer.arg("state") == "1" || webServer.arg("state") == "true");
            setRelayHardware(state);
            webServer.send(200, "application/json", "{\"status\":\"ok\",\"relay_pin\":" + String(relayPin) + ",\"state\":" + String(state ? 1 : 0) + ",\"active_low\":" + String(relayActiveLow ? 1 : 0) + "}");
            return;
        }
        webServer.send(200, "application/json", "{\"status\":\"ok\",\"relay_pin\":" + String(relayPin) + ",\"active_low\":" + String(relayActiveLow ? 1 : 0) + "}");
    });
    
    // Port 80: Web OTA Firmware Update Endpoints
    webServer.on("/update", HTTP_GET, handleOtaForm);
    webServer.on("/update", HTTP_POST, []() {
        if (!checkAdminAuth()) return;
        webServer.sendHeader("Connection", "close");
        if (!otaIsValidBinary || Update.hasError() || !otaUpdateSuccess) {
            String errStr = otaErrorMsg.length() > 0 ? otaErrorMsg : ("Flash write failed (Error Code " + String(Update.getError()) + ")");
            webServer.send(400, "text/plain", errStr);
        } else {
            if (revenueDirty || totalCoinsLifetime != lastSavedTotalCoins || totalEarningsLifetime != lastSavedTotalEarnings) {
                lockNvs();
                prefs.begin(NVS_NAMESPACE, false);
                prefs.putULong(NVS_KEY_TOTAL_COINS, totalCoinsLifetime);
                prefs.putFloat(NVS_KEY_TOTAL_EARNINGS, totalEarningsLifetime);
                prefs.end();
                unlockNvs();
                lastSavedTotalCoins = totalCoinsLifetime;
                lastSavedTotalEarnings = totalEarningsLifetime;
                revenueDirty = false;
            }
            webServer.send(200, "text/plain", "SUCCESS");
            Serial.println("[OTA] Firmware flashing verified & completed successfully. Restarting...");
            Serial.flush();
            delay(500);
            ESP.restart();
        }
    }, []() {
        if (!checkAdminAuth()) return;
        HTTPUpload& upload = webServer.upload();
        
        if (upload.status == UPLOAD_FILE_START) {
            otaUpdateSuccess = false;
            otaFirstChunkReceived = false;
            otaIsValidBinary = true;
            otaErrorMsg = "";
            Update.clearError();
            
            setMaintenanceMode(true);
            releaseCoinSlot(getActiveCoinSessionId(), CoinSlotOwnerType::ANY, true, "OTA_FLASH");

            if (revenueDirty || totalCoinsLifetime != lastSavedTotalCoins || totalEarningsLifetime != lastSavedTotalEarnings) {
                lockNvs();
                prefs.begin(NVS_NAMESPACE, false);
                prefs.putULong(NVS_KEY_TOTAL_COINS, totalCoinsLifetime);
                prefs.putFloat(NVS_KEY_TOTAL_EARNINGS, totalEarningsLifetime);
                prefs.end();
                unlockNvs();
                lastSavedTotalCoins = totalCoinsLifetime;
                lastSavedTotalEarnings = totalEarningsLifetime;
                revenueDirty = false;
                Serial.println("[OTA] Revenue counters flushed to NVS flash before flashing.");
            }

            Serial.printf("[OTA] Starting firmware flash: %s\n", upload.filename.c_str());
            
            if (!Update.begin(UPDATE_SIZE_UNKNOWN, U_FLASH)) {
                otaIsValidBinary = false;
                setMaintenanceMode(false);
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
                    setMaintenanceMode(false);
                    otaErrorMsg = "Firmware verification failed after write (Error: " + String(Update.getError()) + ")";
                    Serial.printf("[OTA] Error: %s\n", otaErrorMsg.c_str());
                }
            } else {
                Update.abort();
                setMaintenanceMode(false);
            }
        } else if (upload.status == UPLOAD_FILE_ABORTED) {
            Update.abort();
            setMaintenanceMode(false);
            otaIsValidBinary = false;
            otaErrorMsg = "Upload connection was aborted prematurely.";
            Serial.println("[OTA] Upload aborted by client.");
        }
    });
    webServer.begin();

    // Port 81: Real-time WebSocket Server
    wsServer.begin();

    if (WiFi.status() == WL_CONNECTED) {
        Serial.printf("[!] Port 80: Management at http://%s:80\n", WiFi.localIP().toString().c_str());
        Serial.printf("[!] Port 81: WebSocket at ws://%s:81/ws\n\n", WiFi.localIP().toString().c_str());
    } else {
        Serial.printf("[!] Wi-Fi disconnected. Waiting for hotspot '%s' to become available...\n", wifiSsid.c_str());
    }
}
