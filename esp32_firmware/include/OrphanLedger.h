#ifndef ORPHAN_LEDGER_H
#define ORPHAN_LEDGER_H

// The record of coin payments the box could not hand over: money a customer put in that never became time on a phone (or Wi-Fi
// access on the router). A payment that nobody acknowledges is not kept for ever, so before one is dropped it is written here,
// where it stays until the operator marks it handled (refunded, or credited by hand). Without this, the only trace of an
// unclaimed payment was a log line that rolled off the end of a 300-line buffer.
//
// The whole ledger is ONE flash record (a few hundred bytes to about 1.3 KB, checked by a CRC), written whole for every change,
// so a power cut leaves either the old or the new ledger, never a half-written one, and it costs the small settings partition
// about 40 entries in all. It holds the newest RING_SIZE payments. When it is full the oldest HANDLED one is replaced; only if
// every entry is still unhandled is the oldest unhandled one replaced, and then it is counted in lostCount/lostPulses, so the
// totals shown to the operator never silently shrink.
//
// Pure C++ (no Arduino), tested on the host (host_tests/orphan_ledger_test.cpp).
// `Store` is any class with: size_t getBytesLength(const char*), size_t getBytes(const char*, void*, size_t),
// size_t putBytes(const char*, const void*, size_t).

#include <stddef.h>
#include <stdint.h>
#include <string.h>

namespace orphans {

static const int RING_SIZE = 16;
static const uint32_t LEDGER_MAGIC = 0x314C524FUL; // "ORL1"
static const char* const LEDGER_KEY = "ledger";
static const size_t ID_LEN = 32;     // transaction id, cut to 31 characters
static const size_t TARGET_LEN = 24; // phone or session id, cut to 23 characters
static const size_t HEADER_WORDS = 8;
static const size_t ENTRY_BYTES = 6 * 4 + ID_LEN + TARGET_LEN;
static const size_t MAX_BLOB_BYTES = HEADER_WORDS * 4 + RING_SIZE * ENTRY_BYTES + 4;

// Why a payment ended up here.
enum Reason : uint8_t {
    EXPIRED = 1,                // not acknowledged for 24 hours
    EXPIRED_UNDER_PRESSURE = 2, // not acknowledged for 30 minutes while the queue was nearly full
    CLEARED_BY_ADMIN = 3,       // removed with the "clear payments" request
    NOT_QUEUED = 4,             // the coin was accepted but could not be recorded (queue full or flash unwritable)
};

inline const char* reasonName(uint8_t r) {
    switch (r) {
    case EXPIRED:
        return "expired";
    case EXPIRED_UNDER_PRESSURE:
        return "expired-queue-full";
    case CLEARED_BY_ADMIN:
        return "cleared";
    case NOT_QUEUED:
        return "not-queued";
    }
    return "unknown";
}

struct Entry {
    uint32_t seq = 0;           // 1, 2, 3 ... never reused
    uint32_t timeS = 0;         // seconds since 1970 (the box's master clock), 0 when it did not know the time
    uint32_t pulses = 0;        // coins; one coin is one peso
    uint32_t creditSeconds = 0; // the time those coins were worth to a phone, 0 when not applicable
    uint32_t ageS = 0;          // how long the payment waited before it was given up on
    uint8_t owner = 0;          // 1 = phone, 2 = controller, 3 = router gateway (PaymentRecord::ownerType)
    uint8_t reason = 0;
    uint8_t handled = 0;
    char txId[ID_LEN] = {0};
    char target[TARGET_LEN] = {0};
};

inline void copyText(char* dst, size_t n, const char* src) {
    memset(dst, 0, n);
    if (src == nullptr) return;
    size_t len = strlen(src);
    if (len > n - 1) len = n - 1;
    memcpy(dst, src, len);
}

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

// Everything that is stored.
struct State {
    uint32_t nextSeq = 1;
    uint32_t lostCount = 0; // unhandled payments pushed out of a full ledger, ever
    uint32_t lostPulses = 0;
    uint32_t lostAckCount = 0; // of those, the ones the operator has marked handled ("handle all")
    uint32_t lostAckPulses = 0;
    int count = 0;
    Entry entries[RING_SIZE]; // oldest first
};

// Writes `s` into `out` (at least MAX_BLOB_BYTES) and returns the length.
inline size_t encode(const State& s, uint8_t* out) {
    putWord(out + 0, LEDGER_MAGIC);
    putWord(out + 4, s.nextSeq);
    putWord(out + 8, (uint32_t)s.count);
    putWord(out + 12, s.lostCount);
    putWord(out + 16, s.lostPulses);
    putWord(out + 20, s.lostAckCount);
    putWord(out + 24, s.lostAckPulses);
    putWord(out + 28, 0); // reserved
    size_t pos = HEADER_WORDS * 4;
    for (int i = 0; i < s.count; i++) {
        const Entry& e = s.entries[i];
        putWord(out + pos + 0, e.seq);
        putWord(out + pos + 4, e.timeS);
        putWord(out + pos + 8, e.pulses);
        putWord(out + pos + 12, e.creditSeconds);
        putWord(out + pos + 16, e.ageS);
        putWord(out + pos + 20, (uint32_t)e.owner | ((uint32_t)e.reason << 8) | ((uint32_t)e.handled << 16));
        memcpy(out + pos + 24, e.txId, ID_LEN);
        memcpy(out + pos + 24 + ID_LEN, e.target, TARGET_LEN);
        pos += ENTRY_BYTES;
    }
    putWord(out + pos, fnv1a(out, pos));
    return pos + 4;
}

inline bool decode(const uint8_t* in, size_t len, State& s) {
    if (len < HEADER_WORDS * 4 + 4 || len > MAX_BLOB_BYTES) return false;
    if (getWord(in) != LEDGER_MAGIC) return false;
    if (getWord(in + len - 4) != fnv1a(in, len - 4)) return false;
    uint32_t count = getWord(in + 8);
    if (count > (uint32_t)RING_SIZE || len != HEADER_WORDS * 4 + count * ENTRY_BYTES + 4) return false;
    State t;
    t.nextSeq = getWord(in + 4);
    t.count = (int)count;
    t.lostCount = getWord(in + 12);
    t.lostPulses = getWord(in + 16);
    t.lostAckCount = getWord(in + 20);
    t.lostAckPulses = getWord(in + 24);
    if (t.lostAckCount > t.lostCount || t.lostAckPulses > t.lostPulses) return false; // the totals would wrap
    size_t pos = HEADER_WORDS * 4;
    for (uint32_t i = 0; i < count; i++) {
        Entry& e = t.entries[i];
        e.seq = getWord(in + pos + 0);
        e.timeS = getWord(in + pos + 4);
        e.pulses = getWord(in + pos + 8);
        e.creditSeconds = getWord(in + pos + 12);
        e.ageS = getWord(in + pos + 16);
        uint32_t meta = getWord(in + pos + 20);
        e.owner = (uint8_t)meta;
        e.reason = (uint8_t)(meta >> 8);
        e.handled = (uint8_t)(meta >> 16);
        memcpy(e.txId, in + pos + 24, ID_LEN);
        memcpy(e.target, in + pos + 24 + ID_LEN, TARGET_LEN);
        e.txId[ID_LEN - 1] = '\0'; // whatever was stored, a string read from it always ends
        e.target[TARGET_LEN - 1] = '\0';
        if (e.seq == 0 || (i > 0 && e.seq <= t.entries[i - 1].seq))
            return false; // must be oldest first, strictly rising
        pos += ENTRY_BYTES;
    }
    s = t;
    return true;
}

class Ledger {
public:
    // Reads the stored ledger. Anything missing or damaged gives an empty one (and false).
    template <class Store> bool load(Store& st) {
        s_ = State();
        uint8_t raw[MAX_BLOB_BYTES];
        size_t len = st.getBytesLength(LEDGER_KEY);
        if (len == 0 || len > MAX_BLOB_BYTES || st.getBytes(LEDGER_KEY, raw, len) != len) return false;
        State t;
        if (!decode(raw, len, t)) return false;
        s_ = t;
        return true;
    }

    void clear() { s_ = State(); }

    // Records a payment that was given up on. A transaction is recorded once, whatever the reason, so doing it again after a
    // power cut (the payment was still queued) adds nothing and cannot count the same money twice. Returns false (and changes nothing) when the flash write
    // failed: the caller must then keep the payment where it is.
    template <class Store> bool add(Store& st, const Entry& entry, uint32_t* seqOut = nullptr) {
        if (entry.txId[0] != '\0') {
            for (int i = 0; i < s_.count; i++) {
                if (strncmp(s_.entries[i].txId, entry.txId, ID_LEN) == 0) {
                    if (seqOut) *seqOut = s_.entries[i].seq;
                    return true;
                }
            }
        }
        State t = s_;
        if (t.count == RING_SIZE) {
            int victim = 0; // the oldest handled payment, or the oldest of all when none is handled
            for (int i = 0; i < t.count; i++) {
                if (t.entries[i].handled) {
                    victim = i;
                    break;
                }
            }
            if (!t.entries[victim].handled) {
                t.lostCount++;
                t.lostPulses += t.entries[victim].pulses;
            }
            for (int i = victim; i + 1 < t.count; i++)
                t.entries[i] = t.entries[i + 1];
            t.count--;
        }
        Entry e = entry;
        e.seq = t.nextSeq++;
        e.handled = 0;
        e.txId[ID_LEN - 1] = '\0';
        e.target[TARGET_LEN - 1] = '\0';
        t.entries[t.count++] = e;
        if (!save(st, t)) return false;
        s_ = t;
        if (seqOut) *seqOut = e.seq;
        return true;
    }

    // Marks one payment handled. True when it is handled afterwards (also when it already was); false when there is no such
    // payment or the flash write failed.
    template <class Store> bool handle(Store& st, uint32_t seq) {
        State t = s_;
        for (int i = 0; i < t.count; i++) {
            if (t.entries[i].seq != seq) continue;
            if (t.entries[i].handled) return true;
            t.entries[i].handled = 1;
            if (!save(st, t)) return false;
            s_ = t;
            return true;
        }
        return false;
    }

    // Marks everything handled, including the payments that no longer fit in the ledger.
    template <class Store> bool handleAll(Store& st) {
        if (unhandledCount() == 0) return true;
        State t = s_;
        for (int i = 0; i < t.count; i++)
            t.entries[i].handled = 1;
        t.lostAckCount = t.lostCount;
        t.lostAckPulses = t.lostPulses;
        if (!save(st, t)) return false;
        s_ = t;
        return true;
    }

    // Payments still waiting for the operator, with those pushed out of a full ledger counted in.
    uint32_t unhandledCount() const {
        uint32_t n = s_.lostCount - s_.lostAckCount;
        for (int i = 0; i < s_.count; i++)
            n += s_.entries[i].handled ? 0 : 1;
        return n;
    }
    uint32_t unhandledPulses() const {
        uint32_t n = s_.lostPulses - s_.lostAckPulses;
        for (int i = 0; i < s_.count; i++)
            n += s_.entries[i].handled ? 0 : s_.entries[i].pulses;
        return n;
    }
    // Of the unhandled ones, how many are no longer listed because the ledger was full.
    uint32_t unlistedCount() const { return s_.lostCount - s_.lostAckCount; }
    uint32_t unlistedPulses() const { return s_.lostPulses - s_.lostAckPulses; }

    uint32_t totalRecorded() const { return s_.nextSeq - 1; }
    int count() const { return s_.count; }

    // Newest first. Returns how many were copied (at most `max`).
    int recent(Entry* out, int max) const {
        int n = 0;
        for (int i = s_.count - 1; i >= 0 && n < max; i--)
            out[n++] = s_.entries[i];
        return n;
    }

private:
    template <class Store> static bool save(Store& st, const State& t) {
        uint8_t raw[MAX_BLOB_BYTES];
        size_t len = encode(t, raw);
        return st.putBytes(LEDGER_KEY, raw, len) == len;
    }

    State s_;
};

} // namespace orphans

#endif // ORPHAN_LEDGER_H
