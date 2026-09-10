#ifndef WEB_SERVER_MODULE_H
#define WEB_SERVER_MODULE_H

#include <Arduino.h>
#include <WebServer.h>
#include <WiFiUdp.h>
#include <WiFiServer.h>
#include <WiFiClient.h>
#include "Config.h"

extern WebServer webServer;
extern WiFiServer wsServer;
extern WiFiClient wsClient;
extern bool isWsConnected;
extern WiFiUDP udpServer;
extern QueueHandle_t authQueue;

// Background Auth Worker & Queue
void authWorkerTask(void *pvParameters);

// Initialization and Event Loops
void setupWebServer();
void processWebSocketServer();
void sendWsText(WiFiClient& client, String text);
String readWsText(WiFiClient& client);

void processUdpDiscovery();
void sendUdpDiscoveryResponse(IPAddress targetIp, uint16_t targetPort);
void processSerialCli();

#endif // WEB_SERVER_MODULE_H
