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
static const unsigned long RETRY_INTERVAL_MS = 10000;

static PaymentRecord paymentQueue[MAX_PAYMENT_QUEUE_SIZE];
static bool paymentSlotUsed[MAX_PAYMENT_QUEUE_SIZE] = {false};
static bool paymentSlotPersisted[MAX_PAYMENT_QUEUE_SIZE] = {false};
static unsigned long lastPersistAttemptMs[MAX_PAYMENT_QUEUE_SIZE] = {0};
static uint8_t persistRetryCount[MAX_PAYMENT_QUEUE_SIZE] = {0};
static unsigned long lastDispatchMs[MAX_PAYMENT_QUEUE_SIZE] = {0};
static int activePaymentCount = 0;
static int quarantineRecordCount = 0;
static SemaphoreHandle_t paymentQueueMutex = nullptr;
static bool paymentStorageReady = false;

uint32_t computeRecordCrc32(const PaymentRecord& rec) {
    const uint8_t* data = (const uint8_t*)&rec;
    size_t length = offsetof(PaymentRecord, crc32);
    uint32_t crc = 0xFFFFFFFF;
    for (size_t i = 0; i < length; i++) {
        crc ^= data[i];
        for (int j = 0; j < 8; j++) {
            crc = (crc >> 1) ^ (0xEDB88320 & -(crc & 1));
        }
    }
    return ~crc;
}

String generateCollisionResistantTxId(const char* prefix) {
    uint64_t ts = (uint64_t)getCurrentMasterTimeMs();
#if defined(ESP32) || defined(ARDUINO)
    uint32_t r1 = esp_random();
    uint32_t r2 = esp_random();
#else
    uint32_t r1 = (uint32_t)rand();
    uint32_t r2 = (uint32_t)rand();
#endif
    char buf[64];
    snprintf(buf, sizeof(buf), "%s-%llu-%08x%08x", prefix ? prefix : "tx", (unsigned long long)ts, r1, r2);
    return String(buf);
}

static String recordKey(int index) {
    char key[8];
    snprintf(key, sizeof(key), "rec_%02d", index);
    return String(key);
}

static bool validRecord(const PaymentRecord& rec) {
    if (rec.magic != PAYMENT_RECORD_MAGIC) return false;
    if (rec.schemaVersion != PAYMENT_SCHEMA_VERSION) return false;
    if (rec.txId[0] == '\0' || rec.targetId[0] == '\0') return false;
    if (rec.ownerType != 1 && rec.ownerType != 2) return false;
    if (rec.crc32 != computeRecordCrc32(rec)) return false;
    return true;
}

static bool persistRecord(int index, const PaymentRecord& rec) {
    lockNvs();
    Preferences storage;
    if (!storage.begin("pay_queue", false)) {
        unlockNvs();
        return false;
    }
    String key = recordKey(index);
    size_t written = storage.putBytes(key.c_str(), &rec, sizeof(rec));
    storage.end();
    unlockNvs();
    return written == sizeof(rec);
}

static bool eraseRecord(int index) {
    lockNvs();
    Preferences storage;
    if (!storage.begin("pay_queue", false)) {
        unlockNvs();
        return false;
    }
    String key = recordKey(index);
    bool removed = !storage.isKey(key.c_str()) || storage.remove(key.c_str());
    storage.end();
    unlockNvs();
    return removed;
}

static void lockQueue() {
    if (paymentQueueMutex != nullptr) xSemaphoreTake(paymentQueueMutex, portMAX_DELAY);
}

static void unlockQueue() {
    if (paymentQueueMutex != nullptr) xSemaphoreGive(paymentQueueMutex);
}

static bool hasUnpersistedPaymentsLocked() {
    for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
        if (paymentSlotUsed[i] && !paymentSlotPersisted[i]) {
            return true;
        }
    }
    return false;
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
    quarantineRecordCount = 0;
    paymentStorageReady = false;

    lockNvs();
    Preferences storage;
    if (!storage.begin("pay_queue", false)) {
        Serial.println("[PAY QUEUE] Failed to open persistent payment storage.");
        unlockNvs();
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
            Serial.printf("[PAY QUEUE] Restored tx_id='%s' for '%s' (kind=%d).\n", rec.txId, rec.targetId, rec.opKind);
        } else {
            // Check if it is a valid legacy V2 record to safely migrate
            struct LegacyRecordV2 {
                uint32_t magic;
                char txId[64];
                char targetId[97];
                int pulses;
                int creditSeconds;
                uint8_t ownerType;
                uint64_t timestamp;
            } legacyRec;

            if (length == sizeof(legacyRec) &&
                storage.getBytes(key.c_str(), &legacyRec, sizeof(legacyRec)) == sizeof(legacyRec) &&
                legacyRec.magic == PAYMENT_RECORD_MAGIC_V2 &&
                legacyRec.txId[0] != '\0') {
                memset(&rec, 0, sizeof(rec));
                rec.magic = PAYMENT_RECORD_MAGIC;
                rec.schemaVersion = PAYMENT_SCHEMA_VERSION;
                rec.opKind = (legacyRec.ownerType == 2) ? OP_KIND_CONTROLLER : OP_KIND_COIN;
                rec.ownerType = legacyRec.ownerType;
                strncpy(rec.txId, legacyRec.txId, sizeof(rec.txId) - 1);
                strncpy(rec.targetId, legacyRec.targetId, sizeof(rec.targetId) - 1);
                rec.pulses = legacyRec.pulses;
                rec.creditSeconds = legacyRec.creditSeconds;
                rec.pricePerCoin = 5.0;
                rec.timestamp = legacyRec.timestamp;
                rec.crc32 = computeRecordCrc32(rec);
                size_t written = storage.putBytes(key.c_str(), &rec, sizeof(rec));
                if (written == sizeof(rec)) {
                    paymentQueue[i] = rec;
                    paymentSlotUsed[i] = true;
                    paymentSlotPersisted[i] = true;
                    activePaymentCount++;
                    Serial.printf("[PAY QUEUE] Migrated legacy record tx_id='%s'.\n", rec.txId);
                    continue;
                }
            }

            // Do not erase corrupt records silently: quarantine/report and block acceptance
            quarantineRecordCount++;
            Serial.printf("[PAY QUEUE] CRITICAL: Corrupted/unrecognized record in slot %d (len=%u) QUARANTINED (not erased). Acceptance blocked.\n",
                          i, (unsigned)length);
        }
    }
    storage.end();
    unlockNvs();
    Serial.printf("[PAY QUEUE] Ready with %d pending payment(s), %d quarantined.\n",
                  activePaymentCount, quarantineRecordCount);
    unlockQueue();
    initMatchSettlement();
}

bool isPaymentQueueFull() {
    lockQueue();
    // Capacity reservation: reserve at least 2 slots for in-flight pulses.
    // Stop acceptance if storage fault exists, or corrupted records exist, or queue is full.
    bool full = !paymentStorageReady ||
                (quarantineRecordCount > 0) ||
                hasUnpersistedPaymentsLocked() ||
                (activePaymentCount >= MAX_PAYMENT_QUEUE_SIZE - 2);
    unlockQueue();
    return full;
}

bool isPaymentStorageReady() {
    lockQueue();
    bool ready = paymentStorageReady && (quarantineRecordCount == 0) && !hasUnpersistedPaymentsLocked();
    unlockQueue();
    return ready;
}

bool hasUnpersistedPayments() {
    lockQueue();
    bool unpersisted = hasUnpersistedPaymentsLocked();
    unlockQueue();
    return unpersisted;
}

int getQuarantinedRecordCount() {
    lockQueue();
    int count = quarantineRecordCount;
    unlockQueue();
    return count;
}

static uint32_t maintenanceReasons = 0;

void setMaintenanceReason(uint32_t reason, bool enable) {
    if (enable) {
        maintenanceReasons |= reason;
    } else {
        maintenanceReasons &= ~reason;
    }
}

void setMaintenanceMode(bool enable) {
    setMaintenanceReason(MAINT_REASON_STORAGE, enable);
}

bool isMaintenanceMode() {
    return (maintenanceReasons != 0);
}

bool isMaintenanceReasonActive(uint32_t reason) {
    return (maintenanceReasons & reason) != 0;
}

bool hasPendingPaymentsForTarget(const String& targetId) {
    if (targetId.length() == 0) return false;
    lockQueue();
    for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
        if (paymentSlotUsed[i] && String(paymentQueue[i].targetId) == targetId) {
            unlockQueue();
            return true;
        }
    }
    unlockQueue();
    return false;
}

bool hasPendingPayments() {
    lockQueue();
    for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
        if (paymentSlotUsed[i]) {
            unlockQueue();
            return true;
        }
    }
    unlockQueue();
    return false;
}

bool canPerformRebootOrOta() {
    if (!isMaintenanceMode()) {
        Serial.println("[PAY QUEUE] Reboot/OTA blocked: admission is not closed (maintenance mode off).");
        return false;
    }
    if (hasUnpersistedPayments()) {
        Serial.println("[PAY QUEUE] Reboot/OTA blocked: unpersisted transactions remain in RAM.");
        return false;
    }
    if (isCoinSlotArmed() || isRelayHardwareActive()) {
        Serial.println("[PAY QUEUE] Reboot/OTA blocked: relay or coin slot hardware is powered/ARMED.");
        return false;
    }
    String activeDev = getActiveCoinSessionId();
    CoinSlotState state = getCoinSlotState();
    if (activeDev.length() > 0 || state == CoinSlotState::ARMED || state == CoinSlotState::DRAINING || state == CoinSlotState::RESERVED_ARMING) {
        Serial.println("[PAY QUEUE] Reboot/OTA blocked: active or draining session owner exists.");
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

static bool pendingRestartRequested = false;
static String pendingRestartReason = "";
static unsigned long pendingRestartStartMs = 0;
static unsigned long pendingRestartTimeoutMs = 15000;
static bool factoryResetPending = false;

void setFactoryResetPending(bool pending) {
    factoryResetPending = pending;
}

bool requestSystemRestart(const char* reason, unsigned long timeoutMs) {
    Serial.printf("\n=======================================================\n");
    Serial.printf("[🛑 REBOOT GATE] Planned restart requested: %s\n", reason ? reason : "Unknown");
    Serial.printf("=======================================================\n");

    setMaintenanceReason(MAINT_REASON_RESTART, true);

    pendingRestartRequested = true;
    pendingRestartReason = reason ? reason : "Planned Restart";
    pendingRestartStartMs = millis();
    pendingRestartTimeoutMs = (timeoutMs > 0) ? timeoutMs : 15000;
    return true;
}

void processPendingSystemRestart() {
    if (!pendingRestartRequested) return;

    unsigned long now = millis();

    if (canPerformRebootOrOta()) {
        if (factoryResetPending) {
            Serial.println("[🛑 REBOOT GATE] Safe maintenance acquired. Executing factory reset before restart...");
            factoryResetDefaults();
            factoryResetPending = false;
        }

        if (revenueDirty || totalCoinsLifetime != lastSavedTotalCoins || totalEarningsLifetime != lastSavedTotalEarnings) {
            lockNvs();
            prefs.begin(NVS_NAMESPACE, false);
            prefs.putULong(NVS_KEY_TOTAL_COINS, totalCoinsLifetime);
            prefs.putFloat(NVS_KEY_TOTAL_EARNINGS, totalEarningsLifetime);
            prefs.end();
            unlockNvs();
            lastSavedTotalCoins = totalCoinsLifetime;
            lastSavedTotalEarnings = totalEarningsLifetime;
            revenueDirty = false;
            Serial.println("[🛑 REBOOT GATE] Revenue counters durably flushed to NVS flash.");
        }

        Serial.printf("[🛑 REBOOT GATE] Pre-reboot invariants verified (%s). System restarting now...\n", pendingRestartReason.c_str());
        Serial.flush();
        delay(200);
        ESP.restart();
        return;
    }

    if (now - pendingRestartStartMs >= pendingRestartTimeoutMs) {
        Serial.printf("[🛑 REBOOT GATE] Timeout (%lu ms) waiting for safe reboot conditions (%s). Restart request canceled.\n",
                      pendingRestartTimeoutMs, pendingRestartReason.c_str());
        pendingRestartRequested = false;
        factoryResetPending = false;
        setMaintenanceReason(MAINT_REASON_RESTART, false);
        setMaintenanceReason(MAINT_REASON_RESET, false);
    }
}

int getPendingPaymentCount() {
    lockQueue();
    int count = activePaymentCount;
    unlockQueue();
    return count;
}

bool enqueuePendingPayment(const String& txId, const String& targetId, int pulses,
                           CoinSlotOwnerType ownerType, int creditSeconds,
                           PaymentOpKind opKind,
                           double pricePerCoin,
                           uint64_t boxEpoch, uint64_t phoneEpoch) {
    if (txId.length() == 0 || txId.length() >= sizeof(((PaymentRecord*)0)->txId) ||
        targetId.length() == 0 || targetId.length() >= sizeof(((PaymentRecord*)0)->targetId) ||
        (ownerType != CoinSlotOwnerType::PHONE && ownerType != CoinSlotOwnerType::CONTROLLER)) {
        Serial.println("[PAY QUEUE] Rejected invalid payment record parameters.");
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
    rec.schemaVersion = PAYMENT_SCHEMA_VERSION;
    rec.opKind = (uint8_t)opKind;
    rec.ownerType = (ownerType == CoinSlotOwnerType::CONTROLLER) ? 2 : 1;
    strncpy(rec.txId, txId.c_str(), sizeof(rec.txId) - 1);
    strncpy(rec.targetId, targetId.c_str(), sizeof(rec.targetId) - 1);
    rec.pulses = pulses;
    rec.creditSeconds = creditSeconds;
    rec.pricePerCoin = (pricePerCoin > 0.0) ? pricePerCoin : 5.0;
    rec.boxInstallationEpoch = boxEpoch;
    rec.phonePairingEpoch = phoneEpoch;
    rec.timestamp = getCurrentMasterTimeMs();
    rec.crc32 = computeRecordCrc32(rec);

    // Retain pulses in RAM before fallible flash write
    paymentQueue[freeIndex] = rec;
    paymentSlotUsed[freeIndex] = true;
    paymentSlotPersisted[freeIndex] = false;
    lastPersistAttemptMs[freeIndex] = millis();
    persistRetryCount[freeIndex] = 0;
    lastDispatchMs[freeIndex] = 0;
    activePaymentCount++;

    // Attempt durable NVS flash write
    if (!persistRecord(freeIndex, rec)) {
        persistRetryCount[freeIndex] = 1;
        unlockQueue();
        Serial.printf("[PAY QUEUE] NVS write failed for tx_id='%s'. Retained in RAM; persistence will retry with backoff.\n", txId.c_str());
        return true;
    }

    paymentSlotPersisted[freeIndex] = true;
    lastDispatchMs[freeIndex] = millis();
    unlockQueue();

    Serial.printf("[PAY QUEUE] Persisted tx_id='%s' for '%s' (%d pulse(s), kind=%d). Dispatching durably written record...\n",
                  txId.c_str(), targetId.c_str(), pulses, (int)opKind);

    if (rec.ownerType == 2) {
        sendControllerPaymentEvent(String(rec.targetId), String(rec.txId), rec.pulses);
    } else if (rec.ownerType == 1) {
        retryPhonePayment(String(rec.targetId), rec.pulses, rec.creditSeconds, String(rec.txId), rec.opKind, rec.boxInstallationEpoch, rec.phonePairingEpoch);
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

    if (!paymentSlotPersisted[foundIndex]) {
        unlockQueue();
        Serial.printf("[PAY QUEUE] Reject ACK for tx_id='%s': record not yet durably persisted.\n", txId.c_str());
        return false;
    }

    // Failed flash deletion retains the record!
    if (!eraseRecord(foundIndex)) {
        unlockQueue();
        Serial.printf("[PAY QUEUE] Failed to erase acknowledged tx_id='%s'. Record RETAINED in RAM and flash.\n", txId.c_str());
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

bool hasPendingPayment(const String& txId) {
    if (txId.length() == 0) return false;
    lockQueue();
    bool found = false;
    for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
        if (paymentSlotUsed[i] && String(paymentQueue[i].txId) == txId) {
            found = true;
            break;
        }
    }
    unlockQueue();
    return found;
}

bool acknowledgePhonePayment(
    const String& deviceId,
    const String& txId,
    int acknowledgedPulses,
    int acknowledgedSeconds,
    const String& status,
    uint8_t expectedOpKind) {
    if (deviceId.length() == 0 || txId.length() == 0) return false;

    // 1. Reject status other than OK or ALREADY_PROCESSED.
    if (status != "OK" && status != "ALREADY_PROCESSED") {
        return false;
    }

    lockQueue();
    // 2. Find exact PHONE record matching transaction, device ID and parameters
    int foundIndex = -1;
    for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
        if (paymentSlotUsed[i] && 
            paymentQueue[i].ownerType == 1 && 
            String(paymentQueue[i].txId) == txId && 
            String(paymentQueue[i].targetId) == deviceId) {
            foundIndex = i;
            break;
        }
    }

    if (foundIndex < 0) {
        unlockQueue();
        return false;
    }

    // 3. Require it to be durably persisted.
    if (!paymentSlotPersisted[foundIndex]) {
        unlockQueue();
        Serial.printf("[PAY QUEUE] Reject ACK for tx_id='%s': record not yet durably persisted.\n", txId.c_str());
        return false;
    }

    // 4. Require acknowledgedPulses == record.pulses.
    if (acknowledgedPulses != paymentQueue[foundIndex].pulses) {
        unlockQueue();
        Serial.printf("[PAY QUEUE] Reject ACK for tx_id='%s': mismatched pulses (%d vs %d).\n", 
                      txId.c_str(), acknowledgedPulses, paymentQueue[foundIndex].pulses);
        return false;
    }

    // 5. Require acknowledgedSeconds == record.creditSeconds.
    if (acknowledgedSeconds != paymentQueue[foundIndex].creditSeconds) {
        unlockQueue();
        Serial.printf("[PAY QUEUE] Reject ACK for tx_id='%s': mismatched seconds (%d vs %d).\n", 
                      txId.c_str(), acknowledgedSeconds, paymentQueue[foundIndex].creditSeconds);
        return false;
    }

    // 6. Require expectedOpKind match if specified
    if (expectedOpKind != 0 && paymentQueue[foundIndex].opKind != expectedOpKind) {
        unlockQueue();
        Serial.printf("[PAY QUEUE] Reject ACK for tx_id='%s': mismatched opKind (%d vs %d).\n",
                      txId.c_str(), expectedOpKind, paymentQueue[foundIndex].opKind);
        return false;
    }

    // 7. Delete NVS record. FAILED FLASH DELETION RETAINS THE RECORD!
    if (!eraseRecord(foundIndex)) {
        unlockQueue();
        Serial.printf("[PAY QUEUE] Failed to erase acknowledged tx_id='%s' from NVS. Record RETAINED.\n", txId.c_str());
        return false;
    }

    // 8. Clear its RAM slot only after successful NVS deletion.
    memset(&paymentQueue[foundIndex], 0, sizeof(PaymentRecord));
    paymentSlotUsed[foundIndex] = false;
    paymentSlotPersisted[foundIndex] = false;
    lastPersistAttemptMs[foundIndex] = 0;
    persistRetryCount[foundIndex] = 0;
    lastDispatchMs[foundIndex] = 0;
    activePaymentCount--;

    unlockQueue();
    Serial.printf("[PAY QUEUE] Acknowledged tx_id='%s' (device: %s) successfully erased and cleared.\n", txId.c_str(), deviceId.c_str());
    recordMatchDeductionCommitted(txId);
    recordMatchCreditCommitted(txId);
    return true;
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

    // 0. Periodic Match Settlement Recovery (ensures queue restoration even if queue was temporarily full or rebooted)
    static unsigned long lastMatchRecoveryMs = 0;
    if (lastMatchRecoveryMs == 0 || (long)(now - (lastMatchRecoveryMs + 2000UL)) >= 0) {
        lastMatchRecoveryMs = now;
        recoverPendingMatchSettlement();
    }

    // 1. Retry unpersisted records in RAM with exponential backoff (1s, 2s, 4s, 8s, 16s, 32s)
    for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
        PaymentRecord rec;
        bool needPersist = false;
        int currentAttempt = 0;

        lockQueue();
        if (paymentSlotUsed[i] && !paymentSlotPersisted[i]) {
            uint8_t count = persistRetryCount[i] > 5 ? 5 : persistRetryCount[i];
            unsigned long backoffMs = 1000UL << count;
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
                lastDispatchMs[i] = millis();
                unlockQueue();
                Serial.printf("[PAY QUEUE] NVS persistence recovered for tx_id='%s'. Dispatching...\n", rec.txId);
                if (rec.ownerType == 2) {
                    sendControllerPaymentEvent(String(rec.targetId), String(rec.txId), rec.pulses);
                } else if (rec.ownerType == 1) {
                    retryPhonePayment(String(rec.targetId), rec.pulses, rec.creditSeconds, String(rec.txId), rec.opKind, rec.boxInstallationEpoch, rec.phonePairingEpoch);
                }
            } else {
                lockQueue();
                if (persistRetryCount[i] < 10) persistRetryCount[i]++;
                unlockQueue();
                Serial.printf("[PAY QUEUE] Persistence retry failed for tx_id='%s'. Retained in RAM.\n", rec.txId);
            }
        }
    }

    // 2. Dispatch / retry ONLY durably persisted records with bounded backoff (2s, 4s, 8s, 16s, 32s)
    for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
        PaymentRecord rec;
        bool shouldDispatch = false;

        lockQueue();
        if (paymentSlotUsed[i] && paymentSlotPersisted[i]) {
            uint8_t attempts = persistRetryCount[i] > 4 ? 4 : persistRetryCount[i];
            unsigned long dispatchInterval = min(2000UL * (1UL << attempts), 32000UL);
            if (lastDispatchMs[i] == 0 || (long)(now - (lastDispatchMs[i] + dispatchInterval)) >= 0) {
                rec = paymentQueue[i];
                lastDispatchMs[i] = now;
                if (persistRetryCount[i] < 10) persistRetryCount[i]++;
                shouldDispatch = true;
            }
        }
        unlockQueue();

        if (!shouldDispatch) continue;
        if (rec.ownerType == 2) {
            sendControllerPaymentEvent(String(rec.targetId), String(rec.txId), rec.pulses);
        } else if (rec.ownerType == 1) {
            retryPhonePayment(String(rec.targetId), rec.pulses,
                              rec.creditSeconds, String(rec.txId), rec.opKind, rec.boxInstallationEpoch, rec.phonePairingEpoch);
        }
    }
}

bool cancelPaymentRecord(const String& txId) {
    if (txId.length() == 0) return false;
    lockQueue();
    int foundIndex = -1;
    for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
        if (paymentSlotUsed[i] && String(paymentQueue[i].txId) == txId) {
            foundIndex = i;
            break;
        }
    }
    if (foundIndex < 0) {
        unlockQueue();
        return false;
    }
    eraseRecord(foundIndex);
    memset(&paymentQueue[foundIndex], 0, sizeof(PaymentRecord));
    paymentSlotUsed[foundIndex] = false;
    paymentSlotPersisted[foundIndex] = false;
    lastPersistAttemptMs[foundIndex] = 0;
    persistRetryCount[foundIndex] = 0;
    lastDispatchMs[foundIndex] = 0;
    activePaymentCount--;
    unlockQueue();
    Serial.printf("[PAY QUEUE] Canceled/rejected tx_id='%s'.\n", txId.c_str());
    return true;
}

uint32_t computeMatchRecordCrc32(const MatchSettlementRecord& rec) {
    const uint8_t* data = (const uint8_t*)&rec;
    size_t length = offsetof(MatchSettlementRecord, crc32);
    uint32_t crc = 0xFFFFFFFF;
    for (size_t i = 0; i < length; i++) {
        crc ^= data[i];
        for (int j = 0; j < 8; j++) {
            crc = (crc >> 1) ^ (0xEDB88320 & -(crc & 1));
        }
    }
    return ~crc;
}

static bool loadMatchRecord(MatchSettlementRecord& outRec) {
    lockNvs();
    Preferences storage;
    if (!storage.begin("match_settle", true)) {
        unlockNvs();
        return false;
    }
    size_t read = storage.getBytes("current", &outRec, sizeof(outRec));
    storage.end();
    unlockNvs();
    if (read != sizeof(outRec)) return false;
    if (outRec.magic != 0x4D53544CUL || outRec.schemaVersion != 1) return false;
    if (outRec.crc32 != computeMatchRecordCrc32(outRec)) return false;
    return true;
}

static bool saveMatchRecord(MatchSettlementRecord& rec) {
    rec.magic = 0x4D53544CUL;
    rec.schemaVersion = 1;
    rec.crc32 = computeMatchRecordCrc32(rec);
    lockNvs();
    Preferences storage;
    if (!storage.begin("match_settle", false)) {
        unlockNvs();
        return false;
    }
    size_t written = storage.putBytes("current", &rec, sizeof(rec));
    storage.end();
    unlockNvs();
    return written == sizeof(rec);
}

void recoverPendingMatchSettlement() {
    MatchSettlementRecord rec;
    if (!loadMatchRecord(rec)) return;

    if (rec.state == MATCH_SETTLE_DEDUCT_PENDING) {
        if (!hasPendingPayment(String(rec.deductTxId))) {
            Serial.printf("[MATCH SETTLE RECOVERY] Restoring deduction tx_id='%s' for '%s' to queue...\n",
                          rec.deductTxId, rec.loserId);
            enqueuePendingPayment(
                String(rec.deductTxId), String(rec.loserId), 0, CoinSlotOwnerType::PHONE,
                -rec.stakeSeconds, OP_KIND_MATCH_TRANSFER, 0.0, 0, 0
            );
        }
    } else if (rec.state == MATCH_SETTLE_DEDUCT_COMMITTED_CREDIT_PENDING) {
        if (!hasPendingPayment(String(rec.creditTxId))) {
            Serial.printf("[MATCH SETTLE RECOVERY] Restoring credit tx_id='%s' for '%s' to queue...\n",
                          rec.creditTxId, rec.winnerId);
            enqueuePendingPayment(
                String(rec.creditTxId), String(rec.winnerId), 0, CoinSlotOwnerType::PHONE,
                rec.stakeSeconds, OP_KIND_MATCH_TRANSFER, 0.0, 0, 0
            );
        }
    }
}

void initMatchSettlement() {
    MatchSettlementRecord rec;
    if (loadMatchRecord(rec)) {
        Serial.printf("[MATCH SETTLE] Loaded stored settlement record: matchId='%s', state=%d, deductTx='%s', creditTx='%s'\n",
                      rec.matchId, (int)rec.state, rec.deductTxId, rec.creditTxId);
    }
    recoverPendingMatchSettlement();
}

bool startMatchSettlement(
    const String& matchId,
    const String& loserId,
    const String& winnerId,
    int stakeSeconds,
    String& outDeductTxId,
    String& outCreditTxId,
    String& errOut) {

    MatchSettlementRecord existing;
    if (loadMatchRecord(existing)) {
        if (existing.state == MATCH_SETTLE_DEDUCT_PENDING || 
            existing.state == MATCH_SETTLE_DEDUCT_COMMITTED_CREDIT_PENDING) {
            errOut = "An unresolved match settlement is already in progress. Only one unresolved settlement is supported.";
            return false;
        }
        if (hasPendingPayment(String(existing.deductTxId)) || hasPendingPayment(String(existing.creditTxId))) {
            errOut = "A transaction from a prior settlement is still pending in the queue.";
            return false;
        }
        if (String(existing.matchId) == matchId) {
            if (existing.state == MATCH_SETTLE_COMPLETED) {
                errOut = "Match settlement already completed.";
                return false;
            }
            if (existing.state == MATCH_SETTLE_REJECTED) {
                errOut = "Match settlement was already rejected.";
                return false;
            }
        }
    }

    String mId = matchId.length() > 0 ? matchId : ("match-" + String((unsigned long long)getCurrentMasterTimeMs()));
    outDeductTxId = mId + "-deduct-" + generateCollisionResistantTxId("mdd");
    outCreditTxId = mId + "-credit-" + generateCollisionResistantTxId("mcr");

    MatchSettlementRecord newRec;
    memset(&newRec, 0, sizeof(newRec));
    newRec.state = MATCH_SETTLE_DEDUCT_PENDING;
    strncpy(newRec.matchId, mId.c_str(), sizeof(newRec.matchId) - 1);
    strncpy(newRec.loserId, loserId.c_str(), sizeof(newRec.loserId) - 1);
    strncpy(newRec.winnerId, winnerId.c_str(), sizeof(newRec.winnerId) - 1);
    newRec.stakeSeconds = stakeSeconds;
    strncpy(newRec.deductTxId, outDeductTxId.c_str(), sizeof(newRec.deductTxId) - 1);
    strncpy(newRec.creditTxId, outCreditTxId.c_str(), sizeof(newRec.creditTxId) - 1);
    newRec.timestamp = getCurrentMasterTimeMs();

    if (!saveMatchRecord(newRec)) {
        errOut = "Failed to persist settlement record to NVS.";
        return false;
    }

    bool queued = enqueuePendingPayment(
        outDeductTxId, loserId, 0, CoinSlotOwnerType::PHONE,
        -stakeSeconds, OP_KIND_MATCH_TRANSFER, 0.0, 0, 0
    );

    if (!queued) {
        Serial.printf("[MATCH SETTLE] Initial queue insertion failed for deduct tx '%s'. Preserved in NVS for retry.\n", outDeductTxId.c_str());
    }

    return true;
}

bool recordMatchDeductionCommitted(const String& deductTxId) {
    MatchSettlementRecord rec;
    if (!loadMatchRecord(rec)) return false;
    if (String(rec.deductTxId) != deductTxId) return false;
    if (rec.state != MATCH_SETTLE_DEDUCT_PENDING) return true;

    rec.state = MATCH_SETTLE_DEDUCT_COMMITTED_CREDIT_PENDING;
    if (!saveMatchRecord(rec)) {
        Serial.printf("[MATCH SETTLE] Warning: Failed to save credit pending state for match '%s'. Retrying save...\n", rec.matchId);
        saveMatchRecord(rec);
    }

    Serial.printf("[MATCH SETTLE] Deduction committed for match '%s'. Submitting credit tx_id='%s' to '%s'...\n",
                  rec.matchId, rec.creditTxId, rec.winnerId);

    bool queued = enqueuePendingPayment(
        String(rec.creditTxId), String(rec.winnerId), 0, CoinSlotOwnerType::PHONE,
        rec.stakeSeconds, OP_KIND_MATCH_TRANSFER, 0.0, 0, 0
    );
    if (!queued) {
        Serial.printf("[MATCH SETTLE] Initial credit queue insertion failed for '%s'. Preserved in NVS for retry.\n", rec.creditTxId);
    }
    return true;
}

bool recordMatchDeductionRejected(const String& deductTxId) {
    MatchSettlementRecord rec;
    if (!loadMatchRecord(rec)) return false;
    if (String(rec.deductTxId) != deductTxId) return false;

    rec.state = MATCH_SETTLE_REJECTED;
    saveMatchRecord(rec);

    Serial.printf("[MATCH SETTLE] Deduction REJECTED for match '%s' (tx_id='%s'). Marked REJECTED.\n",
                  rec.matchId, deductTxId.c_str());

    cancelPaymentRecord(deductTxId);
    return true;
}

bool recordMatchCreditCommitted(const String& creditTxId) {
    MatchSettlementRecord rec;
    if (!loadMatchRecord(rec)) return false;
    if (String(rec.creditTxId) != creditTxId) return false;
    if (rec.state != MATCH_SETTLE_DEDUCT_COMMITTED_CREDIT_PENDING) return true;

    rec.state = MATCH_SETTLE_COMPLETED;
    if (!saveMatchRecord(rec)) {
        Serial.printf("[MATCH SETTLE] Warning: Failed to save completed state for match '%s'. Retrying save...\n", rec.matchId);
        saveMatchRecord(rec);
    }

    Serial.printf("[MATCH SETTLE] Credit committed for match '%s' (tx_id='%s'). Match settlement COMPLETED!\n",
                  rec.matchId, creditTxId.c_str());
    return true;
}

String getMatchSettlementStatusHtml(const String& currentMatchId) {
    MatchSettlementRecord rec;
    if (!loadMatchRecord(rec)) {
        return "";
    }
    if (currentMatchId.length() > 0 && String(rec.matchId) != currentMatchId) {
        return "";
    }

    int mins = rec.stakeSeconds / 60;
    String mId = String(rec.matchId);
    String loser = String(rec.loserId);
    String winner = String(rec.winnerId);

    if (rec.state == MATCH_SETTLE_DEDUCT_PENDING) {
        return "<div style='background:#fef3c7;color:#92400e;padding:8px 12px;border-radius:4px;margin-bottom:10px;font-size:13px;'>⏳ <b>Match Settlement Pending:</b> Deduction (-" + String(mins) + "m) submitted for loser (" + loser + "), credit pending deduction confirmation. [Match: " + mId + "]</div>";
    } else if (rec.state == MATCH_SETTLE_DEDUCT_COMMITTED_CREDIT_PENDING) {
        return "<div style='background:#d1ecf1;color:#0c5460;padding:8px 12px;border-radius:4px;margin-bottom:10px;font-size:13px;'>⏳ <b>Match Settlement In Progress:</b> Deduction committed (-" + String(mins) + "m from " + loser + "), credit pending transfer to winner (" + winner + "). [Match: " + mId + "]</div>";
    } else if (rec.state == MATCH_SETTLE_COMPLETED) {
        return "<div style='background:#e8f5e9;color:#2e7d32;padding:8px 12px;border-radius:4px;margin-bottom:10px;font-size:13px;'>🏆 <b>Match Transfer Completed:</b> Transferred +" + String(mins) + "m to winner (" + winner + ") and deducted -" + String(mins) + "m from loser (" + loser + "). [Match: " + mId + "]</div>";
    } else if (rec.state == MATCH_SETTLE_REJECTED) {
        return "<div style='background:#ffebee;color:#c62828;padding:8px 12px;border-radius:4px;margin-bottom:10px;font-size:13px;'>❌ <b>Match Transfer Rejected:</b> Deduction failed on loser (" + loser + ", e.g. insufficient funds). No credits were transferred. [Match: " + mId + "]</div>";
    }
    return "";
}
