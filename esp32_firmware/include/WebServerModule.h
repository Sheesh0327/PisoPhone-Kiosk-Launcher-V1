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
#include "WebServerTelemetry.h"
#include "WebSocketsUdp.h"

extern WebServer webServer;
extern WiFiServer wsServer;
extern WiFiClient wsClient;
extern bool isWsConnected;
extern WiFiUDP udpServer;
extern QueueHandle_t authQueue;

void setupWebServer();

#endif // WEB_SERVER_MODULE_H
