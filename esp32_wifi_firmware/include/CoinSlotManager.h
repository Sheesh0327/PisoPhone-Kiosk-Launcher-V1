#ifndef COIN_SLOT_MANAGER_H
#define COIN_SLOT_MANAGER_H

#include <Arduino.h>
#include <functional>

typedef std::function<void(const String& sessionId, int pulses)> CoinPaymentCallback;
typedef std::function<void(const String& sessionId, const char* reason)> CoinSessionEndCallback;

enum class CoinSlotOwnerType { ANY, GATEWAY };

enum class CoinSlotState {
    IDLE,    // No session, relay OFF, acceptor disabled
    ARMED,   // Active session running, relay ON, accepting coins
    DRAINING // Session closing/timed-out, relay ON, waiting for in-flight pulses
};

void initCoinSlotManager();
bool reserveCoinSlot(const String& sessionId, CoinSlotOwnerType ownerType, unsigned long ttlMs,
                     CoinPaymentCallback onPayment = nullptr, CoinSessionEndCallback onSessionEnd = nullptr);
void releaseCoinSlot(const String& sessionId, CoinSlotOwnerType ownerType = CoinSlotOwnerType::ANY, bool force = false,
                     const char* reason = "RELEASED");
bool refreshCoinSlotTtl(const String& sessionId, CoinSlotOwnerType ownerType, unsigned long ttlMs);
void processCoinSlotSession();

bool isCoinSlotArmed();
unsigned long getCoinSlotArmedUntilMs();
unsigned long getCoinSlotSettleRemainingMs();
bool isCoinSlotBusy(const String& sessionId, CoinSlotOwnerType ownerType = CoinSlotOwnerType::ANY);
CoinSlotState getCoinSlotState();
String getActiveCoinSessionId();
CoinSlotOwnerType getActiveCoinOwnerType();

#endif // COIN_SLOT_MANAGER_H
