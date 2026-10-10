// Revenue vault glue: connects RevenueLedger.h (pure logic) to flash and to the box's counters.
// The log lives in its own NVS namespace so an operator's factory reset, which clears the settings, never touches it.

#include "RevenueVault.h"
#include "Config.h"
#include "Diagnostics.h"
#include "Money.h"
#include "RevenueLedger.h"

#include <Preferences.h>

static const char* const LEDGER_NAMESPACE = "rev_ledger";
static revenue::Ledger ledger;

// Opens the log's namespace for the duration of one operation.
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

static uint32_t nowSeconds() {
    uint64_t ms = getCurrentMasterTimeMs();
    uint64_t s = ms / 1000ULL;
    return s > UINT32_MAX ? 0 : (uint32_t)s; // 0 = the box did not know the time
}

uint32_t vaultCoins() {
    return ledger.vaultCoins(totalCoinsLifetime);
}

uint32_t vaultCentavos() {
    return ledger.vaultCentavos(totalCentavosLifetime);
}

uint32_t vaultCollections() {
    return ledger.totalCollections();
}

bool vaultCollect(uint32_t reason) {
    // The record states the lifetime totals, so they must be in flash first: a power cut can then never leave a record that
    // is ahead of the counters it describes.
    flushRevenueNow();

    revenue::Collection w;
    LedgerStore store;
    if (!ledger.collect(store, totalCoinsLifetime, totalCentavosLifetime, nowSeconds(), reason, &w)) {
        diagLog("[💰 VAULT] Could not record the collection (flash write failed): nothing was changed.\n");
        return false;
    }
    if (w.seq == 0) return true; // the vault was empty: nothing to record

    // "this session" is a view of the vault since boot; it starts again with the vault. The lifetime counters are untouched.
    totalCoinsSession = 0;
    totalCentavosSession = 0;

    char taken[24];
    money::formatPesos(w.centavos, taken, sizeof(taken));
    diagLog("[💰 VAULT] Collection #%u (%s): %u coin(s), P%s taken; lifetime total stays at %u coin(s).\n",
            (unsigned)w.seq, revenue::reasonName(w.reason), (unsigned)w.coins, taken, (unsigned)w.cumCoins);
    return true;
}

void vaultBegin() {
    {
        LedgerStore store;
        ledger.load(store);
    }
    if (ledger.needsRebase(totalCoinsLifetime, totalCentavosLifetime)) {
        // The counters are lower than the last collection saw (lost or replaced flash): without this the next coins would
        // stay hidden until they passed the old total.
        diagLog("[💰 VAULT] WARNING: the lifetime counters are below the last collection; moving the baseline down.\n");
        vaultCollect(revenue::REBASE);
    }
    diagLog("[💰 VAULT] %u collection(s) on record; %u coin(s) in the vault.\n", (unsigned)ledger.totalCollections(),
            (unsigned)vaultCoins());
}

String vaultHistoryJson(int max) {
    revenue::Collection list[revenue::RING_SIZE];
    if (max > revenue::RING_SIZE) max = revenue::RING_SIZE;
    int n = ledger.recent(list, max);
    String json = "[";
    for (int i = 0; i < n; i++) {
        char taken[24], total[24];
        money::formatPesos(list[i].centavos, taken, sizeof(taken));
        money::formatPesos(list[i].cumCentavos, total, sizeof(total));
        if (i) json += ",";
        json += "{\"seq\":" + String(list[i].seq) + ",\"time_s\":" + String(list[i].timeS) +
                ",\"coins\":" + String(list[i].coins) + ",\"earnings\":" + taken +
                ",\"lifetime_coins\":" + String(list[i].cumCoins) + ",\"lifetime_earnings\":" + total +
                ",\"reason\":\"" + revenue::reasonName(list[i].reason) + "\"}";
    }
    json += "]";
    return json;
}

void vaultOnOwnerWipe() {
    Preferences p;
    if (p.begin(LEDGER_NAMESPACE, false)) {
        p.clear();
        p.end();
    }
    ledger.clear();
}
