#include <iostream>
#include <string>
#include <cassert>
#include <cstring>
#include <vector>
#include <cstdint>
#include <cstddef>

// Emulate types and constants from PaymentQueueManager.h for standalone host unit testing
enum PaymentOpKind : uint8_t {
    OP_KIND_COIN = 1,
    OP_KIND_QUICK_ADJUST = 2,
    OP_KIND_MANUAL_DEDUCT = 3,
    OP_KIND_MATCH_TRANSFER = 4,
    OP_KIND_CONTROLLER = 5
};

static const uint32_t PAYMENT_RECORD_MAGIC = 0x50415933UL; // "PAY3"
static const uint32_t PAYMENT_RECORD_MAGIC_V2 = 0x50415932UL; // "PAY2"
static const uint16_t PAYMENT_SCHEMA_VERSION = 1;

struct PaymentRecord {
    uint32_t magic;                  // 0x50415933
    uint16_t schemaVersion;          // 1
    uint8_t opKind;                  // PaymentOpKind
    uint8_t ownerType;               // 1 = PHONE, 2 = CONTROLLER
    char txId[64];                   // Collision-resistant ID
    char targetId[97];               // Recipient
    int32_t pulses;                  // Integer coin amount
    int32_t creditSeconds;           // Signed seconds (+ for credit, - for deduct)
    double pricePerCoin;             // Frozen price per coin
    uint64_t boxInstallationEpoch;   // Box installation epoch
    uint64_t phonePairingEpoch;     // Pairing epoch
    uint64_t timestamp;              // Event timestamp
    uint32_t crc32;                  // CRC32 checksum
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

static bool validRecord(const PaymentRecord& rec) {
    if (rec.magic != PAYMENT_RECORD_MAGIC) return false;
    if (rec.schemaVersion != PAYMENT_SCHEMA_VERSION) return false;
    if (rec.txId[0] == '\0' || rec.targetId[0] == '\0') return false;
    if (rec.ownerType != 1 && rec.ownerType != 2) return false;
    if (rec.crc32 != computeRecordCrc32(rec)) return false;
    return true;
}

// Queue simulation for testing failure cuts, capacity reservation, and quarantine
static const int MAX_PAYMENT_QUEUE_SIZE = 20;
static const int RESERVED_SLOTS = 2;

struct MockQueueState {
    PaymentRecord records[MAX_PAYMENT_QUEUE_SIZE];
    bool slotUsed[MAX_PAYMENT_QUEUE_SIZE];
    bool slotPersisted[MAX_PAYMENT_QUEUE_SIZE];
    int activeCount;
    int quarantineCount;
    bool storageReady;

    MockQueueState() {
        reset();
    }

    void reset() {
        std::memset(records, 0, sizeof(records));
        std::memset(slotUsed, 0, sizeof(slotUsed));
        std::memset(slotPersisted, 0, sizeof(slotPersisted));
        activeCount = 0;
        quarantineCount = 0;
        storageReady = true;
    }

    bool hasUnpersisted() const {
        for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
            if (slotUsed[i] && !slotPersisted[i]) return true;
        }
        return false;
    }

    bool isQueueFull() const {
        if (!storageReady) return true;
        if (quarantineCount > 0) return true; // Corruption blocks acceptance!
        if (hasUnpersisted()) return true;    // Unwritten records block acceptance!
        return (activeCount >= (MAX_PAYMENT_QUEUE_SIZE - RESERVED_SLOTS));
    }

    // Simulate recovery from raw flash buffer of arbitrary length
    void recoverSlot(int index, const uint8_t* rawData, size_t length) {
        if (length == 0) return;

        if (length == sizeof(PaymentRecord)) {
            PaymentRecord rec;
            std::memcpy(&rec, rawData, sizeof(rec));
            if (validRecord(rec)) {
                records[index] = rec;
                slotUsed[index] = true;
                slotPersisted[index] = true;
                activeCount++;
                return;
            }
        }

        // Check legacy V2 migration
        struct LegacyRecordV2 {
            uint32_t magic;
            char txId[64];
            char targetId[97];
            int pulses;
            int creditSeconds;
            uint8_t ownerType;
            uint64_t timestamp;
        };

        if (length == sizeof(LegacyRecordV2)) {
            LegacyRecordV2 legacy;
            std::memcpy(&legacy, rawData, sizeof(legacy));
            if (legacy.magic == PAYMENT_RECORD_MAGIC_V2 && legacy.txId[0] != '\0') {
                PaymentRecord rec;
                std::memset(&rec, 0, sizeof(rec));
                rec.magic = PAYMENT_RECORD_MAGIC;
                rec.schemaVersion = PAYMENT_SCHEMA_VERSION;
                rec.opKind = (legacy.ownerType == 2) ? OP_KIND_CONTROLLER : OP_KIND_COIN;
                rec.ownerType = legacy.ownerType;
                std::strncpy(rec.txId, legacy.txId, sizeof(rec.txId) - 1);
                std::strncpy(rec.targetId, legacy.targetId, sizeof(rec.targetId) - 1);
                rec.pulses = legacy.pulses;
                rec.creditSeconds = legacy.creditSeconds;
                rec.pricePerCoin = 5.0;
                rec.timestamp = legacy.timestamp;
                rec.crc32 = computeRecordCrc32(rec);

                records[index] = rec;
                slotUsed[index] = true;
                slotPersisted[index] = true;
                activeCount++;
                return;
            }
        }

        // Truncated, torn write, or corrupted record: quarantine!
        quarantineCount++;
    }

    // Acknowledge phone payment and verify matching/conflict
    enum AckOutcome { ACK_OK, ACK_NOT_FOUND, ACK_CONFLICT };

    AckOutcome acknowledgePhone(const char* deviceId, const char* txId, int pulses, int seconds, uint8_t expectedOpKind = 0) {
        for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE; i++) {
            if (!slotUsed[i] || records[i].ownerType != 1) continue;
            if (std::strcmp(records[i].txId, txId) != 0) continue;

            // Target check
            if (std::strcmp(records[i].targetId, deviceId) != 0) {
                return ACK_CONFLICT;
            }

            // Expected op check
            if (expectedOpKind != 0 && records[i].opKind != expectedOpKind) {
                return ACK_CONFLICT;
            }

            // Pulses & seconds matching check
            if (records[i].pulses != pulses || records[i].creditSeconds != seconds) {
                return ACK_CONFLICT;
            }

            // Valid match
            slotUsed[i] = false;
            slotPersisted[i] = false;
            activeCount--;
            return ACK_OK;
        }
        return ACK_NOT_FOUND;
    }
};

int main() {
    std::cout << "[TEST] Starting PisoPhone PaymentQueueManager Verification..." << std::endl;

    // 1. Record Integrity & CRC32 Verification
    std::cout << "[TEST] 1. Record creation, frozen pricing, and CRC32 verification:" << std::endl;
    PaymentRecord rec;
    std::memset(&rec, 0, sizeof(rec));
    rec.magic = PAYMENT_RECORD_MAGIC;
    rec.schemaVersion = PAYMENT_SCHEMA_VERSION;
    rec.opKind = OP_KIND_COIN;
    rec.ownerType = 1;
    std::strncpy(rec.txId, "tx-1710000000-abcd1234efgh5678", sizeof(rec.txId) - 1);
    std::strncpy(rec.targetId, "device-phone-slot-1", sizeof(rec.targetId) - 1);
    rec.pulses = 2;
    rec.creditSeconds = 1200;
    rec.pricePerCoin = 5.0; // frozen pricing
    rec.boxInstallationEpoch = 1710000000L;
    rec.phonePairingEpoch = 1710050000L;
    rec.timestamp = 1710050100000ULL;
    rec.crc32 = computeRecordCrc32(rec);

    assert(validRecord(rec));
    std::cout << "  -> Valid record with CRC32: 0x" << std::hex << rec.crc32 << std::dec << " PASSED." << std::endl;

    // Tampering test
    PaymentRecord tampered = rec;
    tampered.creditSeconds = 1800; // tampered credit seconds
    assert(!validRecord(tampered));
    std::cout << "  -> Tampered record detected and rejected by CRC32 PASSED." << std::endl;

    // 2. Failure Cut Simulation (Power Loss during write)
    std::cout << "[TEST] 2. Failure cut / torn write simulation & quarantine:" << std::endl;
    MockQueueState queue;

    // A. Valid record recovery
    queue.recoverSlot(0, reinterpret_cast<const uint8_t*>(&rec), sizeof(rec));
    assert(queue.activeCount == 1);
    assert(queue.quarantineCount == 0);
    assert(!queue.isQueueFull());

    // B. Half-written record (power cut mid-write: 50 bytes instead of sizeof(rec))
    uint8_t tornBuffer[50];
    std::memcpy(tornBuffer, &rec, sizeof(tornBuffer));
    queue.recoverSlot(1, tornBuffer, sizeof(tornBuffer));
    assert(queue.quarantineCount == 1);
    // CRITICAL: Corruption must BLOCK new acceptance!
    assert(queue.isQueueFull());
    std::cout << "  -> Torn write (50 bytes) properly quarantined; new acceptance blocked PASSED." << std::endl;

    // C. Bit flip in storage (corrupted magic)
    PaymentRecord badMagic = rec;
    badMagic.magic = 0xDEADBEEF;
    queue.recoverSlot(2, reinterpret_cast<const uint8_t*>(&badMagic), sizeof(badMagic));
    assert(queue.quarantineCount == 2);
    assert(queue.isQueueFull());
    std::cout << "  -> Corrupted magic quarantined; new acceptance blocked PASSED." << std::endl;

    // 3. Duplicate and Conflicting ACK Rejection
    std::cout << "[TEST] 3. Duplicate and Conflicting ACK Rejection:" << std::endl;
    queue.reset();
    queue.recoverSlot(0, reinterpret_cast<const uint8_t*>(&rec), sizeof(rec));
    assert(queue.activeCount == 1);

    // Conflicting target ID
    assert(queue.acknowledgePhone("wrong-device", rec.txId, rec.pulses, rec.creditSeconds, OP_KIND_COIN) == MockQueueState::ACK_CONFLICT);
    assert(queue.activeCount == 1); // Record must not be dropped on conflict

    // Conflicting seconds
    assert(queue.acknowledgePhone(rec.targetId, rec.txId, rec.pulses, 9999, OP_KIND_COIN) == MockQueueState::ACK_CONFLICT);
    assert(queue.activeCount == 1);

    // Conflicting pulses
    assert(queue.acknowledgePhone(rec.targetId, rec.txId, 99, rec.creditSeconds, OP_KIND_COIN) == MockQueueState::ACK_CONFLICT);
    assert(queue.activeCount == 1);

    // Conflicting opKind
    assert(queue.acknowledgePhone(rec.targetId, rec.txId, rec.pulses, rec.creditSeconds, OP_KIND_QUICK_ADJUST) == MockQueueState::ACK_CONFLICT);
    assert(queue.activeCount == 1);

    // Matching ACK
    assert(queue.acknowledgePhone(rec.targetId, rec.txId, rec.pulses, rec.creditSeconds, OP_KIND_COIN) == MockQueueState::ACK_OK);
    assert(queue.activeCount == 0);

    // Duplicate ACK on already cleared transaction
    assert(queue.acknowledgePhone(rec.targetId, rec.txId, rec.pulses, rec.creditSeconds, OP_KIND_COIN) == MockQueueState::ACK_NOT_FOUND);
    std::cout << "  -> Conflicting and duplicate ACK tests PASSED." << std::endl;

    // 4. Capacity Reservation: In-flight reservation
    std::cout << "[TEST] 4. Capacity reservation & queue saturation:" << std::endl;
    queue.reset();
    for (int i = 0; i < MAX_PAYMENT_QUEUE_SIZE - RESERVED_SLOTS; i++) {
        PaymentRecord item = rec;
        snprintf(item.txId, sizeof(item.txId), "tx-batch-%02d", i);
        item.crc32 = computeRecordCrc32(item);
        queue.recoverSlot(i, reinterpret_cast<const uint8_t*>(&item), sizeof(item));
    }
    assert(queue.activeCount == (MAX_PAYMENT_QUEUE_SIZE - RESERVED_SLOTS));
    // At activeCount == 18, queue is considered full to reserve 2 slots for in-flight pulses
    assert(queue.isQueueFull());
    std::cout << "  -> Queue saturation stops acceptance before exhausting reserved slots PASSED." << std::endl;

    // 5. Unpersisted payments block acceptance
    queue.reset();
    queue.slotUsed[0] = true;
    queue.slotPersisted[0] = false; // in-memory pulse before flash write
    queue.activeCount = 1;
    assert(queue.isQueueFull());
    std::cout << "  -> Unpersisted counted pulse retains memory and blocks acceptance until durable PASSED." << std::endl;

    std::cout << "ALL PAYMENT QUEUE MANAGER TESTS PASSED SUCCESSFULLY!" << std::endl;
    return 0;
}
