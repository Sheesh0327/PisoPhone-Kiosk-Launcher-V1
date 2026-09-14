#ifndef WEB_SERVER_H
#define WEB_SERVER_H

#include <Arduino.h>
#include <WebServer.h>
#include <WiFiUdp.h>
#include <WiFiServer.h>
#include <WiFiClient.h>
#include "Config.h"
#include "WebSocketsUdp.h"

// WebServer Module
extern WebServer webServer;
extern WiFiServer wsServer;
extern WiFiClient wsClient;
extern bool isWsConnected;
extern String wsSessionDeviceId;
extern WiFiUDP udpServer;
extern QueueHandle_t authQueue;

void setupWebServer();

// API
void handleAddTime();
void handleInsertUniversalCoin();
void handleQueryTime();
void handleApiSlots();
void handleApiSlotPair();
void handleApiSlotUnpair();
void handleApiSlotApplyToken();
void handleApiSlotCloudSync();
void handleApiStatus();
void handleIdentify();

// Auth
void authWorkerTask(void *pvParameters);
bool checkAuth();
void redirectHome();
void handleLogout();

// Config
extern bool otaUpdateSuccess;
extern bool otaFirstChunkReceived;
extern bool otaIsValidBinary;
extern String otaErrorMsg;
void handlePortalRoot();
void handleReboot();
void handleFactoryReset();
void handleResetVault();
void handleSave();
void handleOtaForm();

// Telemetry
void handleHeartbeat();
void handleGetConfig();
void handleCrashReport();
void handleCheckQualification();
void handleOneVsOne();

#endif // WEB_SERVER_H
