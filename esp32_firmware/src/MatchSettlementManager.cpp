#include "MatchSettlementManager.h"
#include "PaymentQueueManager.h"
#include "Config.h"
#include <Preferences.h>

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
        Serial.printf("[MATCH SETTLE] Failed to save credit pending state for match '%s'. State retained for retry; credit submission deferred.\n", rec.matchId);
        return false;
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
    if (!saveMatchRecord(rec)) {
        Serial.printf("[MATCH SETTLE] Failed to persist REJECTED state for match '%s'. Retaining state for retry.\n", rec.matchId);
        return false;
    }

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
        Serial.printf("[MATCH SETTLE] Failed to save completed state for match '%s'. Retaining state for retry.\n", rec.matchId);
        return false;
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
