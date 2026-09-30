#ifndef DEVICE_NETWORK_H
#define DEVICE_NETWORK_H

#include <Arduino.h>
#include "Config.h"

void sendAddTime(int minutes, String targetIp, String txId = "");
void triggerUniversalCoinEvent(int pulses, const String& targetDeviceId = "");
void recordSessionCoinTx(const String& devId, const String& txId, int pulses, int seconds, double amount);
int getDeviceTimeRemainingSeconds(String targetIp, String *errOut = nullptr);

void sendCloudSnapshot();
bool sendAuthenticated(String ip, int port, String actionPath, String challengePath = "/challenge", String params = "", int timeoutMs = 1500);
bool retryPhonePayment(const String& targetDeviceId, int pulses, int creditSeconds, const String& txId);

String urlEncode(const String &str);

#endif // DEVICE_NETWORK_H
