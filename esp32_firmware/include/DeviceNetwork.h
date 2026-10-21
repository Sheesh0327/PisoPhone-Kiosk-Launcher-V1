#ifndef DEVICE_NETWORK_H
#define DEVICE_NETWORK_H

#include <Arduino.h>
#include "Config.h"
#include "CoinSlotManager.h"

#if PISO_HAS_PHONES
void sendAddTime(int minutes, String targetIp, String txId = "");
#endif
// Adds coins to the lifetime/session revenue counters (persisted by the revenue debounce).
// Every accepted coin, whoever it is credited to, goes through here.
#if PISO_HAS_PHONES
void recordCoinRevenue(int pulses, CoinSlotOwnerType owner = CoinSlotOwnerType::PHONE);
#else
void recordCoinRevenue(int pulses, CoinSlotOwnerType owner = CoinSlotOwnerType::GATEWAY);
#endif
#if PISO_HAS_PHONES
void triggerUniversalCoinEvent(int pulses, const String& targetDeviceId = "");
void recordSessionCoinTx(const String& devId, const String& txId, int pulses, int seconds, double amount);
int getDeviceTimeRemainingSeconds(String targetIp, String* errOut = nullptr);

#endif
void sendCloudSnapshot();
#if PISO_HAS_PHONES
bool sendAuthenticated(String ip, int port, String actionPath, String challengePath = "/challenge", String params = "",
                       int timeoutMs = 1500);
bool retryPhonePayment(const String& targetDeviceId, int pulses, int creditSeconds, const String& txId);

#endif
String urlEncode(const String& str);

#endif // DEVICE_NETWORK_H
