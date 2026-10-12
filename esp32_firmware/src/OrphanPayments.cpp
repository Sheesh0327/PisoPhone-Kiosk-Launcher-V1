// Orphaned payments: glue between OrphanLedger.h (pure logic) and flash, the clock and the dashboard.
// The ledger has its own flash namespace, so an operator's factory reset (which clears the settings) never touches it.

#include "OrphanPayments.h"
#include "Config.h"
#include "Diagnostics.h"
#include "InputSafety.h"
#include "Money.h"
#include "OrphanLedger.h"

#include <Preferences.h>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>

static const char* const LEDGER_NAMESPACE = "orphans";
static orphans::Ledger ledger;
static StaticSemaphore_t ledgerMutexBuf;
static SemaphoreHandle_t ledgerMutex = xSemaphoreCreateMutexStatic(&ledgerMutexBuf);

// Opens the namespace for the duration of one operation. (In an anonymous namespace, like the same-named class in
// RevenueVault.cpp: two different definitions of one name with external linkage would be merged by the linker.)
namespace {
struct LedgerStore {
    Preferences p;
    bool open;
    LedgerStore() : open(p.begin(LEDGER_NAMESPACE, false)) {}
    ~LedgerStore() {
        if (open) p.end();
    }
    size_t getBytesLength(const char* k) { return open ? p.getBytesLength(k) : 0; }
    size_t getBytes(const char* k, void* b, size_t n) { return open ? p.getBytes(k, b, n) : 0; }
    size_t putBytes(const char* k, const void* b, size_t n) { return open ? p.putBytes(k, b, n) : 0; }
};

struct Lock {
    Lock() { xSemaphoreTake(ledgerMutex, portMAX_DELAY); }
    ~Lock() { xSemaphoreGive(ledgerMutex); }
};
} // namespace

static uint32_t nowSeconds() {
    uint64_t s = getCurrentMasterTimeMs() / 1000ULL;
    return s > UINT32_MAX ? 0 : (uint32_t)s; // 0 = the box did not know the time
}

void orphansBegin() {
    Lock lock;
    LedgerStore store;
    ledger.load(store);
    diagLog("[ORPHANS] %u payment(s) on record, %u waiting for the operator (P%u).\n", (unsigned)ledger.totalRecorded(),
            (unsigned)ledger.unhandledCount(), (unsigned)ledger.unhandledPulses());
}

bool orphanRecord(const char* txId, const char* target, uint8_t owner, uint32_t pulses, uint32_t creditSeconds,
                  uint32_t ageS, uint8_t reason) {
    orphans::Entry e;
    orphans::copyText(e.txId, sizeof(e.txId), txId);
    orphans::copyText(e.target, sizeof(e.target), target);
    e.owner = owner;
    e.pulses = pulses;
    e.creditSeconds = creditSeconds;
    e.ageS = ageS;
    e.reason = reason;
    e.timeS = nowSeconds();

    Lock lock;
    LedgerStore store;
    uint32_t seq = 0;
    if (!ledger.add(store, e, &seq)) {
        diagCount(DiagCounter::OrphanWriteFailures);
        diagLog("[ORPHANS] CANNOT RECORD tx_id='%s' (P%u, %s): flash write failed; the payment is kept where it is.\n",
                e.txId, (unsigned)pulses, orphans::reasonName(reason));
        return false;
    }
    diagCount(DiagCounter::PaymentsOrphaned);
    diagLog("[ORPHANS] Recorded #%u: tx_id='%s' for '%s', P%u, %s after %u s. Waiting for the operator (%u in all).\n",
            (unsigned)seq, e.txId, e.target, (unsigned)pulses, orphans::reasonName(reason), (unsigned)ageS,
            (unsigned)ledger.unhandledCount());
    return true;
}

uint32_t orphansUnhandledCount() {
    Lock lock;
    return ledger.unhandledCount();
}

uint32_t orphansUnhandledPulses() {
    Lock lock;
    return ledger.unhandledPulses();
}

bool orphansHandle(uint32_t seq) {
    Lock lock;
    LedgerStore store;
    bool ok = ledger.handle(store, seq);
    if (ok) diagLog("[ORPHANS] #%u marked handled by the operator.\n", (unsigned)seq);
    return ok;
}

bool orphansHandleAll() {
    Lock lock;
    LedgerStore store;
    bool ok = ledger.handleAll(store);
    if (ok) diagLog("[ORPHANS] All payments marked handled by the operator.\n");
    return ok;
}

String orphansJson() {
    orphans::Entry list[orphans::RING_SIZE];
    Lock lock;
    int n = ledger.recent(list, orphans::RING_SIZE);
    String json = "{\"unhandled\":" + String(ledger.unhandledCount()) +
                  ",\"unhandled_pulses\":" + String(ledger.unhandledPulses()) +
                  ",\"unlisted\":" + String(ledger.unlistedCount()) +
                  ",\"unlisted_pulses\":" + String(ledger.unlistedPulses()) +
                  ",\"total\":" + String(ledger.totalRecorded()) + ",\"entries\":[";
    for (int i = 0; i < n; i++) {
        if (i) json += ",";
        json += "{\"seq\":" + String(list[i].seq) + ",\"time_s\":" + String(list[i].timeS) +
                ",\"pulses\":" + String(list[i].pulses) + ",\"credit_seconds\":" + String(list[i].creditSeconds) +
                ",\"age_s\":" + String(list[i].ageS) + ",\"owner\":" + String(list[i].owner) + ",\"reason\":\"" +
                orphans::reasonName(list[i].reason) + "\",\"handled\":" + (list[i].handled ? "true" : "false") +
                ",\"tx_id\":\"" + jsonEsc(list[i].txId) + "\",\"target\":\"" + jsonEsc(list[i].target) + "\"}";
    }
    json += "]}";
    return json;
}

void orphansOnOwnerWipe() {
    Lock lock;
    Preferences p;
    if (p.begin(LEDGER_NAMESPACE, false)) {
        p.clear();
        p.end();
    }
    ledger.clear();
}
