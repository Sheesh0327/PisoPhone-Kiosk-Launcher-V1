#ifndef PAYMENT_QUEUE_MANAGER_H
#define PAYMENT_QUEUE_MANAGER_H

#include <Arduino.h>
#include "CoinSlotManager.h"

struct PaymentRecord {
    uint32_t magic;
    char txId[40];
    char targetId[40];
    uint16_t pulses;
    uint32_t creditSeconds;
    uint8_t ownerType;
    uint64_t timestamp;
};

void initPaymentQueue();
bool isPaymentQueueFull();
int getPendingPaymentCount();
bool enqueuePendingPayment(const String& txId, const String& targetId, int pulses,
                           CoinSlotOwnerType ownerType, int creditSeconds);
bool acknowledgePayment(const String& txId);
bool acknowledgeControllerPayment(const String& sessionId, const String& txId);
void dispatchPendingControllerPayments(const String& sessionId);
void processPendingPaymentRetries();

#endif // PAYMENT_QUEUE_MANAGER_H
