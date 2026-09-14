#ifndef DEVICE_MANAGER_H
#define DEVICE_MANAGER_H

#include <Arduino.h>
#include "Config.h"

struct DeviceConfig {
    String id;
    String ip;
    String name;
};

bool parseDeviceEntry(const String& entry, DeviceConfig& cfg);
int findSlotIndexForDevice(const String& deviceId, const String& ip);
bool isSlotActive(int slotIndex);
bool pairDeviceToSlot(int slotNum, const String& deviceId, const String& ip, const String& name);
bool unpairSlot(int slotNum);
String getDeviceNameByIpOrId(const String& ip, const String& deviceId);

void updateDeviceTelemetry(const String& deviceId, const String& ip, int timeRem, int state, int battery, bool charging, unsigned long long timestamp);
int getTrackedTimeRemaining(const String& ip, unsigned long maxAgeMs = 15000, const String& deviceId = "");
int getTrackedBatteryLevel(const String& ip, const String& deviceId = "");
bool getTrackedChargingState(const String& ip, const String& deviceId = "");

#endif // DEVICE_MANAGER_H
