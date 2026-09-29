#ifndef CONTROLLER_WEB_SOCKET_H
#define CONTROLLER_WEB_SOCKET_H

#include <Arduino.h>
#include <WiFiClient.h>

/**
 * Handles incoming Controller WebSocket handshake on port 81.
 * Authenticates via HMAC with controller credential (sharedSecret/webPassword/DEFAULT_ADMIN_PW),
 * validates replay timestamp, checks CoinSlotManager single-session mutex, and arms slot.
 *
 * @param client Incoming TCP client connection.
 * @param request HTTP request line and query string.
 * @param secKey Sec-WebSocket-Key from HTTP headers.
 * @return true if handshake was accepted and upgraded, false if rejected.
 */
bool handleControllerWebSocketHandshake(WiFiClient& client, const String& request, const String& secKey);

/**
 * Main loop handler for active Controller WebSocket connection.
 * Manages frame reads, keep-alive TTL refreshes, explicit release ("DONE"/"CLOSE"),
 * and client disconnections.
 */
void processControllerWebSocket();

/**
 * Returns true if a controller WebSocket client is currently connected.
 */
bool isControllerWsConnected();

/**
 * Returns the session ID of the currently connected controller client.
 */
String getControllerSessionId();

/** Re-delivers a retained controller payment only to its original session. */
bool sendControllerPaymentEvent(const String& sessionId, const String& txId, int pulses);

#endif // CONTROLLER_WEB_SOCKET_H
