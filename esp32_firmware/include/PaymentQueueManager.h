#ifndef PAYMENT_QUEUE_MANAGER_H
#define PAYMENT_QUEUE_MANAGER_H

#include <Arduino.h>
#include <stdint.h>
#include "CoinSlotManager.h"

enum PaymentOpKind : uint8_t {
    OP_KIND_COIN = 1,
    OP_KIND_QUICK_ADJUST = 2,
    OP_KIND_MANUAL_DEDUCT = 3,
    OP_KIND_MATCH_TRANSFER = 4,
    OP_KIND_CONTROLLER = 5
};

static const uint32_t PAYMENT_RECORD_MAGIC = 0x50415933UL; // "PAY3"
static const uint32_t PAYMENT_RECORD_MAGIC_V2 = 0x50415932UL; // "PAY2" for migration
static const uint16_t PAYMENT_SCHEMA_VERSION = 1;

struct PaymentRecord {
    uint32_t magic;                  // 0x50415933
    uint16_t schemaVersion;          // 1
    uint8_t opKind;                  // PaymentOpKind (1=COIN, 2=ADJUST, etc.)
    uint8_t ownerType;               // 1 = PHONE, 2 = CONTROLLER
    char txId[64];                   // Globally unique collision-resistant ID
    char targetId[97];               // Recipient (device_id or session_id)
    int32_t pulses;                  // Integer coin amount (0 for manual adjustments)
    int32_t creditSeconds;           // Signed seconds (+ for add, - for deduct)
    double pricePerCoin;             // Frozen price per coin at acceptance
    uint64_t boxInstallationEpoch;   // Box installation epoch
    uint64_t phonePairingEpoch;     // Pairing epoch
    uint64_t timestamp;              // Event timestamp (epoch ms)
    uint32_t crc32;                  // Record integrity checksum
};

void initPaymentQueue();
String generateCollisionResistantTxId(const char* prefix = "tx");
uint32_t computeRecordCrc32(const PaymentRecord& rec);

bool enqueuePendingPayment(const String& txId, const String& targetId, int pulses,
                           CoinSlotOwnerType ownerType, int creditSeconds = 0,
                           PaymentOpKind opKind = OP_KIND_COIN,
                           double pricePerCoin = 0.0,
                           uint64_t boxEpoch = 0, uint64_t phoneEpoch = 0);

bool acknowledgeControllerPayment(const String& sessionId, const String& txId);
bool isPaymentQueueFull();
bool isPaymentStorageReady();
bool hasUnpersistedPayments();
int getQuarantinedRecordCount();
#define MAINT_REASON_HEAP     0x01
#define MAINT_REASON_RESTART  0x02
#define MAINT_REASON_OTA      0x04
#define MAINT_REASON_STORAGE  0x08
#define MAINT_REASON_RESET    0x10

void setMaintenanceReason(uint32_t reason, bool enable);
void setMaintenanceMode(bool enable);
bool isMaintenanceMode();
bool isMaintenanceReasonActive(uint32_t reason);
bool hasPendingPaymentsForTarget(const String& targetId);
bool hasPendingPayments();
void processPendingSystemRestart();
void setFactoryResetPending(bool pending);
bool canPerformRebootOrOta();
bool requestSystemRestart(const char* reason, unsigned long timeoutMs = 15000);
int getPendingPaymentCount();
bool hasPendingPayment(const String& txId);

bool acknowledgePhonePayment(
    const String& deviceId,
    const String& txId,
    int acknowledgedPulses,
    int acknowledgedSeconds,
    const String& status,
    uint8_t expectedOpKind = 0);

bool cancelPaymentRecord(const String& txId);

enum MatchSettleState : uint8_t {
    MATCH_SETTLE_NONE = 0,
    MATCH_SETTLE_DEDUCT_PENDING = 1,
    MATCH_SETTLE_DEDUCT_COMMITTED_CREDIT_PENDING = 2,
    MATCH_SETTLE_COMPLETED = 3,
    MATCH_SETTLE_REJECTED = 4
};

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

void initMatchSettlement();
void recoverPendingMatchSettlement();
bool startMatchSettlement(
    const String& matchId,
    const String& loserId,
    const String& winnerId,
    int stakeSeconds,
    String& outDeductTxId,
    String& outCreditTxId,
    String& errOut);
bool recordMatchDeductionCommitted(const String& deductTxId);
bool recordMatchDeductionRejected(const String& deductTxId);
bool recordMatchCreditCommitted(const String& creditTxId);
String getMatchSettlementStatusHtml(const String& currentMatchId = "");

void dispatchPendingControllerPayments(const String& sessionId);
void processPendingPaymentRetries();

#endif // PAYMENT_QUEUE_MANAGER_H
