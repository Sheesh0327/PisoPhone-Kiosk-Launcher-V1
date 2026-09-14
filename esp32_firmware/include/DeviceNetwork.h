#ifndef DEVICE_NETWORK_H
#define DEVICE_NETWORK_H

#include <Arduino.h>

extern uint64_t masterTimeOffsetMs;
extern bool isMasterTimeSet;

void updateMasterTime(uint64_t clientTimeMs);
uint64_t getCurrentMasterTimeMs();
bool checkReplayProtection(const String& deviceId, uint64_t timestamp);
void recordDeviceNonce(const String& deviceId, uint64_t timestamp);
bool verifyTelemetryAuth(const String& deviceId, const String& tsStr, const String& sig);

void sendAuthenticated(const String& ip, int port, const String& actionPath, const String& challengePath, const String& params, int timeoutMs = 2000);
void sendAddTime(int minutes, const String& targetIp = "ALL");
void retryPhonePayment(const String& ip, int pulses, int creditSeconds, const String& txId);
int getDeviceTimeRemainingSeconds(const String& ip, String* outErr = nullptr);
void sendCloudSnapshot();

#endif // DEVICE_NETWORK_H
