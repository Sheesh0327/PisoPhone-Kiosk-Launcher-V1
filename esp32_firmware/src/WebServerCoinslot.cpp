// Coin-slot HTTP API used by the phone app:
//   /api/coinslot/arm     reserve the slot for one phone and energise the coin acceptor
//   /api/coinslot/unarm   release the slot (in-flight coins are drained first)
//   /api/coinslot/status  report slot state to the phone
//   /api/coinslot/ack     the phone confirms it credited a coin (removes it from the retry queue)
//
// Flow of one payment: arm -> coin pulses (CoinSlotManager) -> triggerUniversalCoinEvent() queues the
// payment (PaymentQueueManager) and pushes it over WebSocket/HTTP -> phone credits time and calls
// ack -> the queued payment is erased. An unacknowledged payment is retried until it expires.
//
// arm/unarm/ack carry a signature, see coinslotRequestAuthorized().
#include "InputSafety.h"
#include "WebServerCoinslot.h"
#include "CoinTxVisibility.h"
#include "WebServerModule.h"
#include "WebServerAuth.h"
#include "Config.h"
#include "SetupGate.h"
#include "SuperAdminCreds.h"
#include "Security.h"
#include "HardwareManager.h"
#include "DeviceManager.h"
#include "CoinSlotManager.h"
#include "DeviceNetwork.h"
#include "PaymentQueueManager.h"
#include "Diagnostics.h"
#include <WiFi.h>
#include <WebServer.h>

struct SessionCoinTx {
    String devId;
    String txId;
    int pulses;
    int seconds;
    double amount;
    unsigned long ts;
    bool acknowledged;
};

static const int MAX_SESSION_TX = 32;
static const unsigned long SESSION_TX_MAX_AGE_MS =
    15UL * 60UL * 1000UL; // the durable payment queue keeps retrying; this list is only a fast path
static SessionCoinTx sessionTxList[MAX_SESSION_TX];
static int sessionTxCount = 0;

static void purgeOldSessionCoinTx() {
    int writeIdx = 0;
    for (int i = 0; i < sessionTxCount; i++) {
        if (!sessionTxList[i].acknowledged && millis() - sessionTxList[i].ts < SESSION_TX_MAX_AGE_MS) {
            sessionTxList[writeIdx++] = sessionTxList[i];
        }
    }
    sessionTxCount = writeIdx;
}

void recordSessionCoinTx(const String& devId, const String& txId, int pulses, int seconds, double amount) {
    if (txId.length() == 0 || pulses <= 0) return;
    purgeOldSessionCoinTx();
    for (int i = 0; i < sessionTxCount; i++) {
        if (sessionTxList[i].txId == txId) {
            return;
        }
    }
    if (sessionTxCount < MAX_SESSION_TX) {
        sessionTxList[sessionTxCount] = {devId, txId, pulses, seconds, amount, millis(), false};
        sessionTxCount++;
    } else {
        for (int i = 0; i < MAX_SESSION_TX - 1; i++) {
            sessionTxList[i] = sessionTxList[i + 1];
        }
        sessionTxList[MAX_SESSION_TX - 1] = {devId, txId, pulses, seconds, amount, millis(), false};
    }
}

static void clearSessionCoinTx(const String& devId) {
    if (devId.length() == 0) {
        sessionTxCount = 0;
        return;
    }
    int writeIdx = 0;
    for (int i = 0; i < sessionTxCount; i++) {
        if (sessionTxList[i].devId != devId) {
            sessionTxList[writeIdx++] = sessionTxList[i];
        }
    }
    sessionTxCount = writeIdx;
}

static void acknowledgeSessionCoinTx(const String& txId) {
    for (int i = 0; i < sessionTxCount; i++) {
        if (sessionTxList[i].txId == txId) {
            sessionTxList[i].acknowledged = true;
            return;
        }
    }
}

// Coin-slot calls from the phone carry ts + sig = HMAC(shared secret, "v1:<action>:<device>:<ts>[:<tx>]").
// A present-but-wrong signature is always refused, and so is an unsigned call: every app build since 2026-10-02 signs
// arm/unarm/ack/status, and an unsigned ack or unarm from anyone on the kiosk network could otherwise drop a phone's
// queued coins or end its window. Only for a fleet that still runs older phones: build with
// -DPISO_REQUIRE_SIGNED_COINSLOT=0 (unsigned calls are then served and counted in the diagnostics).
#ifndef PISO_REQUIRE_SIGNED_COINSLOT
#define PISO_REQUIRE_SIGNED_COINSLOT 1
#endif
enum class SigCheck { Ok, Missing, BadSignature, StaleTimestamp };

// Why a request is or is not accepted: a correct signature (the phone has this box's secret) is told apart from a
// correct signature with a time outside the box's window (the phone's clock is off), because only the second can be
// healed by the phone itself: the refusal then carries the box's time.
static SigCheck coinslotSignatureCheck(const char* action, const String& rawDevId, const String& txId) {
    if (!(webServer.hasArg("sig") && webServer.hasArg("ts"))) return SigCheck::Missing;
    String ts = webServer.arg("ts");
    String sig = webServer.arg("sig");
    String payload = "v1:" + String(action) + ":" + rawDevId + ":" + ts;
    if (txId.length() > 0) payload += ":" + txId;
    if (rawDevId.length() == 0 || !sig.equalsIgnoreCase(calculateHMAC(payload, getSharedSecret())))
        return SigCheck::BadSignature;
    if (!checkReplayProtection(rawDevId, strtoull(ts.c_str(), NULL, 10))) return SigCheck::StaleTimestamp;
    return SigCheck::Ok;
}

// True only for a request carrying a correct, fresh signature for this action, device and transaction.
static bool coinslotSignatureValid(const char* action, const String& rawDevId, const String& txId) {
    return coinslotSignatureCheck(action, rawDevId, txId) == SigCheck::Ok;
}

static bool coinslotRequestAuthorized(const char* action, const String& rawDevId, const String& txId) {
    bool hasSig = webServer.hasArg("sig") && webServer.hasArg("ts");
    if (!hasSig) {
#if PISO_REQUIRE_SIGNED_COINSLOT
        webServer.send(403, "application/json", "{\"success\":false,\"error\":\"SIGNATURE_REQUIRED\"}");
        return false;
#else
        static uint32_t unsignedCount = 0;
        if ((unsignedCount++ % 50) == 0) {
            diagLog("[AUTH] Unsigned /api/coinslot/%s call served (#%u); update the phone app.\n", action,
                    (unsigned)unsignedCount);
        }
        return true;
#endif
    }
    SigCheck check = coinslotSignatureCheck(action, rawDevId, txId);
    if (check != SigCheck::Ok) {
        bool stale = (check == SigCheck::StaleTimestamp);
        diagLog("[AUTH] Rejected /api/coinslot/%s from %s: %s\n", action,
                webServer.client().remoteIP().toString().c_str(),
                stale ? "the phone's clock is outside the box's window (the refusal carries the box's time)"
                      : "signature does not match (the phone has a different box secret)");
        webServer.send(403, "application/json",
                       String("{\"success\":false,\"error\":\"AUTH_FAILED\",\"reason\":\"") +
                           (stale ? "STALE_TIMESTAMP" : "BAD_SIGNATURE") + "\"" + boxTimeJsonField() + "}");
    }
    return check == SigCheck::Ok;
}

// Account calls are always signed (the PISO_REQUIRE_SIGNED_COINSLOT escape hatch for old phones does not apply: there
// are no old phones with accounts). `bound` is the part of the signed message that carries the call's parameters.
bool accountRequestAuthorized(const char* op, const String& devId, const String& bound) {
    String action = String("acct_") + op;
    SigCheck check = coinslotSignatureCheck(action.c_str(), devId, bound);
    if (check == SigCheck::Ok) return true;
    bool stale = (check == SigCheck::StaleTimestamp);
    if (check != SigCheck::Missing) {
        diagLog("[AUTH] Rejected /api/account/%s from %s: %s\n", op, webServer.client().remoteIP().toString().c_str(),
                stale ? "the phone's clock is outside the box's window" : "signature does not match");
    }
    webServer.send(403, "application/json",
                   String("{\"success\":false,\"error\":\"AUTH_FAILED\",\"reason\":\"") +
                       (stale ? "STALE_TIMESTAMP" : "BAD_SIGNATURE") + "\"" + boxTimeJsonField() + "}");
    return false;
}

void handleApiCoinslotArm() {
    String devId = webServer.hasArg("device_id") ? webServer.arg("device_id")
                                                 : (webServer.hasArg("id") ? webServer.arg("id") : "");
    devId.trim();
    if (!coinslotRequestAuthorized("arm", devId, "")) return;
    if (!setupgate::usageAllowed(adminPwChanged)) {
        webServer.send(
            403, "application/json",
            "{\"success\":false,\"status\":\"error\",\"error\":\"SETUP_REQUIRED\",\"message\":\"The box's admin password has not been changed yet.\"}");
        return;
    }
    String reqIp = webServer.hasArg("ip") ? webServer.arg("ip") : "";
    reqIp.trim();
    if (reqIp.length() == 0 || reqIp == "127.0.0.1" || reqIp == "0.0.0.0") {
        reqIp = webServer.client().remoteIP().toString();
    }
    int durationSec = webServer.hasArg("duration") ? webServer.arg("duration").toInt() : 45;
    if (durationSec <= 0 || durationSec > 300) durationSec = 45;

    if (devId.length() == 0 && reqIp.length() > 0 && reqIp != "127.0.0.1" && reqIp != "0.0.0.0") {
        devId = "DEV_" + reqIp;
    }

    int slotIdx = findSlotIndexForDevice(devId, reqIp);
    if (slotIdx < 0) {
        updateDynamicDeviceList(devId, reqIp);
        slotIdx = findSlotIndexForDevice(devId, reqIp);
    }
    if (slotIdx < 0) {
        String json =
            "{\"success\":false,\"status\":\"unpaired\",\"error\":\"SLOT_NOT_PAIRED\",\"message\":\"Device is not paired to any slot on this ESP32.\"}";
        webServer.send(423, "application/json", json);
        return;
    }

    if (!isSlotActive(slotIdx)) {
        String json = "{\"success\":false,\"status\":\"locked\",\"error\":\"SLOT_EXPIRED\",\"slot\":" +
                      String(phoneSlots[slotIdx].slotNum) + ",\"message\":\"Device slot is expired or inactive.\"}";
        webServer.send(423, "application/json", json);
        return;
    }

    if (isCoinSlotBusy(devId, CoinSlotOwnerType::PHONE)) {
        String holder = getActiveCoinSessionId();
        String json = "{\"success\":false,\"status\":\"busy\",\"error\":\"SLOT_BUSY\",\"holder\":\"" + jsonEsc(holder) +
                      "\",\"message\":\"Coin slot is currently in use by another device.\"}";
        webServer.send(409, "application/json", json);
        return;
    }

    unsigned long ttlMs = durationSec * 1000UL;
    bool reserved = reserveCoinSlot(
        devId, CoinSlotOwnerType::PHONE, ttlMs,
        [](const String& id, int pulses) { triggerUniversalCoinEvent(pulses, id); },
        [](const String& id, const char* reason) {
            Serial.printf("[⚡ COIN SLOT] Session ended for %s (%s)\n", id.c_str(), reason);
        });

    if (!reserved) {
        // Say why: a storage fault or a full payment queue looks identical to the phone otherwise.
        const char* why =
            !isPaymentStorageReady() ? "STORAGE_UNAVAILABLE" : (isPaymentQueueFull() ? "QUEUE_FULL" : "ARM_FAILED");
        diagLog("[API] Arm refused for '%s': %s (pending payments: %d)\n", devId.c_str(), why,
                getPendingPaymentCount());
        webServer.send(500, "application/json",
                       String("{\"success\":false,\"status\":\"error\",\"error\":\"") + why + "\"}");
        return;
    }

    clearSessionCoinTx(devId);

    int sNum = phoneSlots[slotIdx].slotNum;
    String json = "{\"success\":true,\"status\":\"armed\",\"slot\":" + String(sNum) +
                  ",\"duration\":" + String(durationSec) + ",\"minutes_per_coin\":" + String(minutesPerCoin) +
                  ",\"price\":1.0,\"settle_ms\":" + String(getCoinSlotSettleRemainingMs()) + "}";
    webServer.send(200, "application/json", json);
}

void handleApiCoinslotUnarm() {
    String devId = webServer.hasArg("device_id") ? webServer.arg("device_id")
                                                 : (webServer.hasArg("id") ? webServer.arg("id") : "");
    devId.trim();
    if (!coinslotRequestAuthorized("unarm", devId, "")) return;
    if (devId.length() == 0) {
        devId = getActiveCoinSessionId();
    }

    releaseCoinSlot(devId, CoinSlotOwnerType::PHONE, false, "CLIENT_DONE");
    webServer.send(200, "application/json", "{\"success\":true,\"status\":\"idle\"}");
}

void handleApiCoinslotStatus() {
    String devId = webServer.hasArg("device_id") ? webServer.arg("device_id")
                                                 : (webServer.hasArg("id") ? webServer.arg("id") : "");
    devId.trim();
    if (devId.length() == 0) {
        String reqIp = webServer.client().remoteIP().toString();
        devId = getDeviceIdFromIp(reqIp);
    }

    CoinSlotState st = getCoinSlotState();
    String stateStr = (st == CoinSlotState::ARMED) ? "ARMED" : ((st == CoinSlotState::DRAINING) ? "DRAINING" : "IDLE");
    String activeDev = getActiveCoinSessionId();
    bool isArmed = isCoinSlotArmed();

    int remainingSec = 0;
    unsigned long armedUntil = getCoinSlotArmedUntilMs();
    if (isArmed && armedUntil > millis()) {
        remainingSec = (int)((armedUntil - millis()) / 1000UL);
    }

    String json = "{";
    json += "\"success\":true,";
    json += "\"armed\":" + String(isArmed ? "true" : "false") + ",";
    json += "\"state\":\"" + stateStr + "\",";
    json += "\"holder\":\"" + jsonEsc(activeDev) + "\",";
    json += "\"remaining_seconds\":" + String(remainingSec) + ",";
    json += "\"minutes_per_coin\":" + String(minutesPerCoin) + ",";
    json += "\"transactions\":[";

    // Coins are listed only to the phone they belong to, on a signed request (see coinTxVisibleTo). An unsigned or
    // other phone's request gets the slot state but no transactions; the phone still receives its coins by push.
    purgeOldSessionCoinTx(); // expired entries are dropped before anything is listed
    bool signedOk = coinslotSignatureValid("status", devId, "");
    bool first = true;
    for (int i = 0; i < sessionTxCount; i++) {
        if (!coinTxVisibleTo(sessionTxList[i].devId, devId, signedOk, sessionTxList[i].acknowledged)) continue;
        if (!first) json += ",";
        first = false;
        json += "{";
        json += "\"device_id\":\"" + jsonEsc(sessionTxList[i].devId) + "\",";
        json += "\"tx_id\":\"" + jsonEsc(sessionTxList[i].txId) + "\",";
        json += "\"pulses\":" + String(sessionTxList[i].pulses) + ",";
        json += "\"amount\":" + String(sessionTxList[i].amount, 2) + ",";
        json += "\"seconds\":" + String(sessionTxList[i].seconds) + ",";
        json += "\"minutes\":" + String(sessionTxList[i].seconds / 60) + ",";
        json += "\"acknowledged\":" + String(sessionTxList[i].acknowledged ? "true" : "false") + ",";
        json += "\"ts\":" + String(sessionTxList[i].ts);
        json += "}";
    }
    json += "]}";
    webServer.send(200, "application/json", json);
}

void handleApiCoinslotAck() {
    String txId = webServer.hasArg("tx_id") ? webServer.arg("tx_id") : "";
    txId.trim();
    String devId = webServer.hasArg("device_id") ? webServer.arg("device_id") : "";
    devId.trim();
    if (!coinslotRequestAuthorized("ack", devId, txId)) return;

    if (txId.length() > 0) {
        acknowledgeSessionCoinTx(txId);
        if (devId.length() > 0) {
            acknowledgePhonePayment(devId, txId);
        }
    }
    webServer.send(200, "application/json", "{\"success\":true,\"tx_id\":\"" + jsonEsc(txId) + "\"}");
}
