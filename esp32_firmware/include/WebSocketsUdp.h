#ifndef WEBSOCKETS_UDP_H
#define WEBSOCKETS_UDP_H

#include <Arduino.h>
#include <WiFiClient.h>
#include <IPAddress.h>

String extractUrlParam(String url, String param);
void sendWsText(WiFiClient& client, String text);
String readWsText(WiFiClient& client);
void processWebSocketServer();

void sendUdpDiscoveryResponse(IPAddress targetIp, uint16_t targetPort);
void processUdpDiscovery();
void processSerialCli();

#endif // WEBSOCKETS_UDP_H
