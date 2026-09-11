#ifndef DEVICE_NETWORK_H
#define DEVICE_NETWORK_H

#include <Arduino.h>
#include "Config.h"

void sendAddTime(int minutes, String targetIp, String txId = "");
void triggerCoinEvent();
void triggerUniversalCoinEvent(int pulses);
int getDeviceTimeRemainingSeconds(String targetIp, String *errOut = nullptr);

void sendCloudSnapshot();
void sendAuthenticated(String ip, int port, String actionPath, String challengePath = "/challenge", String params = "", int timeoutMs = 1500);

String urlEncode(const String &str);

#endif // DEVICE_NETWORK_H
