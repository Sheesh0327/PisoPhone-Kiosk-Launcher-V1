#ifndef DEVICE_MANAGER_H
#define DEVICE_MANAGER_H

#include <Arduino.h>
#include "Config.h"
#include "DeviceNetwork.h"

bool pairDeviceToSlot(int slotNum, String devId, String ip, String name);
// Why the last pairDeviceToSlot said no because of the box type (empty when it was something else, or it succeeded)
const String& pairRefusalReason();
bool unpairSlot(int slotNum);
int findSlotIndexForDevice(String devId, String ip);

bool isSlotActive(int slotIdx);

void updateDynamicDeviceList(String deviceId, String ip);
String getDeviceNameByIpOrId(String reqIp, String devId = "");

bool checkReplayProtection(String deviceId, unsigned long long newTs);
bool verifyTelemetryAuth(String deviceId, String tsStr, String sig);
// Why a signed request is refused, in words for the diagnostics log; nullptr when it is accepted.
const char* telemetryAuthFailure(String deviceId, String tsStr, String sig);
void recordDeviceNonce(String deviceId, unsigned long long ts);
void updateDeviceTelemetry(String deviceId, String ip, int timeRemaining, int state, int battery = -1,
                           bool charging = false, unsigned long long ts = 0, bool isApp = false, String name = "");

int getTrackedTimeRemaining(String ip, unsigned long maxAgeMs = 40000, String devId = "");
int getTrackedBatteryLevel(String ip, String devId = "");
bool getTrackedChargingState(String ip, String devId = "");
// How often the phone's app restarted after a crash in the last 24 hours, as the phone reports it in its signed heartbeat.
void setTrackedRestarts(String ip, String devId, int restarts);
int getTrackedRestarts(String ip, String devId = "");

String getIpFromDeviceId(String id);
String getDeviceIdFromIp(String ip);

#endif // DEVICE_MANAGER_H
