#ifndef WEB_SERVER_MODULE_H
#define WEB_SERVER_MODULE_H

#include <Arduino.h>
#include <WebServer.h>
#include <WiFiUdp.h>
#include <WiFiServer.h>
#include <WiFiClient.h>
#include "Config.h"
#include "WebServerAuth.h"
#include "WebServerConfig.h"
#include "WebServerApi.h"
#include "Product.h"
#if PISO_HAS_PHONES
#include "WebServerCoinslot.h"
#endif
#if PISO_HAS_GATEWAY
#include "WebServerGateway.h"
#endif
#if PISO_HAS_PHONES
#include "WebServerTelemetry.h"
#include "WebSocketsUdp.h"
#include "Discovery.h"
#endif
#include "SerialCli.h"

extern WebServer webServer;
#if PISO_HAS_PHONES
extern WiFiServer wsServer;
extern WiFiClient wsClient;
extern bool isWsConnected;
extern String wsSessionDeviceId;
extern WiFiUDP udpServer;
extern QueueHandle_t authQueue;
extern volatile uint32_t authWorkerPasses; // AO-07: the AuthWorker's progress counter (WebServerAuth.cpp)
#endif

void setupWebServer();

#endif // WEB_SERVER_MODULE_H
