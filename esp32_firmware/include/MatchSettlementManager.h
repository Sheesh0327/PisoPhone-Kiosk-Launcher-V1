#ifndef MATCH_SETTLEMENT_MANAGER_H
#define MATCH_SETTLEMENT_MANAGER_H

#include <Arduino.h>
#include <stdint.h>

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

uint32_t computeMatchRecordCrc32(const MatchSettlementRecord& rec);
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

#endif // MATCH_SETTLEMENT_MANAGER_H
