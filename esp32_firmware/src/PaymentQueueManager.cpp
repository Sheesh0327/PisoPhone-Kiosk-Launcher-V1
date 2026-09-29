#include "PaymentQueueManager.h"
#include "ControllerWebSocket.h"
#include "DeviceNetwork.h"
#include "Config.h"
#include <Preferences.h>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>

static const int MAX_PAYMENT_QUEUE_SIZE = 20;
static const uint32_t PAYMENT_RECORD_MAGIC = 0x50415932UL; // "PAY2"
static const unsigned long RETRY_INTERVAL_MS = 10000;

static PaymentRecord paymentQueue[MAX_PAYMENT_QUEUE_SIZE];
static bool paymentSlotUsed[MAX_PAYMENT_QUEUE_SIZE] = {false};
static unsigned long lastDispatchMs[MAX_PAYMENT_QUEUE_SIZE] = {0};
static int activePaymentCount = 0;
static SemaphoreHandle_t paymentQueueMutex = nullptr;
static bool paymentStorageReady = false;

static String recordKey(int index) {
    char key[8];
    snprintf(key, sizeof(key), "rec_%02d", index);
    return String(key);
}

static bool validRecord(const PaymentRecord& rec) {
    return rec.magic == PAYMENT_RECORD_MAGIC && rec.txId[0] != '\0' &&
           rec.targetId[0] != '\0' && rec.pulses > 0 &&
           (rec.ownerType == 1 || rec.ownerType == 2);
}

static bool persistRecord(int index, const PaymentRecord& rec) {
    Preferences storage;
    if (!storage.begin("pay_queue", false)) return false;
    String key = recordKey(index);
    size_t written = storage.putBytes(key.c_str(), &rec, sizeof(rec));
    storage.end();
    return written == sizeof(rec);
}

static bool eraseRecord(int index) {
    Preferences storage;
    if (!storage.begin("pay_queue", false)) return false;
    String key = recordKey(index);
    bool removed = !storage.isKey(key.c_str()) || storage.remove(key.c_str());
    storage.end();
    return removed;
}

static void lockQueue() {
    if (paymentQueueMutex != nullptr) xSemaphoreTake(paymentQueueMutex, portMAX_DELAY);
}

static void unlockQueue() {
    if (paymentQueueMutex != nullptr) xSemaphoreGive(paymentQueueMutex);
}

void initPaymentQueue() {
    if (paymentQueueMutex == nullptr) paymentQueueMutex = xSemaphoreCreateMutex();
    if (paymentQueueMutex == nullptr) {
        Serial.println("[PAY QUEUE] Failed to create queue mutex; payments disabled.");
        paymentStorageReady = false;
        return;
    }

    lockQueue();
    memset(paymentQueue, 0, sizeof(paymentQueue));
    memset(paymentSlotUsed, 0, sizeof(paymentSlotUsed));
    memset(lastDispatchMs, 0, sizeof(lastDispatchMs));
    activePaymentCount = 0;
    paymentStorageReady = false;

    Preferences storage;
    if (!storage.begin("pay_queue", false)) {
        Serial.println("[PAY QUEUE] Failed to open persistent payment storage.");
        unlockQueue();
        return;
    }
    paymentStorageReady = true;

    for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
        String key = recordKey(i);
        size_t length = storage.getBytesLength(key.c_str());
        if (length == 0) continue;

        PaymentRecord rec;
        memset(&rec, 0, sizeof(rec));
        if (length == sizeof(rec) &&
            storage.getBytes(key.c_str(), &rec, sizeof(rec)) == sizeof(rec) &&
            validRecord(rec)) {
            paymentQueue[i] = rec;
            paymentSlotUsed[i] = true;
            activePaymentCount++;
            Serial.printf("[PAY QUEUE] Restored tx_id='%s' for '%s'.\n", rec.txId, rec.targetId);
        } else {
            storage.remove(key.c_str());
            Serial.printf("[PAY QUEUE] Removed invalid record in slot %d.\n", i);
        }
    }
    storage.end();
    Serial.printf("[PAY QUEUE] Ready with %d pending payment(s).\n", activePaymentCount);
    unlockQueue();
}

bool isPaymentQueueFull() {
    lockQueue();
    bool full = !paymentStorageReady || activePaymentCount >= MAX_PAYMENT_QUEUE_SIZE;
    unlockQueue();
    return full;
}

int getPendingPaymentCount() {
    lockQueue();
    int count = activePaymentCount;
    unlockQueue();
    return count;
}

bool enqueuePendingPayment(const String& txId, const String& targetId, int pulses,
                           CoinSlotOwnerType ownerType, int creditSeconds) {
    if (txId.length() == 0 || txId.length() >= sizeof(((PaymentRecord*)0)->txId) ||
        targetId.length() == 0 || targetId.length() >= sizeof(((PaymentRecord*)0)->targetId) ||
        pulses <= 0 ||
        (ownerType != CoinSlotOwnerType::PHONE && ownerType != CoinSlotOwnerType::CONTROLLER)) {
        Serial.println("[PAY QUEUE] Rejected invalid payment record.");
        return false;
    }

    lockQueue();
    if (!paymentStorageReady) {
        unlockQueue();
        Serial.println("[PAY QUEUE] Persistent storage unavailable; payment rejected.");
        return false;
    }
    for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
        if (paymentSlotUsed[i] && String(paymentQueue[i].txId) == txId) {
            unlockQueue();
            return true;
        }
    }

    int freeIndex = -1;
    for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
        if (!paymentSlotUsed[i]) {
            freeIndex = i;
            break;
        }
    }
    if (freeIndex < 0) {
        unlockQueue();
        Serial.println("[PAY QUEUE] Cannot record payment: queue is full.");
        return false;
    }

    PaymentRecord rec;
    memset(&rec, 0, sizeof(rec));
    rec.magic = PAYMENT_RECORD_MAGIC;
    strncpy(rec.txId, txId.c_str(), sizeof(rec.txId) - 1);
    strncpy(rec.targetId, targetId.c_str(), sizeof(rec.targetId) - 1);
    rec.pulses = pulses;
    rec.creditSeconds = creditSeconds;
    rec.ownerType = ownerType == CoinSlotOwnerType::CONTROLLER ? 2 : 1;
    rec.timestamp = getCurrentMasterTimeMs();

    if (!persistRecord(freeIndex, rec)) {
        paymentStorageReady = false;
        unlockQueue();
        Serial.printf("[PAY QUEUE] Failed to persist tx_id='%s'.\n", txId.c_str());
        return false;
    }

    paymentQueue[freeIndex] = rec;
    paymentSlotUsed[freeIndex] = true;
    lastDispatchMs[freeIndex] = millis();
    activePaymentCount++;
    unlockQueue();

    Serial.printf("[PAY QUEUE] Persisted tx_id='%s' for '%s' (%d pulse(s)).\n",
                  txId.c_str(), targetId.c_str(), pulses);
    return true;
}

static bool acknowledgeMatchingPayment(const String& txId, const String* sessionId,
                                       uint8_t requiredOwnerType) {
    if (txId.length() == 0) return false;

    lockQueue();
    int foundIndex = -1;
    for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
        if (!paymentSlotUsed[i] || String(paymentQueue[i].txId) != txId) continue;
        if (requiredOwnerType != 0 && paymentQueue[i].ownerType != requiredOwnerType) continue;
        if (sessionId != nullptr && String(paymentQueue[i].targetId) != *sessionId) continue;
        foundIndex = i;
        break;
    }
    if (foundIndex < 0) {
        unlockQueue();
        return false;
    }

    if (!eraseRecord(foundIndex)) {
        unlockQueue();
        Serial.printf("[PAY QUEUE] Failed to erase acknowledged tx_id='%s'.\n", txId.c_str());
        return false;
    }

    memset(&paymentQueue[foundIndex], 0, sizeof(PaymentRecord));
    paymentSlotUsed[foundIndex] = false;
    lastDispatchMs[foundIndex] = 0;
    activePaymentCount--;
    unlockQueue();
    Serial.printf("[PAY QUEUE] Acknowledged tx_id='%s'.\n", txId.c_str());
    return true;
}

bool acknowledgePayment(const String& txId) {
    return acknowledgeMatchingPayment(txId, nullptr, 0);
}

bool acknowledgeControllerPayment(const String& sessionId, const String& txId) {
    return acknowledgeMatchingPayment(txId, &sessionId, 2);
}

void dispatchPendingControllerPayments(const String& sessionId) {
    if (sessionId.length() == 0) return;

    for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
        PaymentRecord rec;
        bool matches = false;
        lockQueue();
        if (paymentSlotUsed[i] && paymentQueue[i].ownerType == 2 &&
            String(paymentQueue[i].targetId) == sessionId) {
            rec = paymentQueue[i];
            lastDispatchMs[i] = millis();
            matches = true;
        }
        unlockQueue();

        if (matches) {
            sendControllerPaymentEvent(sessionId, String(rec.txId), rec.pulses);
        }
    }
}

void processPendingPaymentRetries() {
    unsigned long now = millis();

    for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
        PaymentRecord rec;
        bool shouldDispatch = false;

        lockQueue();
        if (paymentSlotUsed[i] &&
            (lastDispatchMs[i] == 0 || now - lastDispatchMs[i] >= RETRY_INTERVAL_MS)) {
            rec = paymentQueue[i];
            lastDispatchMs[i] = now;
            shouldDispatch = true;
        }
        unlockQueue();

        if (!shouldDispatch) continue;
        if (rec.ownerType == 2) {
            sendControllerPaymentEvent(String(rec.targetId), String(rec.txId), rec.pulses);
        } else if (rec.ownerType == 1) {
            retryPhonePayment(String(rec.targetId), rec.pulses,
                              rec.creditSeconds, String(rec.txId));
        }
    }
}
