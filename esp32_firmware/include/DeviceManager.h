#ifndef DEVICE_MANAGER_H
#define DEVICE_MANAGER_H

#include <Arduino.h>
#include "Config.h"

bool pairDeviceToSlot(int slotNum, String devId, String ip, String name);
bool unpairSlot(int slotNum);
int findSlotIndexForDevice(String devId, String ip);

bool allocateCreditToSlot(int slotNum, String type, String &errorMsg);
int getSlotExpirationStatus(int slotIdx, uint64_t currentMs, int &outDaysLeft);

void updateDynamicDeviceList(String deviceId, String ip);
String getDeviceNameByIpOrId(String reqIp, String devId = "");

bool checkReplayProtection(String deviceId, unsigned long long newTs);
bool verifyTelemetryAuth(String deviceId, String tsStr, String sig);
void recordDeviceNonce(String deviceId, unsigned long long ts);
void updateDeviceTelemetry(String deviceId, String ip, int timeRemaining, int state, int battery = 100, bool charging = false, unsigned long long ts = 0);

int getTrackedTimeRemaining(String ip, unsigned long maxAgeMs = 15000, String devId = "");
int getTrackedBatteryLevel(String ip, String devId = "");
bool getTrackedChargingState(String ip, String devId = "");

String getFirstKnownIp();
String getIpFromDeviceId(String id);
String getPrimaryTerminalIp();

void sendAddTime(int minutes, String targetIp, String txId = "");
void triggerCoinEvent();
void triggerUniversalCoinEvent(int pulses);
int getDeviceTimeRemainingSeconds(String targetIp, String *errOut = nullptr);

void sendCloudSnapshot();
void sendAuthenticated(String ip, int port, String actionPath, String challengePath = "/challenge", String params = "", int timeoutMs = 1500);

String urlEncode(const String &str);

#endif // DEVICE_MANAGER_H
