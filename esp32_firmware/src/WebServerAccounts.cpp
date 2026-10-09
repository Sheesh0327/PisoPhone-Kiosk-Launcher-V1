// Player-account HTTP API (phone -> box). A player's account is a QR card: the phone scans it and sends the card text, the box
// checks the card's signature (CardCodec.h), and the card's serial number is the account id. The account table lives in
// AccountStorage; this file only checks who is asking, turns requests into table changes and answers.
// The message format is AccountProtocol.h.

#include "WebServerAccounts.h"

#include "AccountProtocol.h"
#include "AccountStorage.h"
#include "CardCodec.h"
#include "CardPubKey.h"
#include "Config.h"
#include "DeviceManager.h"
#include "Diagnostics.h"
#include "InputSafety.h"
#include "Security.h"
#include "SetupGate.h"
#include "WebServerAuth.h"
#include "WebServerCoinslot.h"
#include "WebServerModule.h"

#include <WebServer.h>

using accounts::Result;

static const uint32_t SILENT_PHONE_MS = 90000UL;

static uint32_t lastReportMs[MAX_SUPPORTED_SLOTS + 1]; // when each slot's phone last reported (index = slot number)
// Checking a card's signature takes a noticeable fraction of a second on the box. A shop never needs more than a few scans a
// minute; beyond that the box answers "too fast" instead of letting the network keep the CPU busy (the coin loop shares it).
static accounts::CostLimiter scanLimiter(12, 60000UL);

static const char* errorName(Result r) {
    switch (r) {
    case Result::OK:
    case Result::DUPLICATE_TX:
        return "";
    case Result::BAD_CARD:
        return "BAD_CARD";
    case Result::BAD_NAME:
        return "BAD_NAME";
    case Result::ACCOUNTS_FULL:
        return "ACCOUNTS_FULL";
    case Result::NO_SUCH_ACCOUNT:
        return "NO_SUCH_ACCOUNT";
    case Result::ALREADY_SIGNED_IN:
        return "ALREADY_SIGNED_IN";
    case Result::NOT_SIGNED_IN:
        return "NOT_SIGNED_IN";
    default:
        return "INTERNAL";
    }
}

static int httpCodeFor(Result r) {
    switch (r) {
    case Result::OK:
    case Result::DUPLICATE_TX:
        return 200;
    case Result::NO_SUCH_ACCOUNT:
        return 404;
    case Result::ALREADY_SIGNED_IN:
        return 409;
    case Result::ACCOUNTS_FULL:
        return 507;
    case Result::INTERNAL:
        return 500;
    default:
        return 400;
    }
}

// `bonusSec` is the starter time just given by this call (0 when none).
static void answer(int code, bool ok, const char* error, const accounts::Account* a, uint32_t bonusSec = 0) {
    String json = String("{\"success\":") + (ok ? "true" : "false");
    if (error && error[0]) json += String(",\"error\":\"") + error + "\"";
    if (a) {
        // The name only ever holds letters, digits, space, '_' and '-' (Accounts.h), so it needs no JSON escaping.
        json += String(",\"id\":") + String(a->id) + ",\"name\":\"" + a->name +
                "\",\"balance_sec\":" + String(a->balanceSec) + ",\"bonus_sec\":" + String(bonusSec);
    }
    json += boxTimeJsonField() + "}";
    webServer.send(code, "application/json", json);
}

// What every account call needs before it does anything: a paired, active phone and a correct signature over the call's
// parameters. On failure the reply has been sent.
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
    // The box takes its clock from the phones' heartbeats. Without it, pruning has no time to count from (and the replay window
    // accepts any timestamp), so account calls wait for it: a few seconds after a restart.
    if (accountsNowS() == 0) {
        answer(503, false, "CLOCK_UNKNOWN", nullptr);
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

// The account number a call is about; answers 400 and returns false when it is missing or not a card number.
static bool idArg(uint32_t& id, String& raw) {
    raw = webServer.hasArg("id") ? webServer.arg("id") : "";
    raw.trim();
    if (!acctproto::parseId(raw.c_str(), id)) {
        answer(400, false, "BAD_CARD", nullptr);
        return false;
    }
    return true;
}

void handleApiAccountScan() {
    Caller c;
    String text = webServer.hasArg("card") ? webServer.arg("card") : "";
    text.trim();
    if (!authorizeCall("scan", text, c)) return;
    if (!scanLimiter.allow(millis())) {
        answer(429, false, "TOO_FAST", nullptr);
        return;
    }

    card::Card card;
    card::Check check = card::verify(text.c_str(), macAddressStr.c_str(), CARD_PUBKEY_DER, CARD_PUBKEY_LEN, card);
    if (check != card::Check::OK) {
        const char* why = check == card::Check::CARDS_OFF
                              ? "CARDS_OFF"
                              : (check == card::Check::WRONG_BOX ? "WRONG_BOX" : "BAD_CARD");
        diagLog("[ACCT] scan refused from slot %d: %s\n", c.slotNum, why);
        answer(check == card::Check::CARDS_OFF ? 503 : (check == card::Check::WRONG_BOX ? 403 : 400), false, why,
               nullptr);
        return;
    }

    accounts::AccountTable& t = accountsTable();
    uint32_t nowS = accountsNowS();
    bool first = false;
    Result r = t.redeemCard(card.serial, card.seconds, nowS, first);
    if (r == Result::OK) r = t.signIn(card.serial, (uint8_t)c.slotNum, nowS);
    if (r != Result::OK) {
        answer(httpCodeFor(r), false, errorName(r), nullptr);
        return;
    }
    lastReportMs[c.slotNum] = millis();
    accountsCommit(true); // the starter time and the "this card is used" mark reach flash before the phone is told
    diagLog("[ACCT] card %u signed in on slot %d%s\n", (unsigned)card.serial, c.slotNum,
            first ? " (first scan: starter time given)" : "");
    answer(200, true, "", t.find(card.serial), first ? card.seconds : 0);
}

void handleApiAccountName() {
    Caller c;
    uint32_t id = 0;
    String rawId;
    String name = webServer.hasArg("name") ? webServer.arg("name") : "";
    name.trim();
    rawId = webServer.hasArg("id") ? webServer.arg("id") : "";
    rawId.trim();
    if (!authorizeCall("name", rawId + ":" + name, c)) return;
    if (!idArg(id, rawId)) return;

    // Only the phone the player is signed in on may rename the account.
    const accounts::Account* a = accountsTable().find(id);
    if (!a || a->signedInSlot != c.slotNum) {
        answer(403, false, "NOT_SIGNED_IN", nullptr);
        return;
    }
    Result r = accountsTable().setName(id, name.c_str());
    if (r == Result::OK) accountsCommit(true);
    answer(httpCodeFor(r), r == Result::OK, errorName(r), r == Result::OK ? accountsTable().find(id) : nullptr);
}

void handleApiAccountSignout() {
    Caller c;
    uint32_t id = 0;
    String rawId = webServer.hasArg("id") ? webServer.arg("id") : "";
    rawId.trim();
    String left = webServer.hasArg("time") ? webServer.arg("time") : "";
    left.trim();
    if (!authorizeCall("signout", rawId + ":" + left, c)) return;
    if (!idArg(id, rawId)) return;

    uint32_t remaining = 0;
    if (!acctproto::parseSeconds(left.c_str(), remaining)) {
        answer(400, false, "BAD_TIME", nullptr);
        return;
    }
    accounts::AccountTable& t = accountsTable();
    uint32_t nowS = accountsNowS();
    // Final report first (the balance can only go down to what the phone still has), then sign out.
    Result r = t.reportRemaining(id, (uint8_t)c.slotNum, remaining, nowS);
    if (r == Result::OK) r = t.signOut(id, nowS);
    if (r == Result::OK) {
        accountsCommit(true);
        diagLog("[ACCT] card %u signed out from slot %d\n", (unsigned)id, c.slotNum);
    }
    answer(httpCodeFor(r), r == Result::OK, errorName(r), r == Result::OK ? t.find(id) : nullptr);
}

void handleApiAccountInfo() {
    Caller c;
    uint32_t id = 0;
    String rawId = webServer.hasArg("id") ? webServer.arg("id") : "";
    rawId.trim();
    if (!authorizeCall("info", rawId, c)) return;
    if (!idArg(id, rawId)) return;

    // Only the phone the player is signed in on may read the balance.
    const accounts::Account* a = accountsTable().find(id);
    if (!a || a->signedInSlot != c.slotNum) {
        answer(403, false, "NOT_SIGNED_IN", nullptr);
        return;
    }
    answer(200, true, "", a);
}

// ---- admin dashboard ----

static void adminAnswer(int code, bool ok, const char* error) {
    String json = String("{\"success\":") + (ok ? "true" : "false");
    if (error && error[0]) json += String(",\"error\":\"") + error + "\"";
    webServer.send(code, "application/json", json + "}");
}

void handleApiAccountsList() {
    if (!checkAdminAuth()) return;
    if (!accountsReady()) {
        adminAnswer(503, false, "ACCOUNTS_OFF");
        return;
    }
    accounts::AccountTable& t = accountsTable();
    uint32_t nowS = accountsNowS();
    AccountStorageHealth health = accountsHealth();
    bool cardsReady = CARD_PUBKEY_LEN > 1;
    // Streamed one row at a time: hundreds of accounts as one String would need tens of KB of contiguous heap.
    webServer.setContentLength(CONTENT_LENGTH_UNKNOWN);
    webServer.send(200, "application/json", "");
    webServer.sendContent(String("{\"max\":") + String((unsigned)accounts::MAX_ACCOUNTS) +
                          ",\"failing\":" + (health.consecutiveFailures > 0 ? "true" : "false") +
                          ",\"cards_ready\":" + (cardsReady ? "true" : "false") + ",\"box_id\":\"" +
                          String(card::normalizeBox(macAddressStr.c_str()).c_str()) + "\",\"accounts\":[");
    for (size_t i = 0; i < t.count(); i++) {
        const accounts::Account& a = t.at(i);
        // -1 while the box has no clock yet
        long idleMin = (nowS == 0) ? -1L : (long)(accounts::idleFor(nowS, a.lastActiveS) / 60);
        String row = String(i ? "," : "") + "{\"id\":" + String(a.id) + ",\"name\":\"" + a.name +
                     "\",\"balance_sec\":" + String(a.balanceSec) + ",\"slot\":" + String((int)a.signedInSlot) +
                     ",\"idle_min\":" + String(idleMin) + "}";
        webServer.sendContent(row);
    }
    webServer.sendContent("]}");
    webServer.sendContent("");
}

void handleApiAccountsAdjust() {
    if (!checkAdminAuth()) return;
    if (!accountsReady()) return adminAnswer(503, false, "ACCOUNTS_OFF");
    uint32_t id = 0;
    String rawId;
    if (!idArg(id, rawId)) return;
    String m = webServer.hasArg("minutes") ? webServer.arg("minutes") : "";
    m.trim();
    long minutes = m.toInt();
    if (minutes == 0 || minutes > 1440L * 30 || minutes < -1440L * 30) return adminAnswer(400, false, "BAD_MINUTES");
    Result r = accountsTable().adjustSeconds(id, (int64_t)minutes * 60, accountsNowS());
    if (r == Result::OK) {
        accountsCommit(true);
        diagLog("[ACCT] admin changed card %u by %ld min\n", (unsigned)id, minutes);
    }
    adminAnswer(httpCodeFor(r), r == Result::OK, errorName(r));
}

void handleApiAccountsDelete() {
    if (!checkAdminAuth()) return;
    if (!accountsReady()) return adminAnswer(503, false, "ACCOUNTS_OFF");
    uint32_t id = 0;
    String rawId;
    if (!idArg(id, rawId)) return;
    accounts::AccountTable& t = accountsTable();
    const accounts::Account* a = t.find(id);
    if (a && a->signedInSlot != 0) return adminAnswer(409, false, "ALREADY_SIGNED_IN");
    Result r = t.deleteAccount(id);
    if (r == Result::OK) {
        accountsCommit(true);
        diagLog("[ACCT] admin deleted card %u\n", (unsigned)id);
    }
    adminAnswer(httpCodeFor(r), r == Result::OK, errorName(r));
}

// A signed report rides on the heartbeat: acct (the card number), atime and asig = HMAC("v1:acct_report:<dev>:<ts>:<id>:<atime>").
// The heartbeat's own signature does not cover these fields, so they are only believed with their own.
void accountsOnHeartbeat(int slotNum, const String& deviceId, const String& tsStr, String& jsonReply) {
    if (!accountsReady() || slotNum <= 0 || slotNum > MAX_SUPPORTED_SLOTS) return;
    accounts::AccountTable& t = accountsTable();
    uint32_t nowS = accountsNowS();

    if (webServer.hasArg("acct") && webServer.hasArg("atime") && webServer.hasArg("asig")) {
        String acct = webServer.arg("acct");
        acct.trim();
        String atime = webServer.arg("atime");
        atime.trim();
        std::string bound = std::string(acct.c_str()) + ":" + atime.c_str();
        String want = String(
            acctproto::signature(getSharedSecret().c_str(), "report", deviceId.c_str(), tsStr.c_str(), bound).c_str());
        uint32_t id = 0, remaining = 0;
        if (webServer.arg("asig").equalsIgnoreCase(want) && acctproto::parseId(acct.c_str(), id) &&
            acctproto::parseSeconds(atime.c_str(), remaining)) {
            if (t.reportRemaining(id, (uint8_t)slotNum, remaining, nowS) == Result::OK) {
                lastReportMs[slotNum] = millis();
                accountsCommit(false);
            }
        }
    }

    const accounts::Account* a = t.findBySlot((uint8_t)slotNum);
    if (a) {
        jsonReply += String(",\"acct\":\"") + String(a->id) + "\",\"acct_name\":\"" + a->name +
                     "\",\"acct_bal\":" + String(a->balanceSec);
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
    uint32_t id = a->id;
    // The tx id makes this count once however often the phone repeats its acknowledgement.
    Result r = t.creditSeconds(id, (uint32_t)creditSeconds, std::string("pay:") + txId.c_str(), accountsNowS());
    if (r == Result::OK) {
        accountsCommit(true);
        diagLog("[ACCT] card %u credited %d s\n", (unsigned)id, creditSeconds);
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
