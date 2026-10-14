#include "../include/OrphanLedger.h"

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

using namespace orphans;

// A flash stand-in that can be told to refuse writes.
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

static Entry make(const char* tx, uint32_t pulses, uint8_t reason = EXPIRED, const char* target = "DEVICE-A",
                  uint32_t timeS = 1000) {
    Entry e;
    copyText(e.txId, sizeof(e.txId), tx);
    copyText(e.target, sizeof(e.target), target);
    e.pulses = pulses;
    e.creditSeconds = pulses * 600;
    e.ageS = 86400;
    e.owner = 1;
    e.reason = reason;
    e.timeS = timeS;
    return e;
}

int main() {
    // a fresh box has nothing to report
    {
        BlobStore s;
        Ledger l;
        CHECK(!l.load(s));
        CHECK(l.unhandledCount() == 0 && l.unhandledPulses() == 0 && l.count() == 0 && l.totalRecorded() == 0);
    }

    // a dropped payment is recorded, listed newest first and counted until handled
    {
        BlobStore s;
        Ledger l;
        l.load(s);
        uint32_t seq = 0;
        CHECK(l.add(s, make("tx-1", 5), &seq) && seq == 1);
        CHECK(l.add(s, make("tx-2", 10, EXPIRED_UNDER_PRESSURE), &seq) && seq == 2);
        CHECK(l.unhandledCount() == 2 && l.unhandledPulses() == 15 && l.totalRecorded() == 2);
        Entry list[4];
        CHECK(l.recent(list, 4) == 2 && list[0].seq == 2 && !strcmp(list[0].txId, "tx-2") && list[1].seq == 1);
        CHECK(list[0].reason == EXPIRED_UNDER_PRESSURE && list[0].creditSeconds == 6000 && list[0].ageS == 86400);
        CHECK(s.writes == 2); // one flash write per change
        CHECK(l.handle(s, 1) && l.unhandledCount() == 1 && l.unhandledPulses() == 10);
        CHECK(l.handle(s, 1));   // handling twice is fine
        CHECK(!l.handle(s, 99)); // no such payment
        CHECK(l.handleAll(s) && l.unhandledCount() == 0 && l.unhandledPulses() == 0);
        CHECK(l.count() == 2); // handled ones stay listed
    }

    // adding the same payment again (a power cut left it queued) records it once, whatever the reason: no double counting
    {
        BlobStore s;
        Ledger l;
        l.load(s);
        CHECK(l.add(s, make("tx-1", 5)));
        int before = s.writes;
        uint32_t seq = 0;
        CHECK(l.add(s, make("tx-1", 5), &seq) && seq == 1 && s.writes == before && l.count() == 1);
        CHECK(l.add(s, make("tx-1", 5, CLEARED_BY_ADMIN), &seq) && seq == 1 && l.count() == 1 &&
              l.unhandledPulses() == 5);
        CHECK(l.add(s, make("tx-2", 5, CLEARED_BY_ADMIN)) && l.count() == 2);
        // payments without an id are never merged
        CHECK(l.add(s, make("", 1)) && l.add(s, make("", 1)) && l.count() == 4);
    }

    // it survives a reboot, with the handled marks
    {
        BlobStore s;
        Ledger a;
        a.load(s);
        a.add(s, make("tx-1", 5));
        a.add(s, make("tx-2", 7, NOT_QUEUED, "slot-9"));
        a.handle(s, 1);
        Ledger b;
        CHECK(b.load(s));
        CHECK(b.count() == 2 && b.unhandledCount() == 1 && b.unhandledPulses() == 7 && b.totalRecorded() == 2);
        Entry list[2];
        b.recent(list, 2);
        CHECK(!strcmp(list[0].target, "slot-9") && list[0].reason == NOT_QUEUED && list[1].handled == 1);
        // numbering continues after a reboot
        uint32_t seq = 0;
        CHECK(b.add(s, make("tx-3", 1), &seq) && seq == 3);
    }

    // a refused write changes nothing, and the caller is told so it keeps the payment
    {
        BlobStore s;
        Ledger l;
        l.load(s);
        l.add(s, make("tx-1", 5));
        s.failWrites = true;
        CHECK(!l.add(s, make("tx-2", 7)));
        CHECK(l.count() == 1 && l.unhandledPulses() == 5 && l.totalRecorded() == 1);
        CHECK(!l.handle(s, 1) && l.unhandledCount() == 1);
        CHECK(!l.handleAll(s) && l.unhandledCount() == 1);
        s.failWrites = false;
        CHECK(l.add(s, make("tx-2", 7)) && l.count() == 2);
    }

    // a full ledger replaces the oldest HANDLED payment first
    {
        BlobStore s;
        Ledger l;
        l.load(s);
        for (int i = 1; i <= RING_SIZE; i++)
            l.add(s, make(("tx-" + std::to_string(i)).c_str(), 1));
        CHECK(l.count() == RING_SIZE && l.unhandledCount() == (uint32_t)RING_SIZE);
        l.handle(s, 5); // one in the middle is handled
        CHECK(l.add(s, make("tx-new", 1)));
        CHECK(l.count() == RING_SIZE && l.unlistedCount() == 0); // nothing unhandled was lost
        Entry list[RING_SIZE];
        l.recent(list, RING_SIZE);
        bool has5 = false, has1 = false;
        for (int i = 0; i < RING_SIZE; i++) {
            if (list[i].seq == 5) has5 = true;
            if (list[i].seq == 1) has1 = true;
        }
        CHECK(!has5 && has1 && list[0].seq == (uint32_t)RING_SIZE + 1);
        for (int i = 1; i < RING_SIZE; i++)
            CHECK(list[i - 1].seq > list[i].seq); // still in order
    }

    // a full ledger of unhandled payments pushes the oldest out, but the totals keep counting it until "handle all"
    {
        BlobStore s;
        Ledger l;
        l.load(s);
        for (int i = 1; i <= RING_SIZE; i++)
            l.add(s, make(("tx-" + std::to_string(i)).c_str(), i));
        uint32_t before = l.unhandledPulses();
        CHECK(l.add(s, make("tx-over", 100)));
        CHECK(l.count() == RING_SIZE && l.unlistedCount() == 1 && l.unlistedPulses() == 1);
        CHECK(l.unhandledCount() == (uint32_t)RING_SIZE + 1);
        CHECK(l.unhandledPulses() == before + 100); // nothing disappeared from the total
        CHECK(l.add(s, make("tx-over2", 50)) && l.unlistedCount() == 2 && l.unlistedPulses() == 3);
        // survives a reboot
        Ledger r;
        r.load(s);
        CHECK(r.unlistedCount() == 2 && r.unhandledPulses() == l.unhandledPulses());
        // handling one listed payment does not clear the unlisted ones
        Entry list[2];
        r.recent(list, 1);
        CHECK(r.handle(s, list[0].seq) && r.unlistedCount() == 2);
        // "handle all" clears everything, listed or not
        CHECK(r.handleAll(s) && r.unhandledCount() == 0 && r.unhandledPulses() == 0 && r.unlistedCount() == 0);
        Ledger r2;
        r2.load(s);
        CHECK(r2.unhandledCount() == 0);
        // and new ones are counted again on top
        CHECK(r2.add(s, make("tx-after", 4)) && r2.unhandledCount() == 1 && r2.unhandledPulses() == 4);
    }

    // damaged storage is never trusted: flipped bits, wrong length, wrong magic, impossible numbers
    {
        BlobStore s;
        Ledger l;
        l.load(s);
        l.add(s, make("tx-1", 5));
        l.add(s, make("tx-2", 7));
        std::vector<uint8_t> good = s.blobs[LEDGER_KEY];
        for (size_t at : {size_t(0), size_t(5), size_t(40), good.size() / 2, good.size() - 1}) {
            BlobStore d;
            d.blobs[LEDGER_KEY] = good;
            d.blobs[LEDGER_KEY][at] ^= 0x10;
            Ledger r;
            CHECK(!r.load(d) && r.count() == 0 && r.unhandledCount() == 0);
        }
        BlobStore d1;
        d1.blobs[LEDGER_KEY] = std::vector<uint8_t>(good.begin(), good.end() - 1);
        Ledger r1;
        CHECK(!r1.load(d1));
        BlobStore d2;
        d2.blobs[LEDGER_KEY] = std::vector<uint8_t>(good.size() + 7, 0);
        Ledger r2;
        CHECK(!r2.load(d2));
        // a header whose "acknowledged lost" exceeds "lost" would wrap the totals: refused even with a valid CRC
        State bad;
        bad.lostCount = 1;
        bad.lostAckCount = 5;
        uint8_t raw[MAX_BLOB_BYTES];
        size_t len = encode(bad, raw);
        State out;
        CHECK(!decode(raw, len, out));
    }

    // long text is cut, always ends with a NUL, and the entry layout is fixed (it lives in flash across firmware updates)
    {
        Entry e = make("", 3);
        copyText(e.txId, sizeof(e.txId), std::string(100, 'x').c_str());
        copyText(e.target, sizeof(e.target), std::string(100, 'y').c_str());
        CHECK(strlen(e.txId) == ID_LEN - 1 && strlen(e.target) == TARGET_LEN - 1);
        CHECK(ENTRY_BYTES == 80 && MAX_BLOB_BYTES == 32 + 16 * 80 + 4 && MAX_BLOB_BYTES == 1316);
        State s;
        s.count = 1;
        s.entries[0] = make("tx-9", 3);
        s.entries[0].seq = 1;
        uint8_t raw[MAX_BLOB_BYTES];
        size_t len = encode(s, raw);
        CHECK(len == 32 + 80 + 4);
        CHECK(raw[0] == 'O' && raw[1] == 'R' && raw[2] == 'L' && raw[3] == '1');
        CHECK(raw[32] == 1 && raw[40] == 3); // seq, then (timeS, pulses): little-endian words
        State back;
        CHECK(decode(raw, len, back) && back.count == 1 && !strcmp(back.entries[0].txId, "tx-9"));
    }

    printf("orphan_ledger_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
