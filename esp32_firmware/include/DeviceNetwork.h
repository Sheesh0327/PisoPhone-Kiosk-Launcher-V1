#ifndef DEVICE_NETWORK_H
#define DEVICE_NETWORK_H

#include <Arduino.h>
#include "Config.h"

struct AddTimeSummary {
    int matchedRecipients;
    int queuedRequests;
    int skippedInactive;
    int failedSubmissions;
};

AddTimeSummary sendAddTime(int64_t signedSeconds, String targetIp = "ALL", String txId = "");
void recordAdjustmentPending(const String& txId, const String& deviceId, int seconds);
void recordAdjustmentConfirmed(const String& txId, const String& deviceId, int seconds);
bool isAdjustmentConfirmed(const String& txId);
void triggerUniversalCoinEvent(int pulses, const String& targetDeviceId = "");
int getDeviceTimeRemainingSeconds(String targetIp, String *errOut = nullptr);

void sendCloudSnapshot();
bool sendAuthenticated(String ip, int port, String actionPath, String challengePath = "/challenge", String params = "", int timeoutMs = 1500);
bool retryPhonePayment(const String& targetDeviceId, int pulses, int creditSeconds, const String& txId);

String urlEncode(const String &str);

#endif // DEVICE_NETWORK_H
