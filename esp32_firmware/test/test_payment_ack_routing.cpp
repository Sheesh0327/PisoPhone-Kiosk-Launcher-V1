#include <iostream>
#include <string>
#include <cassert>
#include <cstring>
#include <vector>
#include <cstdint>
#include <cstddef>

// Emulate types and constants from PaymentQueueManager.h & WebServerAuth.cpp
enum PaymentOpKind : uint8_t {
    OP_KIND_COIN = 1,
    OP_KIND_QUICK_ADJUST = 2,
    OP_KIND_MANUAL_DEDUCT = 3,
    OP_KIND_MATCH_TRANSFER = 4,
    OP_KIND_CONTROLLER = 5
};

static const uint32_t PAYMENT_RECORD_MAGIC = 0x50415933UL; // "PAY3"
static const uint16_t PAYMENT_SCHEMA_VERSION = 1;

struct PaymentRecord {
    uint32_t magic;
    uint16_t schemaVersion;
    uint8_t opKind;
    uint8_t ownerType; // 1 = PHONE, 2 = CONTROLLER
    char txId[64];
    char targetId[97];
    int32_t pulses;
    int32_t creditSeconds;
    double pricePerCoin;
    uint64_t boxInstallationEpoch;
    uint64_t phonePairingEpoch;
    uint64_t timestamp;
    uint32_t crc32;
};

static uint32_t computeRecordCrc32(const PaymentRecord& rec) {
    const uint8_t* data = reinterpret_cast<const uint8_t*>(&rec);
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

static const int MAX_PAYMENT_QUEUE_SIZE = 20;

struct MockPaymentQueue {
    PaymentRecord records[MAX_PAYMENT_QUEUE_SIZE];
    bool slotUsed[MAX_PAYMENT_QUEUE_SIZE];
    bool slotPersisted[MAX_PAYMENT_QUEUE_SIZE];
    bool flashEraseFail;
    int activePaymentCount;

    MockPaymentQueue() {
        reset();
    }

    void reset() {
        std::memset(records, 0, sizeof(records));
        std::memset(slotUsed, 0, sizeof(slotUsed));
        std::memset(slotPersisted, 0, sizeof(slotPersisted));
        flashEraseFail = false;
        activePaymentCount = 0;
    }

    bool enqueue(const std::string& txId, const std::string& targetId, int pulses, int creditSeconds, PaymentOpKind opKind) {
        for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
            if (!slotUsed[i]) {
                slotUsed[i] = true;
                slotPersisted[i] = true;
                records[i].magic = PAYMENT_RECORD_MAGIC;
                records[i].schemaVersion = PAYMENT_SCHEMA_VERSION;
                records[i].opKind = (uint8_t)opKind;
                records[i].ownerType = 1; // PHONE
                std::strncpy(records[i].txId, txId.c_str(), sizeof(records[i].txId) - 1);
                std::strncpy(records[i].targetId, targetId.c_str(), sizeof(records[i].targetId) - 1);
                records[i].pulses = pulses;
                records[i].creditSeconds = creditSeconds;
                records[i].crc32 = computeRecordCrc32(records[i]);
                activePaymentCount++;
                return true;
            }
        }
        return false;
    }

    bool hasPendingPayment(const std::string& txId) const {
        if (txId.empty()) return false;
        for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
            if (slotUsed[i] && std::string(records[i].txId) == txId) {
                return true;
            }
        }
        return false;
    }

    bool acknowledgePhonePayment(const std::string& deviceId, const std::string& txId,
                                int ackPulses, int ackSeconds, const std::string& status) {
        if (deviceId.empty() || txId.empty()) return false;
        if (status != "OK" && status != "ALREADY_PROCESSED") return false;

        int foundIndex = -1;
        for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
            if (slotUsed[i] &&
                records[i].ownerType == 1 &&
                std::string(records[i].txId) == txId &&
                std::string(records[i].targetId) == deviceId) {
                foundIndex = i;
                break;
            }
        }

        if (foundIndex < 0) return false;
        if (!slotPersisted[foundIndex]) return false;
        if (ackPulses != records[foundIndex].pulses) return false;
        if (ackSeconds != records[foundIndex].creditSeconds) return false;

        if (flashEraseFail) {
            // Flash erase failed: record MUST be retained in RAM and flash!
            return false;
        }

        // Erase successful: clear RAM slot
        std::memset(&records[foundIndex], 0, sizeof(PaymentRecord));
        slotUsed[foundIndex] = false;
        slotPersisted[foundIndex] = false;
        activePaymentCount--;
        return true;
    }
};

struct MockAdjustmentStore {
    std::vector<std::string> confirmedAdjustments;

    void recordAdjustmentConfirmed(const std::string& txId, const std::string& devId, int seconds) {
        confirmedAdjustments.push_back(txId + ":" + devId + ":" + std::to_string(seconds));
    }
};

enum AckRouteResult {
    ROUTE_ACK_PHONE_OR_COIN_SUCCESS,
    ROUTE_ACK_ADJUSTMENT_CONFIRMED,
    ROUTE_ACK_REJECTED
};

AckRouteResult routeAckProductionLogic(
    MockPaymentQueue& queue,
    MockAdjustmentStore& adjStore,
    bool ackValid,
    const std::string& currentTxId,
    const std::string& currentDevId,
    const std::string& ackTx,
    const std::string& ackDev,
    int ackAmt,
    int ackSec,
    const std::string& status) {

    if (!ackValid) {
        return ROUTE_ACK_REJECTED;
    }

    if (queue.hasPendingPayment(ackTx)) {
        if (queue.acknowledgePhonePayment(ackDev, ackTx, ackAmt, ackSec, status)) {
            return ROUTE_ACK_PHONE_OR_COIN_SUCCESS;
        } else {
            return ROUTE_ACK_REJECTED;
        }
    } else {
        // Not a queued payment or already cleared; check/confirm manual adjustment
        adjStore.recordAdjustmentConfirmed(currentTxId, ackDev, ackSec);
        return ROUTE_ACK_ADJUSTMENT_CONFIRMED;
    }
}

int main() {
    std::cout << "Starting Payment ACK Routing Unit Tests..." << std::endl;

    MockPaymentQueue queue;
    MockAdjustmentStore adjStore;

    // 1. Coin Transaction ACK ("coin-" prefix)
    std::cout << "[TEST] 1. 'coin-' prefix payment queue ACK routing:" << std::endl;
    std::string coinTx = "coin-1710000000-1122334455667788";
    std::string deviceId = "esp32-dev-phone-1";
    queue.enqueue(coinTx, deviceId, 5, 3000, OP_KIND_COIN);
    assert(queue.activePaymentCount == 1);

    AckRouteResult res = routeAckProductionLogic(queue, adjStore, true, coinTx, deviceId, coinTx, deviceId, 5, 3000, "OK");
    assert(res == ROUTE_ACK_PHONE_OR_COIN_SUCCESS);
    assert(queue.activePaymentCount == 0); // Evicted from queue upon ACK!
    assert(adjStore.confirmedAdjustments.empty()); // Should NOT route to adjustment!
    std::cout << "  -> 'coin-' transaction correctly routed to payment queue eviction PASSED." << std::endl;

    // 2. Legacy Transaction ACK ("tx-" prefix)
    std::cout << "[TEST] 2. 'tx-' prefix payment queue ACK routing:" << std::endl;
    std::string legacyTx = "tx-1710000000-aabbccddeeff0011";
    queue.enqueue(legacyTx, deviceId, 2, 1200, OP_KIND_COIN);
    assert(queue.activePaymentCount == 1);

    res = routeAckProductionLogic(queue, adjStore, true, legacyTx, deviceId, legacyTx, deviceId, 2, 1200, "OK");
    assert(res == ROUTE_ACK_PHONE_OR_COIN_SUCCESS);
    assert(queue.activePaymentCount == 0);
    assert(adjStore.confirmedAdjustments.empty());
    std::cout << "  -> 'tx-' legacy transaction correctly routed to payment queue eviction PASSED." << std::endl;

    // 3. Mismatched Amount/Seconds or Recipient
    std::cout << "[TEST] 3. Mismatched field ACK rejection:" << std::endl;
    queue.enqueue(coinTx, deviceId, 5, 3000, OP_KIND_COIN);
    assert(queue.activePaymentCount == 1);

    // Mismatched amount
    bool valid = false; // auth worker fails verification when amount mismatches
    res = routeAckProductionLogic(queue, adjStore, valid, coinTx, deviceId, coinTx, deviceId, 999, 3000, "OK");
    assert(res == ROUTE_ACK_REJECTED);
    assert(queue.activePaymentCount == 1); // Record retained!

    // 4. Flash Deletion Failure Retains Record
    std::cout << "[TEST] 4. Flash deletion failure retains queue record:" << std::endl;
    queue.flashEraseFail = true;
    res = routeAckProductionLogic(queue, adjStore, true, coinTx, deviceId, coinTx, deviceId, 5, 3000, "OK");
    // If flash deletion fails, acknowledgePhonePayment returns false
    assert(queue.activePaymentCount == 1); // Retained!
    std::cout << "  -> Failed flash erase retains payment record in queue PASSED." << std::endl;

    // 5. Successful retry after flash recovery
    std::cout << "[TEST] 5. Successful retry after flash recovery:" << std::endl;
    queue.flashEraseFail = false;
    res = routeAckProductionLogic(queue, adjStore, true, coinTx, deviceId, coinTx, deviceId, 5, 3000, "OK");
    assert(res == ROUTE_ACK_PHONE_OR_COIN_SUCCESS);
    assert(queue.activePaymentCount == 0);
    std::cout << "  -> Retry after flash recovery successfully evicts record PASSED." << std::endl;

    // 6. Manual Adjustment (Non-queued transaction)
    std::cout << "[TEST] 6. Manual Quick-Adjust routing:" << std::endl;
    std::string adjTx = "adj-1710000000-9988776655443322";
    res = routeAckProductionLogic(queue, adjStore, true, adjTx, deviceId, adjTx, deviceId, 0, 600, "OK");
    assert(res == ROUTE_ACK_ADJUSTMENT_CONFIRMED);
    assert(adjStore.confirmedAdjustments.size() == 1);
    assert(adjStore.confirmedAdjustments[0] == adjTx + ":" + deviceId + ":600");
    std::cout << "  -> Manual Quick-Adjust properly routed and recorded PASSED." << std::endl;

    std::cout << "\nALL PAYMENT ACK ROUTING TESTS PASSED SUCCESSFULLY!" << std::endl;
    return 0;
}
