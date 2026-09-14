#ifndef COIN_SLOT_MANAGER_H
#ifndef COIN_SLOT_MANAGER_H
#define COIN_SLOT_MANAGER_H

#include <Arduino.h>
#include <functional>

enum class CoinSlotOwnerType : uint8_t {
    NONE = 0,
    PHONE = 1,
    CONTROLLER = 2
};

typedef std::function<void(const String& sessionId, int pulses)> CoinPulseCallback;
typedef std::function<void(const String& sessionId, const char* reason)> CoinSlotReleaseCallback;

void initCoinSlotManager();
void applyCoinSlotHardwareConfig();

bool reserveCoinSlot(const String& sessionId, CoinSlotOwnerType ownerType,
                     unsigned long timeoutMs,
                     CoinPulseCallback pulseCb,
                     CoinSlotReleaseCallback releaseCb);
bool isCoinSlotBusy(const String& requestingSessionId = "",
                    CoinSlotOwnerType requestingOwnerType = CoinSlotOwnerType::NONE);
String getActiveCoinSessionId();
CoinSlotOwnerType getActiveCoinOwnerType();
bool refreshCoinSlotTtl(const String& sessionId, CoinSlotOwnerType ownerType,
                        unsigned long newTimeoutMs);
bool releaseCoinSlot(const String& sessionId, CoinSlotOwnerType ownerType,
                     bool force = false);

bool isDrainSessionActive();
String getDrainSessionId();

void onUniversalCoinPulseISR();
void triggerUniversalCoinEvent(int pulses = 1, const String& requestingSessionId = "");
void processCoinSlotLoop();

#endif // COIN_SLOT_MANAGER_H
