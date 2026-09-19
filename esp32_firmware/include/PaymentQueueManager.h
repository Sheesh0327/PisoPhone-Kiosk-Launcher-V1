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
    uint8_t ownerType; // 1 = PHONE, 2 = CONTROLLER
    uint64_t timestamp;
};

void initPaymentQueue();
bool enqueuePendingPayment(const String& txId, const String& targetId, int pulses,
                           CoinSlotOwnerType ownerType, int creditSeconds = 0);
bool acknowledgeControllerPayment(const String& sessionId, const String& txId);
bool isPaymentQueueFull();
bool isPaymentStorageReady();
bool hasUnpersistedPayments();
void setMaintenanceMode(bool enable);
bool isMaintenanceMode();
bool canPerformRebootOrOta();
int getPendingPaymentCount();
bool acknowledgePhonePayment(
    const String& deviceId,
    const String& txId,
    int acknowledgedPulses,
    int acknowledgedSeconds,
    const String& status);
void dispatchPendingControllerPayments(const String& sessionId);
void processPendingPaymentRetries();

#endif // PAYMENT_QUEUE_MANAGER_H
