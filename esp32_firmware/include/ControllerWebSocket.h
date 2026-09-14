#ifndef CONTROLLER_WEB_SOCKET_H
#ifndef CONTROLLER_WEB_SOCKET_H
#define CONTROLLER_WEB_SOCKET_H

#include <Arduino.h>
#include <WiFiClient.h>

void handleControllerWebSocketHandshake(WiFiClient& client, const String& request, const String& secKey);
void processControllerWebSocket();
void sendControllerPaymentEvent(const String& sessionId, const String& txId, int pulses);

#endif // CONTROLLER_WEB_SOCKET_H
