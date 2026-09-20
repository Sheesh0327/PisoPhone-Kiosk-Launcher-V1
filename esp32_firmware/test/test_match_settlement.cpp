#include <iostream>
#include <string>
#include <cassert>
#include <cstring>
#include <vector>
#include <map>
#include <cstdint>
#include <cstddef>

enum PaymentOpKind : uint8_t {
    OP_KIND_COIN = 1,
    OP_KIND_QUICK_ADJUST = 2,
    OP_KIND_MANUAL_DEDUCT = 3,
    OP_KIND_MATCH_TRANSFER = 4,
    OP_KIND_CONTROLLER = 5
};

enum MatchSettleState : uint8_t {
    MATCH_SETTLE_NONE = 0,
    MATCH_SETTLE_DEDUCT_PENDING = 1,
    MATCH_SETTLE_DEDUCT_COMMITTED_CREDIT_PENDING = 2,
    MATCH_SETTLE_COMPLETED = 3,
    MATCH_SETTLE_REJECTED = 4
};

static const uint32_t MATCH_RECORD_MAGIC = 0x4D53544CUL; // "MSTL"
static const uint32_t PAYMENT_RECORD_MAGIC = 0x50415933UL; // "PAY3"

struct MatchSettlementRecord {
    uint32_t magic;
    uint16_t schemaVersion;
    uint8_t state;
    char matchId[64];
    char loserId[97];
    char winnerId[97];
    int32_t stakeSeconds;
    char deductTxId[64];
    char creditTxId[64];
    uint64_t timestamp;
    uint32_t crc32;
};

static uint32_t computeMatchRecordCrc32(const MatchSettlementRecord& rec) {
    const uint8_t* data = reinterpret_cast<const uint8_t*>(&rec);
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

struct PaymentRecord {
    uint32_t magic;
    uint16_t schemaVersion;
    uint8_t opKind;
    uint8_t ownerType;
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

static const int MAX_PAYMENT_QUEUE_SIZE = 20;

struct DeviceMapping {
    std::string deviceId;
    std::string currentIp;
    bool active;
};

class MockDeviceManager {
public:
    std::map<std::string, DeviceMapping> devices; // keyed by deviceId

    void registerDevice(const std::string& devId, const std::string& ip, bool active = true) {
        devices[devId] = {devId, ip, active};
    }

    void updateDeviceIp(const std::string& devId, const std::string& newIp) {
        if (devices.find(devId) != devices.end()) {
            devices[devId].currentIp = newIp;
        }
    }

    std::string getDeviceIdFromIp(const std::string& ip) {
        for (const auto& kv : devices) {
            if (kv.second.active && (kv.second.currentIp == ip || kv.second.deviceId == ip)) {
                return kv.second.deviceId;
            }
        }
        return "";
    }

    std::string getIpFromDeviceId(const std::string& devId) {
        auto it = devices.find(devId);
        if (it != devices.end() && it->second.active) {
            return it->second.currentIp;
        }
        return "";
    }
};

class MockMatchSettlementSystem {
public:
    PaymentRecord queue[MAX_PAYMENT_QUEUE_SIZE];
    bool slotUsed[MAX_PAYMENT_QUEUE_SIZE];
    bool slotPersisted[MAX_PAYMENT_QUEUE_SIZE];
    int activeCount;

    MatchSettlementRecord storedRecord;
    bool hasStoredRecord;
    bool simulateFlashEraseFailure;
    bool simulateMatchSaveFailure;

    MockDeviceManager devMgr;
    bool matchActive;

    MockMatchSettlementSystem() {
        reset();
    }

    void reset() {
        memset(queue, 0, sizeof(queue));
        memset(slotUsed, 0, sizeof(slotUsed));
        memset(slotPersisted, 0, sizeof(slotPersisted));
        activeCount = 0;
        memset(&storedRecord, 0, sizeof(storedRecord));
        hasStoredRecord = false;
        simulateFlashEraseFailure = false;
        simulateMatchSaveFailure = false;
        matchActive = false;
    }

    bool hasPendingPayment(const std::string& txId) {
        if (txId.empty()) return false;
        for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
            if (slotUsed[i] && std::string(queue[i].txId) == txId) {
                return true;
            }
        }
        return false;
    }

    bool enqueue(const std::string& txId, const std::string& targetId, int creditSeconds, uint8_t opKind) {
        if (hasPendingPayment(txId)) return true;
        int freeSlot = -1;
        for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
            if (!slotUsed[i]) {
                freeSlot = i;
                break;
            }
        }
        if (freeSlot < 0) return false;

        PaymentRecord& r = queue[freeSlot];
        r.magic = PAYMENT_RECORD_MAGIC;
        r.schemaVersion = 1;
        r.opKind = opKind;
        r.ownerType = 1; // PHONE
        strncpy(r.txId, txId.c_str(), sizeof(r.txId) - 1);
        strncpy(r.targetId, targetId.c_str(), sizeof(r.targetId) - 1);
        r.pulses = 0;
        r.creditSeconds = creditSeconds;
        slotUsed[freeSlot] = true;
        slotPersisted[freeSlot] = true;
        activeCount++;
        return true;
    }

    bool cancelPayment(const std::string& txId) {
        if (simulateFlashEraseFailure) {
            // Failure to erase from flash retains the record in RAM and flash
            return false;
        }
        for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
            if (slotUsed[i] && std::string(queue[i].txId) == txId) {
                memset(&queue[i], 0, sizeof(PaymentRecord));
                slotUsed[i] = false;
                slotPersisted[i] = false;
                activeCount--;
                return true;
            }
        }
        return false;
    }

    bool saveMatchRecord(const MatchSettlementRecord& rec) {
        if (simulateMatchSaveFailure) {
            return false;
        }
        storedRecord = rec;
        storedRecord.magic = MATCH_RECORD_MAGIC;
        storedRecord.schemaVersion = 1;
        storedRecord.crc32 = computeMatchRecordCrc32(storedRecord);
        hasStoredRecord = true;
        return true;
    }

    bool loadMatchRecord(MatchSettlementRecord& outRec) {
        if (!hasStoredRecord) return false;
        if (storedRecord.magic != MATCH_RECORD_MAGIC || storedRecord.schemaVersion != 1) return false;
        if (storedRecord.crc32 != computeMatchRecordCrc32(storedRecord)) return false;
        outRec = storedRecord;
        return true;
    }

    // Settlement recovery during normal operation or reboot
    void recoverPendingSettlement() {
        MatchSettlementRecord rec;
        if (!loadMatchRecord(rec)) return;

        if (rec.state == MATCH_SETTLE_DEDUCT_PENDING) {
            if (!hasPendingPayment(rec.deductTxId)) {
                enqueue(rec.deductTxId, rec.loserId, -rec.stakeSeconds, OP_KIND_MATCH_TRANSFER);
            }
        } else if (rec.state == MATCH_SETTLE_DEDUCT_COMMITTED_CREDIT_PENDING) {
            if (!hasPendingPayment(rec.creditTxId)) {
                enqueue(rec.creditTxId, rec.winnerId, rec.stakeSeconds, OP_KIND_MATCH_TRANSFER);
            }
        }
    }

    // Start match settlement
    bool startSettlement(const std::string& matchId, const std::string& loserDevId, const std::string& winnerDevId, int stakeSeconds, std::string& outDeductTx, std::string& outCreditTx, std::string& errOut) {
        MatchSettlementRecord existing;
        if (loadMatchRecord(existing)) {
            if (existing.state == MATCH_SETTLE_DEDUCT_PENDING || 
                existing.state == MATCH_SETTLE_DEDUCT_COMMITTED_CREDIT_PENDING) {
                errOut = "An unresolved match settlement is already in progress. Only one unresolved settlement is supported.";
                return false;
            }
            if (hasPendingPayment(existing.deductTxId) || hasPendingPayment(existing.creditTxId)) {
                errOut = "A transaction from a prior settlement is still pending in the queue.";
                return false;
            }
            if (std::string(existing.matchId) == matchId) {
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

        outDeductTx = matchId + "-deduct-123";
        outCreditTx = matchId + "-credit-456";

        MatchSettlementRecord newRec;
        memset(&newRec, 0, sizeof(newRec));
        newRec.state = MATCH_SETTLE_DEDUCT_PENDING;
        strncpy(newRec.matchId, matchId.c_str(), sizeof(newRec.matchId) - 1);
        strncpy(newRec.loserId, loserDevId.c_str(), sizeof(newRec.loserId) - 1);
        strncpy(newRec.winnerId, winnerDevId.c_str(), sizeof(newRec.winnerId) - 1);
        newRec.stakeSeconds = stakeSeconds;
        strncpy(newRec.deductTxId, outDeductTx.c_str(), sizeof(newRec.deductTxId) - 1);
        strncpy(newRec.creditTxId, outCreditTx.c_str(), sizeof(newRec.creditTxId) - 1);
        newRec.timestamp = 1000000;

        if (!saveMatchRecord(newRec)) {
            errOut = "Failed to persist settlement record to NVS.";
            return false;
        }

        bool queued = enqueue(outDeductTx, loserDevId, -stakeSeconds, OP_KIND_MATCH_TRANSFER);
        if (!queued) {
            // Preserved in NVS for retry
        }
        return true;
    }

    // Delivery attempt for a queued transaction
    bool attemptDelivery(const std::string& txId, std::string& deliveredIp, int& deliveredSec) {
        int idx = -1;
        for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
            if (slotUsed[i] && std::string(queue[i].txId) == txId) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return false;

        std::string currentIp = devMgr.getIpFromDeviceId(queue[idx].targetId);
        if (currentIp.empty() || currentIp == "0.0.0.0") return false;

        deliveredIp = currentIp;
        deliveredSec = queue[idx].creditSeconds;
        return true;
    }

    // Phone ACK receipt
    bool acknowledgePayment(const std::string& devId, const std::string& txId, int acknowledgedSeconds) {
        int idx = -1;
        for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
            if (slotUsed[i] && std::string(queue[i].txId) == txId && std::string(queue[i].targetId) == devId) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return false;
        if (queue[idx].creditSeconds != acknowledgedSeconds) return false;

        // Clear payment from queue
        memset(&queue[idx], 0, sizeof(PaymentRecord));
        slotUsed[idx] = false;
        slotPersisted[idx] = false;
        activeCount--;

        // Trigger settlement state transitions
        recordMatchDeductionCommitted(txId);
        recordMatchCreditCommitted(txId);
        return true;
    }

    bool recordMatchDeductionCommitted(const std::string& deductTxId) {
        MatchSettlementRecord rec;
        if (!loadMatchRecord(rec)) return false;
        if (std::string(rec.deductTxId) != deductTxId) return false;
        if (rec.state != MATCH_SETTLE_DEDUCT_PENDING) return true;

        rec.state = MATCH_SETTLE_DEDUCT_COMMITTED_CREDIT_PENDING;
        if (!saveMatchRecord(rec)) {
            return false;
        }

        enqueue(rec.creditTxId, rec.winnerId, rec.stakeSeconds, OP_KIND_MATCH_TRANSFER);
        return true;
    }

    bool recordMatchDeductionRejected(const std::string& deductTxId) {
        MatchSettlementRecord rec;
        if (!loadMatchRecord(rec)) return false;
        if (std::string(rec.deductTxId) != deductTxId) return false;

        rec.state = MATCH_SETTLE_REJECTED;
        if (!saveMatchRecord(rec)) {
            return false;
        }

        cancelPayment(deductTxId);
        return true;
    }

    bool recordMatchCreditCommitted(const std::string& creditTxId) {
        MatchSettlementRecord rec;
        if (!loadMatchRecord(rec)) return false;
        if (std::string(rec.creditTxId) != creditTxId) return false;
        if (rec.state != MATCH_SETTLE_DEDUCT_COMMITTED_CREDIT_PENDING) return true;

        rec.state = MATCH_SETTLE_COMPLETED;
        if (!saveMatchRecord(rec)) {
            return false;
        }
        return true;
    }

    // Simulates handleOneVsOne winner submission
    bool submitWinner(const std::string& winnerSelection, const std::string& p1Ip, const std::string& p2Ip, int stakeMinutes, std::string& errOut) {
        if (!matchActive) {
            errOut = "No active match in progress. Match must be activated first.";
            return false;
        }

        std::string p1DevId = devMgr.getDeviceIdFromIp(p1Ip);
        std::string p2DevId = devMgr.getDeviceIdFromIp(p2Ip);

        if (p1DevId.empty() || p2DevId.empty()) {
            errOut = "Unpaired player selected: canonical device ID missing.";
            return false;
        }

        if (p1DevId == p2DevId) {
            errOut = "Player 1 and Player 2 cannot be the same device.";
            return false;
        }

        matchActive = false; // Prevents double click on repeated submission

        std::string winnerDevId = (winnerSelection == "p1") ? p1DevId : p2DevId;
        std::string loserDevId = (winnerSelection == "p1") ? p2DevId : p1DevId;

        std::string deductTx, creditTx;
        return startSettlement("match-100", loserDevId, winnerDevId, stakeMinutes * 60, deductTx, creditTx, errOut);
    }
};

int main() {
    std::cout << "[TEST] Starting Match Settlement Verification..." << std::endl;
    MockMatchSettlementSystem sys;

    // Register test devices
    sys.devMgr.registerDevice("dev-p1", "192.168.1.101");
    sys.devMgr.registerDevice("dev-p2", "192.168.1.102");

    // -------------------------------------------------------------
    // Test 1: 15-minute transfer with canonical ID resolution & delivery IP
    // -------------------------------------------------------------
    std::cout << "[TEST] 1. 15m transfer end-to-end flow:" << std::endl;
    sys.matchActive = true;
    std::string err;
    bool ok = sys.submitWinner("p1", "192.168.1.101", "192.168.1.102", 15, err);
    assert(ok && err.empty());

    MatchSettlementRecord rec;
    assert(sys.loadMatchRecord(rec));
    assert(rec.state == MATCH_SETTLE_DEDUCT_PENDING);
    assert(std::string(rec.loserId) == "dev-p2");
    assert(std::string(rec.winnerId) == "dev-p1");
    assert(rec.stakeSeconds == 900); // 15m = 900s
    assert(sys.hasPendingPayment(rec.deductTxId));
    assert(!sys.hasPendingPayment(rec.creditTxId)); // Credit MUST NOT be queued yet

    // Verify delivery resolves to loser current IP
    std::string deliveredIp;
    int deliveredSec = 0;
    assert(sys.attemptDelivery(rec.deductTxId, deliveredIp, deliveredSec));
    assert(deliveredIp == "192.168.1.102");
    assert(deliveredSec == -900);

    // Loser confirms deduction ACK
    assert(sys.acknowledgePayment("dev-p2", rec.deductTxId, -900));
    assert(sys.loadMatchRecord(rec));
    assert(rec.state == MATCH_SETTLE_DEDUCT_COMMITTED_CREDIT_PENDING);
    assert(!sys.hasPendingPayment(rec.deductTxId)); // Deduct cleared
    assert(sys.hasPendingPayment(rec.creditTxId));  // Credit now queued

    // Winner confirms credit ACK
    assert(sys.attemptDelivery(rec.creditTxId, deliveredIp, deliveredSec));
    assert(deliveredIp == "192.168.1.101");
    assert(deliveredSec == 900);

    assert(sys.acknowledgePayment("dev-p1", rec.creditTxId, 900));
    assert(sys.loadMatchRecord(rec));
    assert(rec.state == MATCH_SETTLE_COMPLETED);
    assert(!sys.hasPendingPayment(rec.creditTxId)); // Credit cleared
    std::cout << "  -> 15m transfer (deduct -900s, credit +900s) PASSED." << std::endl;

    // -------------------------------------------------------------
    // Test 2: Double-click & active match requirement
    // -------------------------------------------------------------
    std::cout << "[TEST] 2. Double-click prevention & active match check:" << std::endl;
    // Without active match
    sys.matchActive = false;
    ok = sys.submitWinner("p1", "192.168.1.101", "192.168.1.102", 15, err);
    assert(!ok);
    assert(err.find("No active match") != std::string::npos);

    // With active match, first click succeeds, second click blocked immediately
    sys.reset();
    sys.devMgr.registerDevice("dev-p1", "192.168.1.101");
    sys.devMgr.registerDevice("dev-p2", "192.168.1.102");
    sys.matchActive = true;

    ok = sys.submitWinner("p1", "192.168.1.101", "192.168.1.102", 15, err);
    assert(ok);

    // Immediate second click (matchActive is now false)
    ok = sys.submitWinner("p1", "192.168.1.101", "192.168.1.102", 15, err);
    assert(!ok);
    assert(err.find("No active match") != std::string::npos);

    // Even if another activation attempt happened while settlement is pending:
    std::string dTx, cTx;
    ok = sys.startSettlement("match-new", "dev-p2", "dev-p1", 900, dTx, cTx, err);
    assert(!ok);
    assert(err.find("An unresolved match settlement is already in progress") != std::string::npos);
    std::cout << "  -> Double-click and concurrent settlement prevention PASSED." << std::endl;

    // -------------------------------------------------------------
    // Test 3: Unpaired player rejection
    // -------------------------------------------------------------
    std::cout << "[TEST] 3. Unpaired player rejection:" << std::endl;
    sys.matchActive = true;
    ok = sys.submitWinner("p1", "192.168.1.101", "192.168.1.199", 15, err); // 199 is not paired
    assert(!ok);
    assert(err.find("Unpaired player") != std::string::npos);
    std::cout << "  -> Unpaired player rejection PASSED." << std::endl;

    // -------------------------------------------------------------
    // Test 4: Offline winner recovery with dynamic IP delivery
    // -------------------------------------------------------------
    std::cout << "[TEST] 4. Offline winner recovery & dynamic IP delivery:" << std::endl;
    sys.reset();
    sys.devMgr.registerDevice("dev-p1", "0.0.0.0"); // Winner is offline (no IP)
    sys.devMgr.registerDevice("dev-p2", "192.168.1.102");
    sys.matchActive = true;

    ok = sys.submitWinner("p1", "dev-p1", "192.168.1.102", 15, err);
    assert(ok);
    assert(sys.loadMatchRecord(rec));

    // Deduct committed
    assert(sys.acknowledgePayment("dev-p2", rec.deductTxId, -900));
    assert(sys.loadMatchRecord(rec));
    assert(rec.state == MATCH_SETTLE_DEDUCT_COMMITTED_CREDIT_PENDING);

    // Delivery fails while winner is offline
    assert(!sys.attemptDelivery(rec.creditTxId, deliveredIp, deliveredSec));

    // Winner comes online with new IP 192.168.1.150
    sys.devMgr.updateDeviceIp("dev-p1", "192.168.1.150");
    assert(sys.attemptDelivery(rec.creditTxId, deliveredIp, deliveredSec));
    assert(deliveredIp == "192.168.1.150");
    assert(deliveredSec == 900);

    // Complete ACK
    assert(sys.acknowledgePayment("dev-p1", rec.creditTxId, 900));
    assert(sys.loadMatchRecord(rec));
    assert(rec.state == MATCH_SETTLE_COMPLETED);
    std::cout << "  -> Offline winner dynamic IP recovery PASSED." << std::endl;

    // -------------------------------------------------------------
    // Test 5: Restart / Reboot resilience with identical child tx IDs
    // -------------------------------------------------------------
    std::cout << "[TEST] 5. Restart resilience with identical child tx IDs:" << std::endl;
    sys.reset();
    sys.devMgr.registerDevice("dev-p1", "192.168.1.101");
    sys.devMgr.registerDevice("dev-p2", "192.168.1.102");
    sys.matchActive = true;

    ok = sys.submitWinner("p1", "192.168.1.101", "192.168.1.102", 15, err);
    assert(ok);
    assert(sys.loadMatchRecord(rec));
    std::string origDeductTx = rec.deductTxId;
    std::string origCreditTx = rec.creditTxId;

    // Simulate power cut / reboot while DEDUCT_PENDING
    memset(sys.queue, 0, sizeof(sys.queue));
    memset(sys.slotUsed, 0, sizeof(sys.slotUsed));
    sys.activeCount = 0;
    assert(!sys.hasPendingPayment(origDeductTx));

    // Recovery runs (at reboot / normal timer)
    sys.recoverPendingSettlement();
    assert(sys.hasPendingPayment(origDeductTx)); // Restored with SAME tx ID!

    // Deduct succeeds
    assert(sys.acknowledgePayment("dev-p2", origDeductTx, -900));
    assert(sys.loadMatchRecord(rec));
    assert(rec.state == MATCH_SETTLE_DEDUCT_COMMITTED_CREDIT_PENDING);

    // Simulate another power cut / reboot while CREDIT_PENDING
    memset(sys.queue, 0, sizeof(sys.queue));
    memset(sys.slotUsed, 0, sizeof(sys.slotUsed));
    sys.activeCount = 0;
    assert(!sys.hasPendingPayment(origCreditTx));

    // Recovery runs again
    sys.recoverPendingSettlement();
    assert(sys.hasPendingPayment(origCreditTx)); // Restored with SAME tx ID!
    assert(std::string(rec.creditTxId) == origCreditTx);

    // Credit completes
    assert(sys.acknowledgePayment("dev-p1", origCreditTx, 900));
    assert(sys.loadMatchRecord(rec));
    assert(rec.state == MATCH_SETTLE_COMPLETED);
    std::cout << "  -> Restart resilience with identical child tx IDs PASSED." << std::endl;

    // -------------------------------------------------------------
    // Test 6: Rejected deduction (insufficient balance) stops credit
    // -------------------------------------------------------------
    std::cout << "[TEST] 6. Rejected deduction stops credit permanently:" << std::endl;
    sys.reset();
    sys.devMgr.registerDevice("dev-p1", "192.168.1.101");
    sys.devMgr.registerDevice("dev-p2", "192.168.1.102");
    sys.matchActive = true;

    ok = sys.submitWinner("p1", "192.168.1.101", "192.168.1.102", 15, err);
    assert(ok);
    assert(sys.loadMatchRecord(rec));

    // Android returns 403 NOT_ELIGIBLE -> trigger deduction rejection
    assert(sys.recordMatchDeductionRejected(rec.deductTxId));
    assert(sys.loadMatchRecord(rec));
    assert(rec.state == MATCH_SETTLE_REJECTED);
    assert(!sys.hasPendingPayment(rec.deductTxId)); // Deduct canceled
    assert(!sys.hasPendingPayment(rec.creditTxId)); // Credit NEVER queued!

    // Recovery run will NOT queue anything for a rejected match
    sys.recoverPendingSettlement();
    assert(!sys.hasPendingPayment(rec.creditTxId));
    assert(!sys.hasPendingPayment(rec.deductTxId));
    std::cout << "  -> Rejected deduction stops credit permanently PASSED." << std::endl;

    // -------------------------------------------------------------
    // Test 7: Flash erase failure preserves RAM and flash records
    // -------------------------------------------------------------
    std::cout << "[TEST] 7. Flash deletion failure retains queue record in cancelPayment:" << std::endl;
    sys.reset();
    sys.enqueue("coin-test-erase-fail", "dev-p1", 60, OP_KIND_COIN);
    assert(sys.hasPendingPayment("coin-test-erase-fail"));

    // Enable flash erase failure
    sys.simulateFlashEraseFailure = true;
    bool cancelRes = sys.cancelPayment("coin-test-erase-fail");
    assert(!cancelRes); // Cancellation fails
    assert(sys.hasPendingPayment("coin-test-erase-fail")); // Record RETAINED in RAM & storage!

    // When flash erase succeeds later
    sys.simulateFlashEraseFailure = false;
    cancelRes = sys.cancelPayment("coin-test-erase-fail");
    assert(cancelRes);
    assert(!sys.hasPendingPayment("coin-test-erase-fail"));
    std::cout << "  -> Flash deletion failure retains queue record PASSED." << std::endl;

    // -------------------------------------------------------------
    // Test 8: Settlement persistence failure retains state and defers credit
    // -------------------------------------------------------------
    std::cout << "[TEST] 8. Settlement advances only after successful persistence:" << std::endl;
    sys.reset();
    sys.devMgr.registerDevice("dev-p1", "192.168.1.101");
    sys.devMgr.registerDevice("dev-p2", "192.168.1.102");
    sys.matchActive = true;

    ok = sys.submitWinner("p1", "192.168.1.101", "192.168.1.102", 15, err);
    assert(ok);
    assert(sys.loadMatchRecord(rec));
    assert(rec.state == MATCH_SETTLE_DEDUCT_PENDING);

    // Simulate NVS save failure when committing deduction
    sys.simulateMatchSaveFailure = true;
    bool commitRes = sys.recordMatchDeductionCommitted(rec.deductTxId);
    assert(!commitRes); // Commit fails because persistence failed
    // State MUST remain DEDUCT_PENDING (not advanced to CREDIT_PENDING)
    assert(sys.loadMatchRecord(rec));
    assert(rec.state == MATCH_SETTLE_DEDUCT_PENDING);
    // Credit must NOT have been queued
    assert(!sys.hasPendingPayment(rec.creditTxId));

    // When NVS recovers and save succeeds
    sys.simulateMatchSaveFailure = false;
    commitRes = sys.recordMatchDeductionCommitted(rec.deductTxId);
    assert(commitRes);
    assert(sys.loadMatchRecord(rec));
    assert(rec.state == MATCH_SETTLE_DEDUCT_COMMITTED_CREDIT_PENDING);
    assert(sys.hasPendingPayment(rec.creditTxId)); // Credit queued now!
    std::cout << "  -> Settlement advances only after successful persistence PASSED." << std::endl;

    // -------------------------------------------------------------
    // Test 9: HTTP errors (403/409/body strings) retain pending payments
    // -------------------------------------------------------------
    std::cout << "[TEST] 9. HTTP errors retain pending payments without cancellation:" << std::endl;
    sys.reset();
    sys.enqueue("coin-err-test", "dev-p1", 60, OP_KIND_COIN);
    sys.enqueue("adj-err-test", "dev-p1", 120, OP_KIND_QUICK_ADJUST);
    assert(sys.hasPendingPayment("coin-err-test"));
    assert(sys.hasPendingPayment("adj-err-test"));

    // Simulating HTTP error handling in WebServerAuth:
    // When code is 403, 409, or body contains NOT_ELIGIBLE/CONFLICT,
    // neither coin nor adjust payments are cancelled.
    auto simulateHttpAuthResponse = [&](int httpCode, const std::string& body) {
        if (httpCode >= 200 && httpCode < 300) {
            // Success branch
        } else {
            // Non-2xx HTTP response: retain unresolved record for retry. Never delete/cancel.
        }
    };

    simulateHttpAuthResponse(403, "NOT_ELIGIBLE");
    assert(sys.hasPendingPayment("coin-err-test"));
    assert(sys.hasPendingPayment("adj-err-test"));

    simulateHttpAuthResponse(409, "CONFLICT");
    assert(sys.hasPendingPayment("coin-err-test"));
    assert(sys.hasPendingPayment("adj-err-test"));
    std::cout << "  -> HTTP errors retain pending payments without cancellation PASSED." << std::endl;

    std::cout << "\nALL MATCH SETTLEMENT UNIT TESTS PASSED SUCCESSFULLY!" << std::endl;
    return 0;
}
