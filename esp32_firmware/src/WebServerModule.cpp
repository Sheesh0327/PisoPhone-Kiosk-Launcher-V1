// Creates the HTTP server, registers every route and maps each one to its handler.
// Authentication is decided inside the handlers (see WebServerAuth.cpp), not here.

#include "WebServerModule.h"
#include "WebServerAccounts.h"
#include "CoinSlotManager.h"
#include "PaymentQueueManager.h"
#include "Diagnostics.h"
#include "OtaCheck.h"
#include "OtaSecurity.h"
#include "WebAssetServer.h"
#include "WebServerAuth.h"
#include "Config.h"
#include "Security.h"
#include "HardwareManager.h"
#include "DeviceManager.h"
#include "SuperAdminManager.h"
#include "WebDashboardHtml.h"
#include <WiFi.h>
#include <WebServer.h>
#include <HTTPClient.h>
#include <WiFiUdp.h>
#include <ESPmDNS.h>
#include <Update.h>
#include "esp_wifi.h"
#include <esp_task_wdt.h>

WebServer webServer(80);
WiFiServer wsServer(81);
WiFiClient wsClient;
bool isWsConnected = false;
String wsSessionDeviceId = "";
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
    initWorkerAckQueue(); // before the task starts: it posts into this queue
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
    webServer.on("/api/superadmin/collections", HTTP_POST, handleSuperAdminCollections);
    webServer.on("/api/superadmin/save_split", HTTP_POST, handleSuperAdminSaveSplit);
    webServer.on("/api/superadmin/factory_reset", HTTP_POST, handleSuperAdminFactoryReset);
    webServer.on("/api/status", HTTP_GET, handleApiStatus);
    webServer.on("/check_qualification", HTTP_GET, handleCheckQualification);
    webServer.on("/identify", HTTP_GET, handleIdentify);
    webServer.on("/query_time", HTTP_GET, handleQueryTime);
    webServer.on("/api/locate", HTTP_POST, handleApiLocate);
    webServer.on("/heartbeat", HTTP_GET, handleHeartbeat);
    webServer.on("/get_config", HTTP_GET, handleGetConfig);
    webServer.on("/crash_report", HTTP_POST, handleCrashReport);
    webServer.on("/api/slots", HTTP_GET, handleApiSlots);
    webServer.on("/api/slots/pair", HTTP_ANY, handleApiSlotPair);
    webServer.on("/api/slots/pair_request", HTTP_ANY, handleApiSlotPairRequest);
    webServer.on("/api/slots/unpair", HTTP_ANY, handleApiSlotUnpair);
    webServer.on("/api/slots/cloud_sync", HTTP_POST, handleApiSlotCloudSync);
    webServer.on("/api/security/switch_key", HTTP_POST, handleApiSecuritySwitchKey);

    // Dedicated Robust Coin Slot API routes
    webServer.on("/api/coinslot/arm", HTTP_ANY, handleApiCoinslotArm);
    webServer.on("/api/coinslot/unarm", HTTP_ANY, handleApiCoinslotUnarm);
    webServer.on("/api/coinslot/status", HTTP_GET, handleApiCoinslotStatus);
    webServer.on("/api/coinslot/ack", HTTP_ANY, handleApiCoinslotAck);

    // Player accounts (QR cards): phone calls are signed; the admin list and changes sit behind the admin login
    webServer.on("/api/account/scan", HTTP_ANY, handleApiAccountScan);
    webServer.on("/api/account/name", HTTP_ANY, handleApiAccountName);
    webServer.on("/api/account/signout", HTTP_ANY, handleApiAccountSignout);
    webServer.on("/api/account/info", HTTP_ANY, handleApiAccountInfo);
    webServer.on("/api/accounts", HTTP_GET, handleApiAccountsList);
    webServer.on("/api/accounts/adjust", HTTP_POST, handleApiAccountsAdjust);
    webServer.on("/api/accounts/delete", HTTP_POST, handleApiAccountsDelete);

    // Network coin-slot gateway (router payment verification). Disabled until a key is configured.
    webServer.on("/api/gateway/challenge", HTTP_GET, handleGatewayChallenge);
    webServer.on("/api/gateway/arm", HTTP_ANY, handleGatewayArm);
    webServer.on("/api/gateway/status", HTTP_ANY, handleGatewayStatus);
    webServer.on("/api/gateway/release", HTTP_ANY, handleGatewayRelease);
    webServer.on("/api/gateway/ack", HTTP_ANY, handleGatewayAck);
    webServer.on("/api/gateway/config", HTTP_ANY, handleGatewayConfig);

    webServer.on("/api/relay", HTTP_ANY, []() {
        if (!checkAdminAuth()) return;

        // Reject manual relay or polarity changes during an active or draining payment session
        if (isCoinSlotBusy("")) {
            webServer.send(
                409, "application/json",
                "{\"status\":\"error\",\"message\":\"Coin slot is currently active or draining. Manual relay override rejected.\"}");
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
            webServer.send(200, "application/json",
                           "{\"status\":\"ok\",\"relay_pin\":" + String(relayPin) + ",\"state\":" +
                               String(state ? 1 : 0) + ",\"active_low\":" + String(relayActiveLow ? 1 : 0) + "}");
            return;
        }
        webServer.send(200, "application/json",
                       "{\"status\":\"ok\",\"relay_pin\":" + String(relayPin) +
                           ",\"active_low\":" + String(relayActiveLow ? 1 : 0) + "}");
    });

    webServer.on("/api/diagnostics", HTTP_GET, handleApiDiagnostics);
    webServer.on("/api/payments/clear", HTTP_POST, []() {
        if (!checkAdminAuth()) return;
        if (isCoinSlotBusy("")) {
            webServer.send(
                409, "application/json",
                "{\"status\":\"error\",\"message\":\"Coin slot is active or draining. Try again when idle.\"}");
            return;
        }
        int cleared = clearPaymentQueue();
        webServer.send(200, "application/json", "{\"status\":\"ok\",\"cleared\":" + String(cleared) + "}");
    });

    // Port 80: Web OTA Firmware Update Endpoints
    registerWebAssetRoutes();
    webServer.on("/update", HTTP_GET, handleOtaForm);
    webServer.on("/api/ota/manifest", HTTP_POST, handleApiOtaManifest);
    webServer.on(
        "/update", HTTP_POST,
        []() {
            if (!checkAdminAuth()) return;
            webServer.sendHeader("Connection", "close");
            if (!otaIsValidBinary || Update.hasError() || !otaUpdateSuccess) {
                String errStr = otaErrorMsg.length() > 0
                                    ? otaErrorMsg
                                    : ("Flash write failed (Error Code " + String(Update.getError()) + ")");
                webServer.send(400, "text/plain", errStr);
            } else {
                // The new image is already committed; give a coin that landed since the finalize
                // check a moment to reach NVS before restarting.
                unsigned long waitStart = millis();
                while (hasUnpersistedPayments() && millis() - waitStart < 10000) {
                    processPendingPaymentRetries();
                    esp_task_wdt_reset();
                    delay(100);
                }
                webServer.send(200, "text/plain", "SUCCESS");
                delay(1000);
                diagNoteRestartReason("firmware-update");
                ESP.restart();
            }
        },
        []() {
            if (!checkAdminAuth()) return;
            HTTPUpload& upload = webServer.upload();

            if (upload.status == UPLOAD_FILE_START) {
                otaUpdateSuccess = false;
                otaFirstChunkReceived = false;
                otaIsValidBinary = true;
                otaErrorMsg = "";
                Update.clearError();

                if (!canPerformRebootOrOta()) {
                    otaIsValidBinary = false;
                    otaErrorMsg = "OTA blocked: unpersisted transactions in RAM";
                    diagLog("[OTA] Aborted: unpersisted transactions in RAM");
                    return;
                }

                diagCount(DiagCounter::OtaAttempts);
                diagLog("[OTA] Starting firmware flash: %s\n", upload.filename.c_str());

                // Refuse before anything is written to flash when the image is not covered by a signed manifest.
                String signErr;
                if (!otaStartImage(signErr)) {
                    otaIsValidBinary = false;
                    otaErrorMsg = signErr;
                    diagLog("[OTA] %s\n", otaErrorMsg.c_str());
                    return;
                }

                if (!Update.begin(UPDATE_SIZE_UNKNOWN, U_FLASH)) {
                    otaIsValidBinary = false;
                    otaErrorMsg = "Failed to begin flash partition write (Error: " + String(Update.getError()) + ")";
                    diagLog("[OTA] Error: %s\n", otaErrorMsg.c_str());
                }
            } else if (upload.status == UPLOAD_FILE_WRITE) {
                // The whole upload runs inside one handleClient() call, so loop() cannot feed the
                // 15 s task watchdog until it finishes; a slow upload would otherwise panic-reset.
                esp_task_wdt_reset();
                if (!otaIsValidBinary) return;

                if (!otaFirstChunkReceived && upload.currentSize > 0) {
                    otaFirstChunkReceived = true;
#if CONFIG_IDF_TARGET_ESP32C3
                    const uint16_t expectedChip = otacheck::CHIP_ESP32_C3;
#else
                    const uint16_t expectedChip = otacheck::CHIP_ESP32;
#endif
                    otacheck::Result headerCheck =
                        otacheck::checkImageHeader(upload.buf, upload.currentSize, expectedChip);
                    if (headerCheck != otacheck::OK) {
                        otaIsValidBinary = false;
                        otaErrorMsg = String("OTA rejected: ") + otacheck::describe(headerCheck);
                        diagLog("[OTA] %s\n", otaErrorMsg.c_str());
                        return;
                    }
                }

                if (upload.currentSize > 0) {
                    String signErr;
                    if (!otaFeedImage(upload.buf, upload.currentSize, signErr)) {
                        otaIsValidBinary = false;
                        otaErrorMsg = signErr;
                        diagLog("[OTA] %s\n", otaErrorMsg.c_str());
                        return;
                    }
                    if (Update.write(upload.buf, upload.currentSize) != upload.currentSize) {
                        otaIsValidBinary = false;
                        otaErrorMsg = "Flash write failed at offset " + String(Update.progress()) +
                                      " (Error: " + String(Update.getError()) + ")";
                        diagLog("[OTA] Error: %s\n", otaErrorMsg.c_str());
                    } else {
                        Serial.print(".");
                    }
                }
            } else if (upload.status == UPLOAD_FILE_END) {
                esp_task_wdt_reset();
                Serial.println();
                // Check before Update.end(): it switches the boot partition, so a refusal afterwards
                // would still boot the new image on the next (e.g. scheduled) restart.
                if (otaIsValidBinary && !canPerformRebootOrOta()) {
                    otaIsValidBinary = false;
                    otaErrorMsg = "OTA blocked: unpersisted transactions in RAM";
                    diagLog("[OTA] Aborted at finalize: unpersisted transactions in RAM");
                }
                if (otaIsValidBinary) {
                    String signErr;
                    if (!otaFinishImage(signErr)) {
                        otaIsValidBinary = false;
                        otaErrorMsg = signErr;
                        diagLog("[OTA] %s\n", otaErrorMsg.c_str());
                    }
                }
                if (otaIsValidBinary) {
                    if (Update.end(true)) {
                        diagLog("[OTA] Firmware flashing verified & completed successfully: %u bytes\n",
                                upload.totalSize);
                        otaUpdateSuccess = true;
                    } else {
                        otaIsValidBinary = false;
                        otaErrorMsg =
                            "Firmware verification failed after write (Error: " + String(Update.getError()) + ")";
                        diagLog("[OTA] Error: %s\n", otaErrorMsg.c_str());
                    }
                } else {
                    Update.abort();
                }
            } else if (upload.status == UPLOAD_FILE_ABORTED) {
                Update.abort();
                otaAbortImage();
                otaIsValidBinary = false;
                otaErrorMsg = "Upload connection was aborted prematurely.";
                Serial.println("[OTA] Upload aborted by client.");
            }
        });
    // Needed so the login throttle can tell a wrong password from the browser's first probe.
    static const char* kCollectedHeaders[] = {"Authorization"};
    webServer.collectHeaders(kCollectedHeaders, 1);
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
