#ifndef WEBSOCKET_SERVER_H
#define WEBSOCKET_SERVER_H

#include <Arduino.h>
#include <WiFiClient.h>
#include <IPAddress.h>

String extractUrlParam(String url, String param);
void sendWsText(WiFiClient& client, String text);
String readWsText(WiFiClient& client);
void processWebSocketServer();

void processSerialCli();

#endif // WEBSOCKET_SERVER_H
