#include "../include/RevenueLedger.h"

#include <cstdio>
#include <map>
#include <string>
#include <vector>

static int failures = 0, checks = 0;
#define CHECK(c)                                                                                                       \
    do {                                                                                                               \
        checks++;                                                                                                      \
        if (!(c)) {                                                                                                    \
            failures++;                                                                                                \
            printf("FAIL line %d: %s\n", __LINE__, #c);                                                                \
        }                                                                                                              \
    } while (0)

using namespace revenue;

// A flash stand-in that can be told to refuse writes (a full or worn NVS).
struct BlobStore {
    std::map<std::string, std::vector<uint8_t>> blobs;
    bool failWrites = false;
    int writes = 0;
    size_t getBytesLength(const char* k) {
        auto i = blobs.find(k);
        return i == blobs.end() ? 0 : i->second.size();
    }
    size_t getBytes(const char* k, void* buf, size_t len) {
        auto i = blobs.find(k);
        if (i == blobs.end() || i->second.size() > len) return 0;
        memcpy(buf, i->second.data(), i->second.size());
        return i->second.size();
    }
    size_t putBytes(const char* k, const void* buf, size_t len) {
        if (failWrites) return 0;
        writes++;
        blobs[k] = std::vector<uint8_t>((const uint8_t*)buf, (const uint8_t*)buf + len);
        return len;
    }
};

int main() {
    // a fresh box: everything counted is in the vault
    {
        BlobStore s;
        Ledger l;
        l.load(s);
        CHECK(l.totalCollections() == 0);
        CHECK(l.vaultCoins(40) == 40 && l.vaultCentavos(4000) == 4000);
        CHECK(l.baselineCoins() == 0);
    }

    // a collection: the vault shows 0, the lifetime counters are untouched, the record says what was taken
    {
        BlobStore s;
        Ledger l;
        l.load(s);
        Collection w;
        CHECK(l.collect(s, 40, 4000, 1700000000UL, SUPERADMIN_RESET, &w));
        CHECK(w.seq == 1 && w.coins == 40 && w.centavos == 4000 && w.cumCoins == 40 && w.reason == SUPERADMIN_RESET);
        CHECK(l.vaultCoins(40) == 0 && l.vaultCentavos(4000) == 0);
        // coins after the collection show up in the vault again, on top of the same lifetime counter
        CHECK(l.vaultCoins(47) == 7 && l.vaultCentavos(4700) == 700);
        CHECK(s.writes == 1); // one collection = one flash write
        // a second collection takes only what came after the first
        CHECK(l.collect(s, 55, 5500, 1700000600UL, UNMASK_EXPIRED, &w));
        CHECK(w.seq == 2 && w.coins == 15 && w.centavos == 1500 && w.cumCoins == 55);
    }

    // it survives a reboot: a new Ledger reading the same flash gives the same baseline and history
    {
        BlobStore s;
        Ledger a;
        a.load(s);
        a.collect(s, 10, 1000, 100, SUPERADMIN_LOGOUT);
        a.collect(s, 25, 2500, 200, UNMASK_EXPIRED);
        Ledger b;
        b.load(s);
        CHECK(b.totalCollections() == 2 && b.baselineCoins() == 25 && b.baselineCentavos() == 2500);
        CHECK(b.vaultCoins(30) == 5);
        Collection list[4];
        int n = b.recent(list, 4);
        CHECK(n == 2 && list[0].seq == 2 && list[0].coins == 15 && list[1].seq == 1 && list[1].coins == 10);
    }

    // an empty vault writes nothing (no flash wear, no empty history entries)
    {
        BlobStore s;
        Ledger l;
        l.load(s);
        CHECK(l.collect(s, 0, 0, 0, UNMASK_EXPIRED));
        l.collect(s, 5, 500, 1, SUPERADMIN_RESET);
        int before = s.writes;
        CHECK(l.collect(s, 5, 500, 2, UNMASK_EXPIRED));
        CHECK(s.writes == before && l.totalCollections() == 1);
    }

    // a refused write changes nothing: the money stays in the vault and a retry works
    {
        BlobStore s;
        Ledger l;
        l.load(s);
        l.collect(s, 5, 500, 1, SUPERADMIN_RESET);
        s.failWrites = true;
        CHECK(!l.collect(s, 9, 900, 2, SUPERADMIN_RESET));
        CHECK(l.totalCollections() == 1 && l.vaultCoins(9) == 4);
        s.failWrites = false;
        CHECK(l.collect(s, 9, 900, 3, SUPERADMIN_RESET));
        CHECK(l.totalCollections() == 2 && l.vaultCoins(9) == 0);
    }

    // only the last 16 are kept, the baseline is always the newest, and the order is right across the wrap
    {
        BlobStore s;
        Ledger l;
        l.load(s);
        for (uint32_t i = 1; i <= 40; i++)
            CHECK(l.collect(s, i * 10, i * 1000, i, SUPERADMIN_RESET));
        Ledger r;
        r.load(s);
        CHECK(r.totalCollections() == 40 && r.baselineCoins() == 400);
        Collection list[32];
        int n = r.recent(list, 32);
        CHECK(n == RING_SIZE && list[0].seq == 40 && list[RING_SIZE - 1].seq == 40 - RING_SIZE + 1);
        for (int i = 1; i < n; i++)
            CHECK(list[i - 1].seq == list[i].seq + 1);
        CHECK(r.recent(list, 3) == 3 && list[2].seq == 38);
    }

    // a damaged record is skipped, never trusted: flipped bits, wrong length, wrong magic
    {
        BlobStore s;
        Ledger l;
        l.load(s);
        l.collect(s, 10, 1000, 1, SUPERADMIN_RESET);
        l.collect(s, 20, 2000, 2, SUPERADMIN_RESET);
        s.blobs["col_01"][9] ^= 0x40; // record 2 (slot 1) corrupted
        Ledger r;
        r.load(s);
        CHECK(r.totalCollections() == 1 && r.baselineCoins() == 10); // falls back to the previous good record
        CHECK(r.vaultCoins(20) == 10);
        Collection list[4];
        CHECK(r.recent(list, 4) == 1 && list[0].seq == 1);
        s.blobs["col_00"].pop_back();
        Ledger t;
        t.load(s);
        CHECK(t.totalCollections() == 0 && t.vaultCoins(20) == 20);
        s.blobs["col_00"] = std::vector<uint8_t>(RECORD_BYTES, 0);
        Ledger u;
        u.load(s);
        CHECK(u.totalCollections() == 0);
    }

    // counters found BELOW the baseline (lost or replaced): the vault reads 0, not a huge wrapped number, and a REBASE
    // record moves the baseline down so coins counted from now on are not hidden
    {
        BlobStore s;
        Ledger l;
        l.load(s);
        l.collect(s, 500, 50000, 1, SUPERADMIN_RESET);
        CHECK(l.vaultCoins(3) == 0 && l.vaultCentavos(300) == 0);
        CHECK(l.needsRebase(3, 300) && !l.needsRebase(500, 50000));
        Collection w;
        CHECK(l.collect(s, 3, 300, 2, SUPERADMIN_RESET, &w));
        CHECK(w.reason == REBASE && w.coins == 0 && w.cumCoins == 3);
        CHECK(l.baselineCoins() == 3 && l.vaultCoins(8) == 5);
        CHECK(!l.needsRebase(8, 800));
    }

    // the record encoding is fixed (it lives in flash across firmware updates): pin the bytes
    {
        Collection c;
        c.seq = 1;
        c.timeS = 2;
        c.coins = 3;
        c.centavos = 4;
        c.cumCoins = 5;
        c.cumCentavos = 6;
        c.reason = 7;
        uint8_t raw[RECORD_BYTES];
        encode(c, raw);
        CHECK(RECORD_BYTES == 36);
        CHECK(raw[0] == 'R' && raw[1] == 'V' && raw[2] == 'L' && raw[3] == '1');
        CHECK(raw[4] == 1 && raw[5] == 0 && raw[8] == 2 && raw[12] == 3 && raw[28] == 7);
        Collection d;
        CHECK(decode(raw, d) && d.seq == 1 && d.cumCentavos == 6 && d.reason == 7);
    }

    printf("revenue_ledger_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
