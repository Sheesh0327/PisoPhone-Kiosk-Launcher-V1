#ifndef WEBSOCKET_PROTOCOL_H
#define WEBSOCKET_PROTOCOL_H

#include <Arduino.h>
#include <WiFiClient.h>

String extractUrlParam(String url, String param);
void sendWsText(WiFiClient& client, String text);
void sendWsPong(WiFiClient& client, const uint8_t* payload, size_t len);
String readWsText(WiFiClient& client);

#endif // WEBSOCKET_PROTOCOL_H
