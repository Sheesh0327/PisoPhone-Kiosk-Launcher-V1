#ifndef PERIOD_LEDGER_H
#define PERIOD_LEDGER_H

// Period revenue ledger: tracks Daily, Weekly, and Monthly coin counts directly on the ESP32 coin box.
// Stored in NVS ("rev_ledger" namespace, key "period_v1") as a single self-checking binary record.
//
// Key capabilities:
// 1. Pre-sync boot buffer: Coins accepted before the box knows wall-clock time are safely accumulated
//    in `unsynced*` fields and atomically folded into the current local day's bucket as soon as wall-clock
//    time anchors (from router poll, phone heartbeat, or SNTP).
// 2. 62-Day Rolling Ring Buffer: Stores exact daily breakdown (Wi-Fi vs. Rental Phone pesos and transactions)
//    for the last 2 months. Oldest days are automatically reclaimed.
// 3. Rolling & Calendar Queries: Provides Today, Yesterday, 7-Day Rolling (Weekly), Calendar Week (Mon-Sun),
//    30-Day Rolling (Monthly), and Calendar Month (e.g. October 2026) revenue totals.
// 4. Monotonic Sequence Watermark: Tracks `lastCountedSeq` so uncounted records in `pay_queue` can be
//    replayed idempotently across brownouts.
//
// Pure C++ (no Arduino / ESP-IDF dependencies), fully host-testable.

#include <stddef.h>
#include <stdint.h>
#include <string.h>

namespace periodledger {

static const size_t RING_DAYS = 62;
static const uint32_t PERIOD_MAGIC = 0x31444550UL;        // "PED1"
static const uint32_t MIN_VALID_EPOCH_SEC = 1700000000UL; // ~Nov 2023 baseline

#pragma pack(push, 1)
struct DayBucket {
    uint32_t epochDay;   // Local day number: (unix_sec + tz_offset_sec) / 86400 (0 = unused)
    uint32_t wifiPesos;  // PisoWiFi (gateway) coins accepted on this day
    uint32_t phonePesos; // Rental phone (kiosk) coins accepted on this day
    uint16_t txCount;    // Number of coin train transactions on this day
    uint16_t reserved;
};

struct State {
    uint32_t magic;
    int32_t tzOffsetSec;        // Local timezone offset in seconds (default +28800 = UTC+8 PST)
    uint32_t lastCountedSeq;    // Highest coin transaction seq counted into period/lifetime
    uint32_t unsyncedWifiPesos; // Coins accepted before valid wall-clock time
    uint32_t unsyncedPhonePesos;
    uint16_t unsyncedTxCount;
    uint16_t reserved;
    DayBucket days[RING_DAYS];
    uint32_t checksum; // FNV-1a checksum over all preceding bytes
};
#pragma pack(pop)

static const size_t STATE_BYTES = sizeof(State);

// Pure integer conversion from epochDay to civil calendar (year, month 1-12, day 1-31).
// Valid for all modern Gregorian dates.
inline void epochDayToDate(uint32_t epochDay, int& year, int& month, int& day) {
    int64_t z = (int64_t)epochDay + 719468;
    int64_t era = (z >= 0 ? z : z - 146096) / 146097;
    unsigned doe = static_cast<unsigned>(z - era * 146097);
    unsigned yoe = (doe - doe / 1024 + doe / 1461 - doe / 142401) / 365;
    int y = static_cast<int>(yoe) + (int)(era * 400);
    unsigned doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    unsigned mp = (5 * doy + 2) / 153;
    unsigned d = doy - (153 * mp + 2) / 5 + 1;
    unsigned m = mp < 10 ? mp + 3 : mp - 9;
    year = y + (m <= 2 ? 1 : 0);
    month = (int)m;
    day = (int)d;
}

inline uint32_t fnv1a(const uint8_t* d, size_t n) {
    uint32_t h = 2166136261UL;
    for (size_t i = 0; i < n; i++) {
        h ^= d[i];
        h *= 16777619UL;
    }
    return h;
}

inline void initState(State& st, int32_t tzOffsetSec = 28800) {
    memset(&st, 0, sizeof(st));
    st.magic = PERIOD_MAGIC;
    st.tzOffsetSec = tzOffsetSec;
}

inline uint32_t unixToLocalEpochDay(uint32_t unixSec, int32_t tzOffsetSec) {
    int64_t localSec = (int64_t)unixSec + (int64_t)tzOffsetSec;
    if (localSec < 0) return 0;
    return (uint32_t)(localSec / 86400LL);
}

inline DayBucket* findBucket(State& st, uint32_t epochDay) {
    if (epochDay == 0) return nullptr;
    for (size_t i = 0; i < RING_DAYS; i++) {
        if (st.days[i].epochDay == epochDay) return &st.days[i];
    }
    return nullptr;
}

inline DayBucket* findOrCreateBucket(State& st, uint32_t epochDay) {
    if (epochDay == 0) return nullptr;
    DayBucket* existing = findBucket(st, epochDay);
    if (existing) return existing;

    // Look for an unused slot
    for (size_t i = 0; i < RING_DAYS; i++) {
        if (st.days[i].epochDay == 0) {
            st.days[i].epochDay = epochDay;
            st.days[i].wifiPesos = 0;
            st.days[i].phonePesos = 0;
            st.days[i].txCount = 0;
            st.days[i].reserved = 0;
            return &st.days[i];
        }
    }

    // All slots full: replace the oldest epochDay
    size_t oldestIdx = 0;
    uint32_t oldestDay = st.days[0].epochDay;
    for (size_t i = 1; i < RING_DAYS; i++) {
        if (st.days[i].epochDay < oldestDay) {
            oldestDay = st.days[i].epochDay;
            oldestIdx = i;
        }
    }
    st.days[oldestIdx].epochDay = epochDay;
    st.days[oldestIdx].wifiPesos = 0;
    st.days[oldestIdx].phonePesos = 0;
    st.days[oldestIdx].txCount = 0;
    st.days[oldestIdx].reserved = 0;
    return &st.days[oldestIdx];
}

// Atomically folds any pending pre-sync coins into Today's bucket when wall-clock time syncs.
inline void anchorTime(State& st, uint32_t unixSec) {
    if (unixSec < MIN_VALID_EPOCH_SEC) return;
    if (st.unsyncedWifiPesos == 0 && st.unsyncedPhonePesos == 0 && st.unsyncedTxCount == 0) return;

    uint32_t today = unixToLocalEpochDay(unixSec, st.tzOffsetSec);
    if (today == 0) return;

    DayBucket* b = findOrCreateBucket(st, today);
    if (b) {
        b->wifiPesos += st.unsyncedWifiPesos;
        b->phonePesos += st.unsyncedPhonePesos;
        b->txCount += st.unsyncedTxCount;
    }
    st.unsyncedWifiPesos = 0;
    st.unsyncedPhonePesos = 0;
    st.unsyncedTxCount = 0;
}

// Records a coin transaction into the period ledger.
inline void recordCoin(State& st, uint32_t unixSec, uint32_t pesos, bool isPhone, uint32_t seq = 0) {
    if (pesos == 0) return;
    if (seq > 0 && seq > st.lastCountedSeq) {
        st.lastCountedSeq = seq;
    }

    if (unixSec < MIN_VALID_EPOCH_SEC) {
        // Pre-sync: hold in boot buffer until clock syncs
        if (isPhone) {
            st.unsyncedPhonePesos += pesos;
        } else {
            st.unsyncedWifiPesos += pesos;
        }
        st.unsyncedTxCount++;
        return;
    }

    // Time is valid: ensure any previous unsynced coins are folded into Today first
    anchorTime(st, unixSec);

    uint32_t today = unixToLocalEpochDay(unixSec, st.tzOffsetSec);
    DayBucket* b = findOrCreateBucket(st, today);
    if (b) {
        if (isPhone) {
            b->phonePesos += pesos;
        } else {
            b->wifiPesos += pesos;
        }
        b->txCount++;
    }
}

// Query Today's coins (including unsynced coins if wall clock has not yet synced).
inline uint32_t todayCoins(const State& st, uint32_t unixSec, uint32_t* outWifi = nullptr, uint32_t* outPhone = nullptr,
                           uint16_t* outTx = nullptr) {
    uint32_t w = st.unsyncedWifiPesos;
    uint32_t p = st.unsyncedPhonePesos;
    uint16_t tx = st.unsyncedTxCount;

    if (unixSec >= MIN_VALID_EPOCH_SEC) {
        uint32_t today = unixToLocalEpochDay(unixSec, st.tzOffsetSec);
        const DayBucket* b = findBucket(const_cast<State&>(st), today);
        if (b) {
            w += b->wifiPesos;
            p += b->phonePesos;
            tx += b->txCount;
        }
    }
    if (outWifi) *outWifi = w;
    if (outPhone) *outPhone = p;
    if (outTx) *outTx = tx;
    return w + p;
}

// Query Yesterday's coins.
inline uint32_t yesterdayCoins(const State& st, uint32_t unixSec, uint32_t* outWifi = nullptr,
                               uint32_t* outPhone = nullptr) {
    if (unixSec < MIN_VALID_EPOCH_SEC) return 0;
    uint32_t today = unixToLocalEpochDay(unixSec, st.tzOffsetSec);
    if (today == 0) return 0;
    uint32_t yest = today - 1;
    const DayBucket* b = findBucket(const_cast<State&>(st), yest);
    uint32_t w = b ? b->wifiPesos : 0;
    uint32_t p = b ? b->phonePesos : 0;
    if (outWifi) *outWifi = w;
    if (outPhone) *outPhone = p;
    return w + p;
}

// Rolling 7-day (weekly) total and optional 7-element daily breakdown array [oldest..newest].
inline uint32_t last7DaysCoins(const State& st, uint32_t unixSec, uint32_t outDaily7[7] = nullptr) {
    uint32_t sum = 0;
    if (outDaily7) {
        for (int i = 0; i < 7; i++)
            outDaily7[i] = 0;
    }

    if (unixSec < MIN_VALID_EPOCH_SEC) {
        sum = st.unsyncedWifiPesos + st.unsyncedPhonePesos;
        if (outDaily7) outDaily7[6] = sum;
        return sum;
    }

    uint32_t today = unixToLocalEpochDay(unixSec, st.tzOffsetSec);
    for (int i = 0; i < 7; i++) {
        uint32_t targetDay = today - (6 - i);
        const DayBucket* b = findBucket(const_cast<State&>(st), targetDay);
        uint32_t dayTotal = b ? (b->wifiPesos + b->phonePesos) : 0;
        if (targetDay == today) {
            dayTotal += (st.unsyncedWifiPesos + st.unsyncedPhonePesos);
        }
        if (outDaily7) outDaily7[i] = dayTotal;
        sum += dayTotal;
    }
    return sum;
}

// Calendar week total (Monday to Sunday of the current week).
inline uint32_t currentWeekCoins(const State& st, uint32_t unixSec) {
    if (unixSec < MIN_VALID_EPOCH_SEC) {
        return st.unsyncedWifiPesos + st.unsyncedPhonePesos;
    }
    uint32_t today = unixToLocalEpochDay(unixSec, st.tzOffsetSec);
    // 1970-01-01 was Thursday (day index 3 if Mon=0..Sun=6).
    // (epochDay + 3) % 7 gives day of week where 0 = Monday, ..., 6 = Sunday.
    uint32_t dayOfWeek = (today + 3) % 7;
    uint32_t monday = today - dayOfWeek;

    uint32_t sum = 0;
    for (uint32_t d = monday; d <= today; d++) {
        const DayBucket* b = findBucket(const_cast<State&>(st), d);
        if (b) sum += (b->wifiPesos + b->phonePesos);
    }
    sum += (st.unsyncedWifiPesos + st.unsyncedPhonePesos);
    return sum;
}

// Rolling 30-day (monthly) total.
inline uint32_t last30DaysCoins(const State& st, uint32_t unixSec) {
    if (unixSec < MIN_VALID_EPOCH_SEC) {
        return st.unsyncedWifiPesos + st.unsyncedPhonePesos;
    }
    uint32_t today = unixToLocalEpochDay(unixSec, st.tzOffsetSec);
    uint32_t sum = 0;
    for (int i = 0; i < 30; i++) {
        uint32_t d = today - i;
        const DayBucket* b = findBucket(const_cast<State&>(st), d);
        if (b) sum += (b->wifiPesos + b->phonePesos);
    }
    sum += (st.unsyncedWifiPesos + st.unsyncedPhonePesos);
    return sum;
}

// Current calendar month total (e.g. all days in 2026-10).
inline uint32_t currentMonthCoins(const State& st, uint32_t unixSec) {
    if (unixSec < MIN_VALID_EPOCH_SEC) {
        return st.unsyncedWifiPesos + st.unsyncedPhonePesos;
    }
    uint32_t today = unixToLocalEpochDay(unixSec, st.tzOffsetSec);
    int curY = 0, curM = 0, curD = 0;
    epochDayToDate(today, curY, curM, curD);

    uint32_t sum = 0;
    for (size_t i = 0; i < RING_DAYS; i++) {
        if (st.days[i].epochDay == 0) continue;
        int y = 0, m = 0, d = 0;
        epochDayToDate(st.days[i].epochDay, y, m, d);
        if (y == curY && m == curM) {
            sum += (st.days[i].wifiPesos + st.days[i].phonePesos);
        }
    }
    sum += (st.unsyncedWifiPesos + st.unsyncedPhonePesos);
    return sum;
}

// Binary serialization with FNV-1a checksum
inline size_t encode(State& st, uint8_t* out, size_t maxLen) {
    if (maxLen < STATE_BYTES) return 0;
    st.checksum = fnv1a(reinterpret_cast<const uint8_t*>(&st), STATE_BYTES - 4);
    memcpy(out, &st, STATE_BYTES);
    return STATE_BYTES;
}

inline bool decode(const uint8_t* in, size_t len, State& st) {
    if (len != STATE_BYTES) return false;
    const State* src = reinterpret_cast<const State*>(in);
    if (src->magic != PERIOD_MAGIC) return false;
    uint32_t expectedChecksum = fnv1a(in, STATE_BYTES - 4);
    if (src->checksum != expectedChecksum) return false;
    memcpy(&st, in, STATE_BYTES);
    return true;
}

template <class Store> inline bool load(Store& store, const char* key, State& st) {
    if (store.getBytesLength(key) != STATE_BYTES) return false;
    uint8_t buf[STATE_BYTES];
    if (store.getBytes(key, buf, STATE_BYTES) != STATE_BYTES) return false;
    return decode(buf, STATE_BYTES, st);
}

template <class Store> inline bool save(Store& store, const char* key, State& st) {
    uint8_t buf[STATE_BYTES];
    if (encode(st, buf, STATE_BYTES) != STATE_BYTES) return false;
    return store.putBytes(key, buf, STATE_BYTES) == STATE_BYTES;
}

} // namespace periodledger

#endif // PERIOD_LEDGER_H
