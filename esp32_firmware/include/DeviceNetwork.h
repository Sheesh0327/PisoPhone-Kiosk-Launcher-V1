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

AddTimeSummary sendAddTime(int64_t signedSeconds, String targetIp = "ALL", String txId = "", uint8_t opKind = 0);
void recordAdjustmentPending(const String& txId, const String& deviceId, int seconds);
void recordAdjustmentConfirmed(const String& txId, const String& deviceId, int seconds);
bool isAdjustmentConfirmed(const String& txId);
bool triggerUniversalCoinEvent(int pulses, const String& targetDeviceId = "");
int getDeviceTimeRemainingSeconds(String targetIp, String *errOut = nullptr);

void sendCloudSnapshot();
bool sendAuthenticated(String ip, int port, String actionPath, String challengePath = "/challenge", String params = "", int timeoutMs = 1500);
bool retryPhonePayment(const String& targetDeviceId, int pulses, int creditSeconds, const String& txId, uint8_t opKind = 1, uint64_t boxEpoch = 0, uint64_t phoneEpoch = 0);

String urlEncode(const String &str);

#endif // DEVICE_NETWORK_H
