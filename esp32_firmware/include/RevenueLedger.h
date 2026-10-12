#ifndef REVENUE_LEDGER_H
#define REVENUE_LEDGER_H

// The coin box's revenue counters only ever go up. Taking the money out of the box is not a reset, it is an event: a
// "collection" is written to a small log (the last RING_SIZE collections, one flash record each) with the time, the amounts
// taken and the lifetime totals at that moment. What the dashboard and the vendor page call the vault is
//     lifetime total - lifetime total at the last collection
// so the vendor sees the same "back to zero" as before, but nothing is ever erased and every collection can be audited.
//
// One collection is ONE flash write (a single self-checking record), so a power cut leaves either the old or the new state,
// never a half-collected one: the baseline is not stored on its own, it is read from the newest valid record.
//
// Pure C++ (no Arduino), tested on the host (host_tests/revenue_ledger_test.cpp).
// `Store` is any class with: size_t getBytesLength(const char*), size_t getBytes(const char*, void*, size_t),
// size_t putBytes(const char*, const void*, size_t).

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

namespace revenue {

static const int RING_SIZE = 16;
static const uint32_t RECORD_MAGIC = 0x314C5652UL; // "RVL1"
static const size_t RECORD_WORDS = 9;
static const size_t RECORD_BYTES = RECORD_WORDS * 4;

// Why the money was collected (stored in the record).
enum Reason : uint32_t {
    UNMASK_EXPIRED = 1, // the vendor's 5-minute window ended
    SUPERADMIN_LOGOUT = 2,
    SUPERADMIN_RESET = 3,      // "finish collecting" on the vendor page
    VENDOR_PASSWORD_RESET = 4, // the dashboard's reset form, with the vendor password
    REBASE = 5,                // bookkeeping only: the counters were found below the last baseline, nothing collected
};

inline const char* reasonName(uint32_t r) {
    switch (r) {
    case UNMASK_EXPIRED:
        return "window-ended";
    case SUPERADMIN_LOGOUT:
        return "vendor-logout";
    case SUPERADMIN_RESET:
        return "vendor-finished";
    case VENDOR_PASSWORD_RESET:
        return "dashboard-reset";
    case REBASE:
        return "rebase";
    }
    return "unknown";
}

struct Collection {
    uint32_t seq = 0;         // 1, 2, 3 ... never reused
    uint32_t timeS = 0;       // seconds since 1970 from the box's master clock, 0 when it did not know the time
    uint32_t coins = 0;       // collected this time
    uint32_t centavos = 0;    // collected this time
    uint32_t cumCoins = 0;    // lifetime total when it happened (= the baseline after it)
    uint32_t cumCentavos = 0; // lifetime total when it happened
    uint32_t reason = 0;
};

inline uint32_t fnv1a(const uint8_t* d, size_t n) {
    uint32_t h = 2166136261UL;
    for (size_t i = 0; i < n; i++) {
        h ^= d[i];
        h *= 16777619UL;
    }
    return h;
}

inline void putWord(uint8_t* p, uint32_t v) {
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16);
    p[3] = (uint8_t)(v >> 24);
}

inline uint32_t getWord(const uint8_t* p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

inline void encode(const Collection& c, uint8_t out[RECORD_BYTES]) {
    putWord(out + 0, RECORD_MAGIC);
    putWord(out + 4, c.seq);
    putWord(out + 8, c.timeS);
    putWord(out + 12, c.coins);
    putWord(out + 16, c.centavos);
    putWord(out + 20, c.cumCoins);
    putWord(out + 24, c.cumCentavos);
    putWord(out + 28, c.reason);
    putWord(out + 32, fnv1a(out, RECORD_BYTES - 4));
}

inline bool decode(const uint8_t in[RECORD_BYTES], Collection& c) {
    if (getWord(in + 0) != RECORD_MAGIC) return false;
    if (getWord(in + 32) != fnv1a(in, RECORD_BYTES - 4)) return false;
    c.seq = getWord(in + 4);
    c.timeS = getWord(in + 8);
    c.coins = getWord(in + 12);
    c.centavos = getWord(in + 16);
    c.cumCoins = getWord(in + 20);
    c.cumCentavos = getWord(in + 24);
    c.reason = getWord(in + 28);
    return c.seq != 0;
}

inline void slotKey(int slot, char* buf, size_t len) {
    snprintf(buf, len, "col_%02d", slot);
}

inline uint32_t subtractClamped(uint32_t total, uint32_t baseline) {
    return total > baseline ? total - baseline : 0;
}

class Ledger {
public:
    // Reads every record; the newest valid one gives the baseline. A damaged record is skipped, not trusted.
    template <class Store> void load(Store& s) {
        clear();
        for (int slot = 0; slot < RING_SIZE; slot++) {
            char key[8];
            slotKey(slot, key, sizeof(key));
            uint8_t raw[RECORD_BYTES];
            if (s.getBytesLength(key) != RECORD_BYTES) continue;
            if (s.getBytes(key, raw, RECORD_BYTES) != RECORD_BYTES) continue;
            Collection c;
            if (!decode(raw, c)) continue;
            recs_[slot] = c;
            used_[slot] = true;
            if (c.seq > latest_.seq) latest_ = c;
        }
    }

    void clear() {
        for (int i = 0; i < RING_SIZE; i++)
            used_[i] = false;
        latest_ = Collection();
    }

    // Lifetime total at the last collection (0 when nothing was ever collected).
    uint32_t baselineCoins() const { return latest_.cumCoins; }
    uint32_t baselineCentavos() const { return latest_.cumCentavos; }

    // What is in the vault now: the lifetime total less the last collection's total (never negative).
    uint32_t vaultCoins(uint32_t lifetimeCoins) const { return subtractClamped(lifetimeCoins, latest_.cumCoins); }
    uint32_t vaultCentavos(uint32_t lifetimeCentavos) const {
        return subtractClamped(lifetimeCentavos, latest_.cumCentavos);
    }

    // True when the lifetime counters are below the last baseline (they were lost or replaced): coins counted from now on
    // would stay hidden until they passed it. collect() then writes a REBASE record that moves the baseline down.
    bool needsRebase(uint32_t lifetimeCoins, uint32_t lifetimeCentavos) const {
        return lifetimeCoins < latest_.cumCoins || lifetimeCentavos < latest_.cumCentavos;
    }

    uint32_t totalCollections() const { return latest_.seq; }

    // Takes the vault's contents out of the counters with ONE flash write. Returns false (and changes nothing) when the
    // write failed or the log is full of numbers; true when it was written or when there was nothing to collect.
    template <class Store>
    bool collect(Store& s, uint32_t lifetimeCoins, uint32_t lifetimeCentavos, uint32_t timeS, uint32_t reason,
                 Collection* written = nullptr) {
        const bool rebase = needsRebase(lifetimeCoins, lifetimeCentavos);
        Collection c;
        c.coins = rebase ? 0 : vaultCoins(lifetimeCoins);
        c.centavos = rebase ? 0 : vaultCentavos(lifetimeCentavos);
        if (!rebase && c.coins == 0 && c.centavos == 0) return true; // empty vault: no record, no flash wear
        if (latest_.seq == UINT32_MAX) return false;
        c.seq = latest_.seq + 1;
        c.timeS = timeS;
        c.cumCoins = lifetimeCoins;
        c.cumCentavos = lifetimeCentavos;
        c.reason = rebase ? (uint32_t)REBASE : reason;

        const int slot = (int)((c.seq - 1) % RING_SIZE);
        char key[8];
        slotKey(slot, key, sizeof(key));
        uint8_t raw[RECORD_BYTES];
        encode(c, raw);
        if (s.putBytes(key, raw, RECORD_BYTES) != RECORD_BYTES) return false;

        recs_[slot] = c;
        used_[slot] = true;
        latest_ = c;
        if (written) *written = c;
        return true;
    }

    // Newest first. Returns how many were copied (at most `max`).
    int recent(Collection* out, int max) const {
        int n = 0;
        uint32_t next = latest_.seq;
        for (int i = 0; i < RING_SIZE && n < max && next > 0; i++, next--) { // one pass over the ring, gaps allowed
            const int slot = (int)((next - 1) % RING_SIZE);
            if (used_[slot] && recs_[slot].seq == next) out[n++] = recs_[slot];
        }
        return n;
    }

private:
    Collection recs_[RING_SIZE];
    bool used_[RING_SIZE] = {false};
    Collection latest_;
};

} // namespace revenue

#endif // REVENUE_LEDGER_H
