#include "../include/PeriodLedger.h"

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

struct MockStore {
    std::map<std::string, std::vector<uint8_t>> blobs;
    size_t getBytesLength(const char* k) {
        auto it = blobs.find(k);
        return it == blobs.end() ? 0 : it->second.size();
    }
    size_t getBytes(const char* k, void* b, size_t n) {
        auto it = blobs.find(k);
        if (it == blobs.end() || it->second.size() != n) return 0;
        memcpy(b, it->second.data(), n);
        return n;
    }
    size_t putBytes(const char* k, const void* b, size_t n) {
        const uint8_t* p = reinterpret_cast<const uint8_t*>(b);
        blobs[k] = std::vector<uint8_t>(p, p + n);
        return n;
    }
};

int main() {
    // 1. Structure size and initialization
    CHECK(periodledger::STATE_BYTES < 1500);
    periodledger::State st;
    periodledger::initState(st, 28800); // UTC+8
    CHECK(st.magic == periodledger::PERIOD_MAGIC);
    CHECK(st.tzOffsetSec == 28800);
    CHECK(st.unsyncedWifiPesos == 0);
    CHECK(st.unsyncedPhonePesos == 0);

    // 2. Date conversion test
    int y = 0, m = 0, d = 0;
    periodledger::epochDayToDate(0, y, m, d);
    CHECK(y == 1970 && m == 1 && d == 1);

    // 2026-10-19: (1792368000) / 86400 = 20745
    // Let's verify unix timestamp 1792400000 (around Oct 19, 2026)
    uint32_t sampleSec = 1792411200UL; // 2026-10-19 12:00:00 UTC
    uint32_t localDay = periodledger::unixToLocalEpochDay(sampleSec, 28800);
    periodledger::epochDayToDate(localDay, y, m, d);
    CHECK(y == 2026 && m == 10 && d == 19);

    // 3. Pre-sync coin recording (box booted without NTP / network)
    periodledger::recordCoin(st, 0, 5, false, 1);   // 5 wifi coins pre-sync
    periodledger::recordCoin(st, 100, 10, true, 2); // 10 phone coins pre-sync
    CHECK(st.unsyncedWifiPesos == 5);
    CHECK(st.unsyncedPhonePesos == 10);
    CHECK(st.unsyncedTxCount == 2);
    CHECK(st.lastCountedSeq == 2);

    uint32_t w = 0, p = 0;
    uint16_t tx = 0;
    CHECK(periodledger::todayCoins(st, 0, &w, &p, &tx) == 15);
    CHECK(w == 5 && p == 10 && tx == 2);
    CHECK(periodledger::last7DaysCoins(st, 0) == 15);

    // 4. Time anchors: wall-clock time syncs to 2026-10-19 12:00:00
    periodledger::anchorTime(st, sampleSec);
    CHECK(st.unsyncedWifiPesos == 0);
    CHECK(st.unsyncedPhonePesos == 0);
    CHECK(st.unsyncedTxCount == 0);

    // After anchor, todayCoins for the synced timestamp returns 15
    CHECK(periodledger::todayCoins(st, sampleSec, &w, &p, &tx) == 15);
    CHECK(w == 5 && p == 10 && tx == 2);

    // 5. Post-sync coin recording on Today
    periodledger::recordCoin(st, sampleSec + 60, 20, false, 3); // 20 wifi coins on Oct 19
    CHECK(periodledger::todayCoins(st, sampleSec, &w, &p, &tx) == 35);
    CHECK(w == 25 && p == 10 && tx == 3);
    CHECK(st.lastCountedSeq == 3);

    // 6. Day rollover (Oct 20, 2026): +86400 seconds
    uint32_t nextDaySec = sampleSec + 86400UL;
    periodledger::recordCoin(st, nextDaySec, 50, true, 4); // 50 phone coins on Oct 20

    // On Oct 20: today = 50, yesterday = 35
    CHECK(periodledger::todayCoins(st, nextDaySec, &w, &p, &tx) == 50);
    CHECK(w == 0 && p == 50 && tx == 1);
    CHECK(periodledger::yesterdayCoins(st, nextDaySec, &w, &p) == 35);
    CHECK(w == 25 && p == 10);

    // 7. Rolling 7-day query
    uint32_t daily7[7] = {0};
    uint32_t sum7 = periodledger::last7DaysCoins(st, nextDaySec, daily7);
    CHECK(sum7 == 85);      // 35 on Oct 19 + 50 on Oct 20
    CHECK(daily7[5] == 35); // yesterday
    CHECK(daily7[6] == 50); // today

    // 8. Calendar week and month
    CHECK(periodledger::currentWeekCoins(st, nextDaySec) == 85);
    CHECK(periodledger::currentMonthCoins(st, nextDaySec) == 85);
    CHECK(periodledger::last30DaysCoins(st, nextDaySec) == 85);

    // 9. 62-Day Ring Buffer overflow test: write 70 distinct days
    for (int i = 2; i < 75; i++) {
        uint32_t futureSec = sampleSec + (uint32_t)i * 86400UL;
        periodledger::recordCoin(st, futureSec, 1, false, 100 + i);
    }
    // Verify no crash, magic intact, valid today
    uint32_t day74Sec = sampleSec + 74 * 86400UL;
    CHECK(periodledger::todayCoins(st, day74Sec) == 1);

    // 10. Serialization and checksum verification
    MockStore store;
    CHECK(periodledger::save(store, "period_v1", st));

    periodledger::State loaded;
    CHECK(periodledger::load(store, "period_v1", loaded));
    CHECK(loaded.magic == periodledger::PERIOD_MAGIC);
    CHECK(loaded.lastCountedSeq == st.lastCountedSeq);
    CHECK(periodledger::todayCoins(loaded, day74Sec) == 1);

    // Corrupt one byte in the store blob: decode must fail
    store.blobs["period_v1"][10] ^= 0xFF;
    CHECK(!periodledger::load(store, "period_v1", loaded));

    // Every day from 1970 to about 2100 turns into the right calendar date (checked against a day-by-day count).
    {
        int ry = 1970, rm = 1, rd = 1, bad = 0, firstBad = -1;
        for (uint32_t day = 0; day < 47500; day++) {
            int y, m, d;
            periodledger::epochDayToDate(day, y, m, d);
            if (y != ry || m != rm || d != rd) {
                if (firstBad < 0) firstBad = (int)day;
                bad++;
            }
            bool leap = (ry % 4 == 0 && ry % 100 != 0) || ry % 400 == 0;
            static const int dim[] = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
            int len = dim[rm - 1] + (rm == 2 && leap ? 1 : 0);
            if (++rd > len) {
                rd = 1;
                if (++rm > 12) {
                    rm = 1;
                    ry++;
                }
            }
        }
        if (bad) printf("epochDayToDate: %d wrong days, the first is day %d\n", bad, firstBad);
        CHECK(bad == 0);
    }

    printf("period_ledger_test: %d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
