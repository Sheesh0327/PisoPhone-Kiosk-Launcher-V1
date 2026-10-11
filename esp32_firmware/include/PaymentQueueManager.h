#ifndef PAYMENT_QUEUE_MANAGER_H
#define PAYMENT_QUEUE_MANAGER_H

#include <Arduino.h>
#include <stdint.h>
#include "CoinSlotManager.h"

struct PaymentRecord {
    uint32_t magic;
    char txId[64];
    char targetId[97];
    int pulses;
    int creditSeconds;
    uint8_t ownerType; // 1 = PHONE, 2 = CONTROLLER, 3 = GATEWAY
    uint64_t timestamp;
};

void initPaymentQueue();
bool enqueuePendingPayment(const String& txId, const String& targetId, int pulses, CoinSlotOwnerType ownerType,
                           int creditSeconds = 0);
bool acknowledgeControllerPayment(const String& sessionId, const String& txId);
bool isPaymentQueueFull();
bool isPaymentStorageReady();
bool hasUnpersistedPayments();
int getPendingPaymentCount();
bool canPerformRebootOrOta();
bool acknowledgePhonePayment(const String& deviceId, const String& txId);
int getPendingPhonePaymentsJson(const String& deviceId, String& outJsonArray);
void dispatchPendingControllerPayments(const String& sessionId);
void processPendingPaymentRetries();
// Network gateway (router) payments are not pushed anywhere: the gateway polls for them and
// acknowledges once it has granted access. These two read and clear one gateway session's coins.
int getPendingGatewayPulses(const String& targetId);
int acknowledgeGatewayPayments(const String& targetId);
// Removes every queued payment. Each is written to the orphaned-payments ledger first (`recordOrphans`, always except for the
// owner's full wipe, which starts a new ledger); returns how many were removed, or -1 when the ledger could not be written and
// nothing was changed.
int clearPaymentQueue(bool recordOrphans = true);

#endif // PAYMENT_QUEUE_MANAGER_H
