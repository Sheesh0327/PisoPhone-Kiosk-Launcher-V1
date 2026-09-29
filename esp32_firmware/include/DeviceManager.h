#ifndef DEVICE_MANAGER_H
#define DEVICE_MANAGER_H

#include <Arduino.h>
#include "Config.h"
#include "DeviceNetwork.h"

bool pairDeviceToSlot(int slotNum, String devId, String ip, String name);
bool unpairSlot(int slotNum);
int findSlotIndexForDevice(String devId, String ip);

bool isSlotActive(int slotIdx);

void updateDynamicDeviceList(String deviceId, String ip);
String getDeviceNameByIpOrId(String reqIp, String devId = "");

bool checkReplayProtection(String deviceId, unsigned long long newTs);
bool verifyTelemetryAuth(String deviceId, String tsStr, String sig);
void recordDeviceNonce(String deviceId, unsigned long long ts);
void updateDeviceTelemetry(String deviceId, String ip, int timeRemaining, int state, int battery = -1, bool charging = false, unsigned long long ts = 0);

int getTrackedTimeRemaining(String ip, unsigned long maxAgeMs = 40000, String devId = "");
int getTrackedBatteryLevel(String ip, String devId = "");
bool getTrackedChargingState(String ip, String devId = "");

String getFirstKnownIp();
String getIpFromDeviceId(String id);
String getPrimaryTerminalIp();

#endif // DEVICE_MANAGER_H
