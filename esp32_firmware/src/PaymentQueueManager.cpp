#include "PaymentQueueManager.h"
#include "CoinSlotManager.h"
#include "HardwareManager.h"
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
static bool paymentSlotPersisted[MAX_PAYMENT_QUEUE_SIZE] = {false};
static unsigned long lastPersistAttemptMs[MAX_PAYMENT_QUEUE_SIZE] = {0};
static uint8_t persistRetryCount[MAX_PAYMENT_QUEUE_SIZE] = {0};
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
    memset(paymentSlotPersisted, 0, sizeof(paymentSlotPersisted));
    memset(lastPersistAttemptMs, 0, sizeof(lastPersistAttemptMs));
    memset(persistRetryCount, 0, sizeof(persistRetryCount));
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
            paymentSlotPersisted[i] = true;
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
    // Reserve at least 2 slots for in-flight pulses so they can safely drain
    bool full = !paymentStorageReady || (activePaymentCount >= MAX_PAYMENT_QUEUE_SIZE - 2);
    unlockQueue();
    return full;
}

bool isPaymentStorageReady() {
    lockQueue();
    bool ready = paymentStorageReady;
    unlockQueue();
    return ready;
}

bool hasUnpersistedPayments() {
    lockQueue();
    bool unpersisted = false;
    for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
        if (paymentSlotUsed[i] && !paymentSlotPersisted[i]) {
            unpersisted = true;
            break;
        }
    }
    unlockQueue();
    return unpersisted;
}

static bool maintenanceMode = false;

void setMaintenanceMode(bool enable) {
    maintenanceMode = enable;
}

bool isMaintenanceMode() {
    return maintenanceMode;
}

bool canPerformRebootOrOta() {
    if (hasUnpersistedPayments()) {
        Serial.println("[PAY QUEUE] Reboot/OTA blocked: unpersisted transactions remain in RAM.");
        return false;
    }
    if (getCoinSlotState() != CoinSlotState::IDLE) {
        Serial.println("[PAY QUEUE] Reboot/OTA blocked: coin slot state is not IDLE.");
        return false;
    }
    if (isCoinSlotArmed()) {
        Serial.println("[PAY QUEUE] Reboot/OTA blocked: coin slot hardware is currently ARMED.");
        return false;
    }
    if (isrUniversalPulseCount > 0) {
        Serial.println("[PAY QUEUE] Reboot/OTA blocked: in-flight coin pulses pending in ISR buffer.");
        return false;
    }
    if (getSessionAccumulatedPulses() > 0) {
        Serial.println("[PAY QUEUE] Reboot/OTA blocked: accumulated coin pulses in buffer.");
        return false;
    }
    return true;
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

    // Retain in RAM under all conditions (never lose in-flight transactions or change tx_id)
    paymentQueue[freeIndex] = rec;
    paymentSlotUsed[freeIndex] = true;
    paymentSlotPersisted[freeIndex] = false;
    lastPersistAttemptMs[freeIndex] = millis();
    persistRetryCount[freeIndex] = 0;
    lastDispatchMs[freeIndex] = 0;
    activePaymentCount++;

    // Attempt durable NVS flash persistence
    if (!persistRecord(freeIndex, rec)) {
        paymentStorageReady = false;
        persistRetryCount[freeIndex] = 1;
        unlockQueue();
        Serial.printf("[PAY QUEUE] NVS write failed for tx_id='%s'. Retained in RAM; persistence will retry with backoff.\n", txId.c_str());
        return true;
    }

    paymentSlotPersisted[freeIndex] = true;
    paymentStorageReady = true;
    lastDispatchMs[freeIndex] = millis();
    unlockQueue();

    Serial.printf("[PAY QUEUE] Persisted tx_id='%s' for '%s' (%d pulse(s)). Dispatching via single payment path...\n",
                  txId.c_str(), targetId.c_str(), pulses);

    if (rec.ownerType == 2) {
        sendControllerPaymentEvent(String(rec.targetId), String(rec.txId), rec.pulses);
    } else if (rec.ownerType == 1) {
        retryPhonePayment(String(rec.targetId), rec.pulses, rec.creditSeconds, String(rec.txId));
    }
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
    paymentSlotPersisted[foundIndex] = false;
    lastPersistAttemptMs[foundIndex] = 0;
    persistRetryCount[foundIndex] = 0;
    lastDispatchMs[foundIndex] = 0;
    activePaymentCount--;
    unlockQueue();
    Serial.printf("[PAY QUEUE] Acknowledged tx_id='%s'.\n", txId.c_str());
    return true;
}

bool acknowledgePhonePayment(const String& deviceId, const String& txId, int expectedPulses, const String& status) {
    if (deviceId.length() == 0 || txId.length() == 0) return false;
    return acknowledgeMatchingPayment(txId, &deviceId, 1);
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
        if (paymentSlotUsed[i] && paymentSlotPersisted[i] && paymentQueue[i].ownerType == 2 &&
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

    // 1. Retry unpersisted records in RAM with exponential backoff
    for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
        PaymentRecord rec;
        bool needPersist = false;
        int currentAttempt = 0;

        lockQueue();
        if (paymentSlotUsed[i] && !paymentSlotPersisted[i]) {
            uint8_t count = persistRetryCount[i] > 5 ? 5 : persistRetryCount[i];
            unsigned long backoffMs = 1000UL << count; // 1s, 2s, 4s, 8s, 16s, 32s
            if ((long)(now - (lastPersistAttemptMs[i] + backoffMs)) >= 0) {
                rec = paymentQueue[i];
                lastPersistAttemptMs[i] = now;
                currentAttempt = persistRetryCount[i] + 1;
                needPersist = true;
            }
        }
        unlockQueue();

        if (needPersist) {
            Serial.printf("[PAY QUEUE] Retrying NVS persistence for tx_id='%s' (attempt %d)...\n",
                          rec.txId, currentAttempt);
            if (persistRecord(i, rec)) {
                lockQueue();
                paymentSlotPersisted[i] = true;
                paymentStorageReady = true;
                lastDispatchMs[i] = millis();
                unlockQueue();
                Serial.printf("[PAY QUEUE] NVS persistence recovered for tx_id='%s'.\n", rec.txId);
            } else {
                lockQueue();
                if (persistRetryCount[i] < 10) persistRetryCount[i]++;
                unlockQueue();
                Serial.printf("[PAY QUEUE] Persistence retry failed for tx_id='%s'. Retained in RAM.\n", rec.txId);
            }
        }
    }

    // 2. Dispatch / retry only durably persisted records
    for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
        PaymentRecord rec;
        bool shouldDispatch = false;

        lockQueue();
        if (paymentSlotUsed[i] && paymentSlotPersisted[i] &&
            (lastDispatchMs[i] == 0 || (long)(now - (lastDispatchMs[i] + RETRY_INTERVAL_MS)) >= 0)) {
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
