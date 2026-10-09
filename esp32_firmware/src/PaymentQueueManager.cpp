// Durable queue of coin payments that the phone (or controller) has not acknowledged yet.
// A coin is enqueued the moment it is detected, persisted to NVS, pushed to the target, and then
// retried every 10 s until acknowledged or expired. Arming is refused while storage is unwritable or
// the queue is nearly full, so a coin is never accepted that cannot be recorded.

#include "PaymentQueueManager.h"
#include "WebServerAccounts.h"
#include "ControllerWebSocket.h"
#include "DeviceNetwork.h"
#include "Config.h"
#include "Diagnostics.h"

#include <Preferences.h>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>

static const int MAX_PAYMENT_QUEUE_SIZE = 20;
static const uint32_t PAYMENT_RECORD_MAGIC = 0x50415932UL; // "PAY2"
static const unsigned long RETRY_INTERVAL_MS = 10000;
// A record the target never acknowledges (phone unpaired, replaced or factory reset) would
// otherwise be retried forever, and enough of them block all arming via isPaymentQueueFull().
static const uint64_t PAYMENT_TTL_MS = 24ULL * 60ULL * 60ULL * 1000ULL;
static const uint64_t PAYMENT_TTL_UNDER_PRESSURE_MS = 30ULL * 60ULL * 1000ULL;

static PaymentRecord paymentQueue[MAX_PAYMENT_QUEUE_SIZE];
static bool paymentSlotUsed[MAX_PAYMENT_QUEUE_SIZE] = {false};
static bool paymentSlotPersisted[MAX_PAYMENT_QUEUE_SIZE] = {false};
static unsigned long lastPersistAttemptMs[MAX_PAYMENT_QUEUE_SIZE] = {0};
static uint8_t persistRetryCount[MAX_PAYMENT_QUEUE_SIZE] = {0};
static unsigned long lastDispatchMs[MAX_PAYMENT_QUEUE_SIZE] = {0};
static unsigned long firstSeenMs[MAX_PAYMENT_QUEUE_SIZE] = {0};
static int activePaymentCount = 0;
static SemaphoreHandle_t paymentQueueMutex = nullptr;
static bool paymentStorageReady = false;

static String recordKey(int index) {
    char key[8];
    snprintf(key, sizeof(key), "rec_%02d", index);
    return String(key);
}

static bool validRecord(const PaymentRecord& rec) {
    return rec.magic == PAYMENT_RECORD_MAGIC && rec.txId[0] != '\0' && rec.targetId[0] != '\0' && rec.pulses > 0 &&
           (rec.ownerType == 1 || rec.ownerType == 2 || rec.ownerType == 3);
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

static void resetSlotLocked(int index) {
    memset(&paymentQueue[index], 0, sizeof(PaymentRecord));
    if (paymentSlotUsed[index]) activePaymentCount--;
    paymentSlotUsed[index] = false;
    paymentSlotPersisted[index] = false;
    lastPersistAttemptMs[index] = 0;
    persistRetryCount[index] = 0;
    lastDispatchMs[index] = 0;
    firstSeenMs[index] = 0;
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
    memset(firstSeenMs, 0, sizeof(firstSeenMs));
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
        if (length == sizeof(rec) && storage.getBytes(key.c_str(), &rec, sizeof(rec)) == sizeof(rec) &&
            validRecord(rec)) {
            paymentQueue[i] = rec;
            paymentSlotUsed[i] = true;
            paymentSlotPersisted[i] = true;
            firstSeenMs[i] = millis();
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

int getPendingPaymentCount() {
    lockQueue();
    int count = activePaymentCount;
    unlockQueue();
    return count;
}

bool canPerformRebootOrOta() {
    if (hasUnpersistedPayments()) {
        Serial.println("[PAY QUEUE] Reboot/OTA blocked: unpersisted transactions remain in RAM.");
        return false;
    }
    return true;
}

bool enqueuePendingPayment(const String& txId, const String& targetId, int pulses, CoinSlotOwnerType ownerType,
                           int creditSeconds) {
    if (txId.length() == 0 || txId.length() >= sizeof(((PaymentRecord*)0)->txId) || targetId.length() == 0 ||
        targetId.length() >= sizeof(((PaymentRecord*)0)->targetId) || pulses <= 0 ||
        (ownerType != CoinSlotOwnerType::PHONE && ownerType != CoinSlotOwnerType::CONTROLLER &&
         ownerType != CoinSlotOwnerType::GATEWAY)) {
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

    // A gateway (router) window keeps ONE record, its running total: the router collects and acknowledges the whole
    // window at its end, so a record per coin filled the queue and refused (lost) every coin after about the 20th for a
    // customer paying with many small coins. Phones and controllers acknowledge each coin, so they keep one per coin.
    if (ownerType == CoinSlotOwnerType::GATEWAY) {
        for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
            if (!paymentSlotUsed[i] || paymentQueue[i].ownerType != 3 || targetId != paymentQueue[i].targetId) continue;
            paymentQueue[i].pulses += pulses;
            PaymentRecord total = paymentQueue[i];
            diagCount(DiagCounter::PaymentsQueued);
            if (!persistRecord(i, total)) {
                // the total stays in RAM and is written again with backoff, like a new record
                diagCount(DiagCounter::PersistFailures);
                paymentStorageReady = false;
                paymentSlotPersisted[i] = false;
                lastPersistAttemptMs[i] = millis();
                persistRetryCount[i] = 1;
                unlockQueue();
                diagLog("[PAY QUEUE] NVS write failed for the gateway total of '%s'. Retained in RAM.\n",
                        targetId.c_str());
                return true;
            }
            paymentSlotPersisted[i] = true;
            paymentStorageReady = true;
            unlockQueue();
            diagLog("[PAY QUEUE] Gateway window '%s' now holds %d pulse(s).\n", targetId.c_str(), total.pulses);
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
    rec.ownerType = ownerType == CoinSlotOwnerType::CONTROLLER ? 2 : (ownerType == CoinSlotOwnerType::GATEWAY ? 3 : 1);
    rec.timestamp = getCurrentMasterTimeMs();

    // Retain in RAM under all conditions (never lose in-flight transactions or change tx_id)
    paymentQueue[freeIndex] = rec;
    paymentSlotUsed[freeIndex] = true;
    paymentSlotPersisted[freeIndex] = false;
    lastPersistAttemptMs[freeIndex] = millis();
    persistRetryCount[freeIndex] = 0;
    lastDispatchMs[freeIndex] = 0;
    firstSeenMs[freeIndex] = millis();
    activePaymentCount++;

    diagCount(DiagCounter::PaymentsQueued);

    // Attempt durable NVS flash persistence
    if (!persistRecord(freeIndex, rec)) {
        diagCount(DiagCounter::PersistFailures);
        paymentStorageReady = false;
        persistRetryCount[freeIndex] = 1;
        unlockQueue();
        diagLog("[PAY QUEUE] NVS write failed for tx_id='%s'. Retained in RAM; persistence will retry with backoff.\n",
                txId.c_str());
        return true;
    }

    paymentSlotPersisted[freeIndex] = true;
    paymentStorageReady = true;
    lastDispatchMs[freeIndex] = millis();
    unlockQueue();

    diagLog("[PAY QUEUE] Persisted tx_id='%s' for '%s' (%d pulse(s)).\n", txId.c_str(), targetId.c_str(), pulses);
    return true;
}

static bool acknowledgeMatchingPayment(const String& txId, const String* sessionId, uint8_t requiredOwnerType) {
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

    resetSlotLocked(foundIndex);
    unlockQueue();
    diagCount(DiagCounter::PaymentsAcked);
    diagLog("[PAY QUEUE] Acknowledged tx_id='%s'.\n", txId.c_str());
    return true;
}

// The seconds a queued phone payment is worth, or 0 when there is no such payment (already acknowledged, or not this
// phone's). Only reads: nothing is erased.
static int peekPhonePaymentCredit(const String& deviceId, const String& txId) {
    int credit = 0;
    lockQueue();
    for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
        if (paymentSlotUsed[i] && paymentQueue[i].ownerType == 1 && String(paymentQueue[i].txId) == txId &&
            String(paymentQueue[i].targetId) == deviceId) {
            credit = paymentQueue[i].creditSeconds;
            break;
        }
    }
    unlockQueue();
    return credit;
}

bool acknowledgePhonePayment(const String& deviceId, const String& txId) {
    if (deviceId.length() == 0 || txId.length() == 0) return false;
    // A player signed in on that phone also gets the coin's time. This is done BEFORE the queued payment is erased: if the
    // power fails in between, the box still holds the payment and offers it again, and the transaction id makes the
    // account count it once. Done after, a power cut would lose the coin's time from the account for good.
    int credit = peekPhonePaymentCredit(deviceId, txId);
    if (credit > 0) accountsOnPhonePaymentAcked(deviceId, txId, credit);
    return acknowledgeMatchingPayment(txId, &deviceId, 1);
}

int getPendingPhonePaymentsJson(const String& deviceId, String& outJsonArray) {
    if (deviceId.length() == 0) return 0;
    lockQueue();
    int foundCount = 0;
    outJsonArray = "[";
    for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
        if (paymentSlotUsed[i] && paymentQueue[i].ownerType == 1 &&
            (String(paymentQueue[i].targetId) == deviceId || strlen(paymentQueue[i].targetId) == 0)) {
            if (foundCount > 0) outJsonArray += ",";
            outJsonArray += "{\"tx_id\":\"" + String(paymentQueue[i].txId) + "\",";
            outJsonArray += "\"amount\":" + String(paymentQueue[i].pulses) + ",";
            outJsonArray += "\"seconds\":" + String(paymentQueue[i].creditSeconds) + "}";
            foundCount++;
        }
    }
    outJsonArray += "]";
    unlockQueue();
    return foundCount;
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

int getPendingGatewayPulses(const String& targetId) {
    if (targetId.length() == 0) return 0;
    lockQueue();
    int total = 0;
    for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
        if (paymentSlotUsed[i] && paymentQueue[i].ownerType == 3 && String(paymentQueue[i].targetId) == targetId) {
            total += paymentQueue[i].pulses;
        }
    }
    unlockQueue();
    return total;
}

int acknowledgeGatewayPayments(const String& targetId) {
    if (targetId.length() == 0) return 0;
    int pulses = 0;
    lockQueue();
    for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
        if (!paymentSlotUsed[i] || paymentQueue[i].ownerType != 3 || String(paymentQueue[i].targetId) != targetId) {
            continue;
        }
        if (!eraseRecord(i)) continue; // keep it queued if flash refuses; the gateway can ack again
        pulses += paymentQueue[i].pulses;
        resetSlotLocked(i);
        diagCount(DiagCounter::PaymentsAcked);
    }
    unlockQueue();
    return pulses;
}

int clearPaymentQueue() {
    lockQueue();
    int cleared = activePaymentCount;
    for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
        resetSlotLocked(i);
    }
    activePaymentCount = 0;
    Preferences storage;
    if (storage.begin("pay_queue", false)) {
        storage.clear();
        storage.end();
        paymentStorageReady = true;
    }
    unlockQueue();
    Serial.printf("[PAY QUEUE] Cleared %d pending payment(s).\n", cleared);
    return cleared;
}

static void evictExpiredPayments(unsigned long now) {
    uint64_t masterNow = getCurrentMasterTimeMs();
    lockQueue();
    uint64_t ttl = (activePaymentCount >= MAX_PAYMENT_QUEUE_SIZE - 2) ? PAYMENT_TTL_UNDER_PRESSURE_MS : PAYMENT_TTL_MS;
    for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
        if (!paymentSlotUsed[i]) continue;
        // Uptime age resets on reboot, so also use the master-clock age when both are known.
        uint64_t age = (uint64_t)(now - firstSeenMs[i]);
        uint64_t recTs = paymentQueue[i].timestamp;
        if (masterNow > 0 && recTs > 0 && masterNow > recTs && masterNow - recTs > age) {
            age = masterNow - recTs;
        }
        if (age < ttl) continue;
        if (paymentSlotPersisted[i] && !eraseRecord(i)) continue;
        diagLog("[PAY QUEUE] Evicted unacknowledged tx_id='%s' for '%s' (%d pulse(s)) after %llu s.\n",
                paymentQueue[i].txId, paymentQueue[i].targetId, paymentQueue[i].pulses, age / 1000ULL);
        resetSlotLocked(i);
        diagCount(DiagCounter::PaymentsEvicted);
    }
    unlockQueue();
}

// paymentStorageReady drops to false when an NVS write fails. It used to be restored only when a
// still-queued record later persisted, so if that record was acknowledged or evicted first the
// flag stayed false for good and every arm request was refused until a reboot. This probes the
// storage with a tiny write and restores the flag as soon as flash accepts writes again.
static void probePaymentStorage(unsigned long now) {
    static unsigned long lastProbeMs = 0;
    static uint32_t failedProbes = 0;
    if (now - lastProbeMs < 5000UL) return;

    lockQueue();
    bool needProbe = !paymentStorageReady;
    for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE && needProbe; i++) {
        // Records that are still waiting for persistence recover the flag themselves.
        if (paymentSlotUsed[i] && !paymentSlotPersisted[i]) needProbe = false;
    }
    unlockQueue();
    if (!needProbe) return;
    lastProbeMs = now;

    Preferences storage;
    bool ok = storage.begin("pay_queue", false);
    if (ok) {
        ok = storage.putUInt("probe", (uint32_t)now) > 0;
        if (ok) storage.remove("probe");
        storage.end();
    }
    if (ok) {
        lockQueue();
        paymentStorageReady = true;
        unlockQueue();
        diagLog("[PAY QUEUE] Payment storage recovered after %u failed probe(s); arming re-enabled.\n",
                (unsigned)failedProbes);
        failedProbes = 0;
    } else if (++failedProbes == 1 || failedProbes % 60 == 0) {
        diagLog("[PAY QUEUE] Payment storage still unwritable (probe failure #%u); arming stays disabled. "
                "Check NVS space.\n",
                (unsigned)failedProbes);
    }
}

void processPendingPaymentRetries() {
    unsigned long now = millis();
    probePaymentStorage(now);

    static unsigned long lastEvictionCheckMs = 0;
    if (now - lastEvictionCheckMs >= 60000UL) {
        lastEvictionCheckMs = now;
        evictExpiredPayments(now);
    }

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
            Serial.printf("[PAY QUEUE] Retrying NVS persistence for tx_id='%s' (attempt %d)...\n", rec.txId,
                          currentAttempt);
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
            retryPhonePayment(String(rec.targetId), rec.pulses, rec.creditSeconds, String(rec.txId));
        }
    }
}
