// Player-account HTTP API (phone -> box). The account table lives in AccountStorage; this file only checks who is
// asking, turns requests into table changes and answers. The message format is AccountProtocol.h.

#include "WebServerAccounts.h"

#include "AccountProtocol.h"
#include "AccountStorage.h"
#include "Config.h"
#include "DeviceManager.h"
#include "Diagnostics.h"
#include "InputSafety.h"
#include "Security.h"
#include "SetupGate.h"
#include "WebServerCoinslot.h"
#include "WebServerModule.h"

#include <WebServer.h>

using accounts::Result;

static const uint32_t SILENT_PHONE_MS = 90000UL;
static const uint32_t CREATE_MIN_GAP_MS = 3000UL; // each new account is a flash write: slow floods down

static uint32_t lastReportMs[MAX_SUPPORTED_SLOTS + 1]; // when each slot's phone last reported (index = slot number)
static uint32_t lastCreateMs = 0;

static const char* errorName(Result r) {
    switch (r) {
    case Result::OK:
    case Result::DUPLICATE_TX:
        return "";
    case Result::BAD_NAME:
        return "BAD_NAME";
    case Result::BAD_PIN_FORMAT:
        return "BAD_PIN_FORMAT";
    case Result::NAME_TAKEN:
        return "NAME_TAKEN";
    case Result::ACCOUNTS_FULL:
        return "ACCOUNTS_FULL";
    case Result::NO_SUCH_USER:
        return "NO_SUCH_USER";
    case Result::BAD_PIN:
        return "BAD_PIN";
    case Result::LOCKED:
        return "LOCKED";
    case Result::ALREADY_SIGNED_IN:
        return "ALREADY_SIGNED_IN";
    case Result::NOT_SIGNED_IN:
        return "NOT_SIGNED_IN";
    default:
        return "INTERNAL";
    }
}

static void answer(int code, bool ok, const char* error, const accounts::Account* a) {
    String json = String("{\"success\":") + (ok ? "true" : "false");
    if (error && error[0]) json += String(",\"error\":\"") + error + "\"";
    if (a) {
        json += String(",\"username\":\"") + a->username + "\",\"balance_sec\":" + String(a->balanceSec);
    }
    json += boxTimeJsonField() + "}";
    webServer.send(code, "application/json", json);
}

static int httpCodeFor(Result r) {
    switch (r) {
    case Result::OK:
    case Result::DUPLICATE_TX:
        return 200;
    case Result::NO_SUCH_USER:
        return 404;
    case Result::NAME_TAKEN:
    case Result::ALREADY_SIGNED_IN:
        return 409;
    case Result::LOCKED:
        return 429;
    case Result::BAD_PIN:
        return 401;
    case Result::ACCOUNTS_FULL:
        return 507;
    case Result::INTERNAL:
        return 500;
    default:
        return 400;
    }
}

// What every account call needs before it does anything: a paired, active phone and a correct signature over the
// call's parameters. On failure the reply has been sent.
struct Caller {
    String deviceId;
    int slotNum = 0;
};

static bool authorizeCall(const char* op, const String& bound, Caller& c) {
    c.deviceId = webServer.hasArg("device_id") ? webServer.arg("device_id") : "";
    c.deviceId.trim();
    if (!accountRequestAuthorized(op, c.deviceId, bound)) return false;
    if (!accountsReady()) {
        answer(503, false, "ACCOUNTS_OFF", nullptr);
        return false;
    }
    if (!setupgate::usageAllowed(adminPwChanged)) {
        answer(403, false, "SETUP_REQUIRED", nullptr);
        return false;
    }
    String ip = webServer.client().remoteIP().toString();
    int idx = findSlotIndexForDevice(c.deviceId, ip);
    if (idx < 0) {
        answer(423, false, "SLOT_NOT_PAIRED", nullptr);
        return false;
    }
    if (!isSlotActive(idx)) {
        answer(423, false, "SLOT_EXPIRED", nullptr);
        return false;
    }
    c.slotNum = phoneSlots[idx].slotNum;
    return true;
}

static String userArg() {
    String u = webServer.hasArg("user") ? webServer.arg("user") : "";
    u.trim();
    u.toLowerCase();
    return u;
}

void handleApiAccountCreate() {
    Caller c;
    String user = userArg();
    String enc = webServer.hasArg("pin_enc") ? webServer.arg("pin_enc") : "";
    if (!authorizeCall("create", acctproto::boundWithPin(user.c_str(), enc.c_str()).c_str(), c)) return;

    uint32_t now = millis();
    if (lastCreateMs != 0 && (uint32_t)(now - lastCreateMs) < CREATE_MIN_GAP_MS) {
        answer(429, false, "TOO_FAST", nullptr);
        return;
    }
    std::string pin;
    if (!acctproto::openPin(enc.c_str(), getSharedSecret().c_str(), pin)) {
        answer(400, false, "BAD_PIN_FORMAT", nullptr);
        return;
    }
    lastCreateMs = now ? now : 1;
    Result r = accountsCreate(user, String(pin.c_str()));
    if (r == Result::OK) diagLog("[ACCT] account '%s' created from slot %d\n", user.c_str(), c.slotNum);
    answer(httpCodeFor(r), r == Result::OK, errorName(r),
           r == Result::OK ? accountsTable().find(user.c_str()) : nullptr);
}

void handleApiAccountSignin() {
    Caller c;
    String user = userArg();
    String enc = webServer.hasArg("pin_enc") ? webServer.arg("pin_enc") : "";
    if (!authorizeCall("signin", acctproto::boundWithPin(user.c_str(), enc.c_str()).c_str(), c)) return;

    std::string pin;
    if (!acctproto::openPin(enc.c_str(), getSharedSecret().c_str(), pin)) {
        answer(400, false, "BAD_PIN_FORMAT", nullptr);
        return;
    }
    accounts::AccountTable& t = accountsTable();
    uint32_t nowS = accountsNowS();
    Result r = t.verifyPin(user.c_str(), pin, nowS);
    if (r != Result::OK) {
        accountsCommit(false); // wrong-PIN counts must reach flash too, or a power cut resets the lockout
        answer(httpCodeFor(r), false, errorName(r), nullptr);
        return;
    }
    r = t.signIn(user.c_str(), (uint8_t)c.slotNum, nowS);
    if (r == Result::OK) {
        lastReportMs[c.slotNum] = millis();
        accountsCommit(true);
        diagLog("[ACCT] '%s' signed in on slot %d\n", user.c_str(), c.slotNum);
    }
    answer(httpCodeFor(r), r == Result::OK, errorName(r), r == Result::OK ? t.find(user.c_str()) : nullptr);
}

void handleApiAccountSignout() {
    Caller c;
    String user = userArg();
    String left = webServer.hasArg("time") ? webServer.arg("time") : "";
    left.trim();
    if (!authorizeCall("signout", user + ":" + left, c)) return;

    uint32_t remaining = 0;
    if (!acctproto::parseSeconds(left.c_str(), remaining)) {
        answer(400, false, "BAD_TIME", nullptr);
        return;
    }
    accounts::AccountTable& t = accountsTable();
    uint32_t nowS = accountsNowS();
    // Final report first (the balance can only go down to what the phone still has), then sign out.
    Result r = t.reportRemaining(user.c_str(), (uint8_t)c.slotNum, remaining, nowS);
    if (r == Result::OK) r = t.signOut(user.c_str(), nowS);
    if (r == Result::OK) {
        accountsCommit(true);
        diagLog("[ACCT] '%s' signed out from slot %d\n", user.c_str(), c.slotNum);
    }
    answer(httpCodeFor(r), r == Result::OK, errorName(r), r == Result::OK ? t.find(user.c_str()) : nullptr);
}

void handleApiAccountInfo() {
    Caller c;
    String user = userArg();
    if (!authorizeCall("info", user, c)) return;

    // Only the phone the player is signed in on may read the balance, so nobody can look up other players.
    const accounts::Account* a = accountsTable().find(user.c_str());
    if (!a || a->signedInSlot != c.slotNum) {
        answer(403, false, "NOT_SIGNED_IN", nullptr);
        return;
    }
    answer(200, true, "", a);
}

// A signed report rides on the heartbeat: acct, atime and asig = HMAC("v1:acct_report:<dev>:<ts>:<acct>:<atime>").
// The heartbeat's own signature does not cover these fields, so they are only believed with their own.
void accountsOnHeartbeat(int slotNum, const String& deviceId, const String& tsStr, String& jsonReply) {
    if (!accountsReady() || slotNum <= 0 || slotNum > MAX_SUPPORTED_SLOTS) return;
    accounts::AccountTable& t = accountsTable();
    uint32_t nowS = accountsNowS();

    if (webServer.hasArg("acct") && webServer.hasArg("atime") && webServer.hasArg("asig")) {
        String acct = webServer.arg("acct");
        acct.trim();
        acct.toLowerCase();
        String atime = webServer.arg("atime");
        atime.trim();
        std::string bound = std::string(acct.c_str()) + ":" + atime.c_str();
        String want = String(
            acctproto::signature(getSharedSecret().c_str(), "report", deviceId.c_str(), tsStr.c_str(), bound).c_str());
        uint32_t remaining = 0;
        if (webServer.arg("asig").equalsIgnoreCase(want) && acctproto::parseSeconds(atime.c_str(), remaining)) {
            if (t.reportRemaining(acct.c_str(), (uint8_t)slotNum, remaining, nowS) == Result::OK) {
                lastReportMs[slotNum] = millis();
                accountsCommit(false);
            }
        }
    }

    const accounts::Account* a = t.findBySlot((uint8_t)slotNum);
    if (a) {
        jsonReply += String(",\"acct\":\"") + a->username + "\",\"acct_bal\":" + String(a->balanceSec);
    } else {
        jsonReply += ",\"acct\":\"\"";
    }
}

void accountsOnPhonePaymentAcked(const String& deviceId, const String& txId, int creditSeconds) {
    if (!accountsReady() || creditSeconds <= 0) return;
    int idx = findSlotIndexForDevice(deviceId, "");
    if (idx < 0) return;
    accounts::AccountTable& t = accountsTable();
    const accounts::Account* a = t.findBySlot((uint8_t)phoneSlots[idx].slotNum);
    if (!a) return;
    std::string user = a->username;
    // The tx id makes this count once however often the phone repeats its acknowledgement.
    Result r = t.creditSeconds(user, (uint32_t)creditSeconds, std::string("pay:") + txId.c_str(), accountsNowS());
    if (r == Result::OK) {
        accountsCommit(true);
        diagLog("[ACCT] '%s' credited %d s\n", user.c_str(), creditSeconds);
    }
}

void accountsWatchdogLoop() {
    if (!accountsReady()) return;
    static uint32_t lastCheckMs = 0;
    uint32_t now = millis();
    if ((uint32_t)(now - lastCheckMs) < 5000UL) return;
    lastCheckMs = now;
    accounts::AccountTable& t = accountsTable();
    for (int slot = 1; slot <= MAX_SUPPORTED_SLOTS; slot++) {
        if (!t.findBySlot((uint8_t)slot)) continue;
        if ((uint32_t)(now - lastReportMs[slot]) >= SILENT_PHONE_MS) {
            // A phone that went quiet (switched off, out of range): its player keeps the time last reported.
            t.signOutSlot((uint8_t)slot, accountsNowS());
            accountsCommit(true);
            diagLog("[ACCT] slot %d stopped reporting; its player was signed out\n", slot);
        }
    }
}
