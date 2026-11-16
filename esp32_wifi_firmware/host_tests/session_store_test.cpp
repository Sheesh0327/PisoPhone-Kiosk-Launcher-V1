// Host test for GatewaySessionStore.h: pulse accumulation, immediate life_pulses write,
// ack refusal while ARMED/DRAINING, NVS erase failure (ACK_INCOMPLETE), re-arming when 16/16
// slots full, dual-clock NTP jump safety, TTL eviction, write retry + probeStorage recovery,
// and factory-reset preservation.

#include "../include/GatewaySessionStore.h"
#include <cstdio>
#include <cstring>
#include <map>
#include <string>
#include <vector>

using namespace sessionstore;

static int failures = 0, checks = 0;
#define CHECK(c)                                                                                                       \
    do {                                                                                                               \
        checks++;                                                                                                      \
        if (!(c)) {                                                                                                    \
            failures++;                                                                                                \
            printf("FAIL line %d: %s\n", __LINE__, #c);                                                                \
        }                                                                                                              \
    } while (0)

struct MockNvs {
    std::map<std::string, std::map<std::string, std::vector<uint8_t>>> namespaces;
    std::map<std::string, std::map<std::string, uint32_t>> ulongs;
    std::string activeNs;
    bool open = false;

    bool failBegin = false;
    int failWrites = 0;
    bool failRemove = false;

    bool begin(const char* ns, bool) {
        if (failBegin) return false;
        activeNs = ns;
        open = true;
        return true;
    }

    void end() { open = false; }

    size_t getBytesLength(const char* key) {
        if (!open) return 0;
        auto it = namespaces[activeNs].find(key);
        return it == namespaces[activeNs].end() ? 0 : it->second.size();
    }

    size_t getBytes(const char* key, void* buf, size_t len) {
        if (!open) return 0;
        auto it = namespaces[activeNs].find(key);
        if (it == namespaces[activeNs].end()) return 0;
        size_t n = it->second.size() < len ? it->second.size() : len;
        std::memcpy(buf, it->second.data(), n);
        return n;
    }

    size_t putBytes(const char* key, const void* buf, size_t len) {
        if (!open) return 0;
        if (failWrites > 0) {
            failWrites--;
            return 0;
        }
        const uint8_t* p = (const uint8_t*)buf;
        namespaces[activeNs][key] = std::vector<uint8_t>(p, p + len);
        return len;
    }

    uint32_t getULong(const char* key, uint32_t def) {
        if (!open) return def;
        auto it = ulongs[activeNs].find(key);
        return it == ulongs[activeNs].end() ? def : it->second;
    }

    size_t putULong(const char* key, uint32_t val) {
        if (!open) return 0;
        if (failWrites > 0) {
            failWrites--;
            return 0;
        }
        ulongs[activeNs][key] = val;
        return sizeof(uint32_t);
    }

    bool isKey(const char* key) {
        if (!open) return false;
        return namespaces[activeNs].count(key) > 0 || ulongs[activeNs].count(key) > 0;
    }

    bool remove(const char* key) {
        if (!open || failRemove) return false;
        namespaces[activeNs].erase(key);
        ulongs[activeNs].erase(key);
        return true;
    }

    void clearNamespace(const char* ns) {
        namespaces.erase(ns);
        ulongs.erase(ns);
    }
};

int main() {
    // 1. Pulse accumulation + immediate life_pulses persistence + factory-reset preservation
    {
        MockNvs nvs;
        nvs.ulongs["pisowifi_cfg"]["dummy"] = 1;
        GatewaySessionStore<MockNvs> store(nvs);
        CHECK(store.init(1000));
        CHECK(store.isStorageReady());
        CHECK(store.lifetimePulses() == 0);

        CHECK(store.recordPulses("sessA", 2, 1000, 1760000000000ULL));
        CHECK(store.recordPulses("sessA", 3, 2000, 1760000001000ULL));
        CHECK(store.pendingPulses("sessA") == 5);
        CHECK(store.lifetimePulses() == 5);
        // Persisted immediately to NVS without a deferred flush
        CHECK(nvs.ulongs["pay_queue"]["life_pulses"] == 5);
        CHECK(nvs.namespaces["pay_queue"].count("rec_00") == 1);

        // Simulated operator factory reset wipes pisowifi_cfg but preserves pay_queue across reboot
        nvs.clearNamespace("pisowifi_cfg");
        GatewaySessionStore<MockNvs> rebooted(nvs);
        CHECK(rebooted.init(500));
        CHECK(rebooted.pendingPulses("sessA") == 5);
        CHECK(rebooted.lifetimePulses() == 5);
    }

    // 2. Ack refused while ARMED/DRAINING (SLOT_BUSY), and NVS erase failure (ACK_INCOMPLETE)
    {
        MockNvs nvs;
        GatewaySessionStore<MockNvs> store(nvs);
        CHECK(store.init(1000));
        CHECK(store.recordPulses("sessB", 4, 1500, 0));

        int acked = 0;
        CHECK(store.acknowledge("sessB", true, acked) == AckResult::Busy);
        CHECK(store.pendingPulses("sessB") == 4);

        nvs.failRemove = true;
        CHECK(store.acknowledge("sessB", false, acked) == AckResult::StorageError);
        CHECK(store.pendingPulses("sessB") == 4);

        nvs.failRemove = false;
        CHECK(store.acknowledge("sessB", false, acked) == AckResult::Ok);
        CHECK(acked == 4);
        CHECK(store.pendingPulses("sessB") == 0);
        CHECK(store.lifetimePulses() == 4);
    }

    // 3. Mid-window re-arm allowed when 16/16 slots full; new session rejected if all 16 are fresh
    {
        MockNvs nvs;
        GatewaySessionStore<MockNvs> store(nvs);
        CHECK(store.init(1000));
        uint64_t t0 = 1760000000000ULL;
        for (int i = 0; i < MAX_SLOTS; i++) {
            CHECK(store.recordPulses("s" + std::to_string(i), 1, (uint32_t)(1000 + i * 100), t0 + (uint64_t)i * 100));
        }
        CHECK(store.activeCount() == 16);
        CHECK(store.canAcceptSession("s15", 5000, t0 + 4000, "s15"));
        CHECK(!store.canAcceptSession("s_new", 5000, t0 + 4000, "s15"));

        // After 31 minutes, pressure TTL (30m) evicts stale slots except the active session
        uint32_t t31m = 1000 + 31U * 60U * 1000U;
        uint64_t m31m = t0 + 31ULL * 60ULL * 1000ULL;
        CHECK(store.canAcceptSession("s_new", t31m, m31m, "s0"));
        CHECK(store.hasSession("s0")); // active session was protected from eviction
    }

    // 4. Dual-clock NTP jump safety: pre-sync session (createdAtMasterMs == 0) must NOT be evicted on sync
    {
        MockNvs nvs;
        GatewaySessionStore<MockNvs> store(nvs);
        CHECK(store.init(1000));
        CHECK(store.recordPulses("pre_sync", 2, 2000, 0));
        // Wall clock suddenly syncs to year 2025 (1760000000000 ms) at monotonic t = 5000ms
        CHECK(store.evictExpired(5000, 1760000000000ULL, "") == 0);
        CHECK(store.pendingPulses("pre_sync") == 2);
        // Only evicted after monotonic 24h elapses
        CHECK(store.evictExpired(2000U + 24U * 3600U * 1000U, 1760000000000ULL + 24ULL * 3600ULL * 1000ULL, "") == 1);
        CHECK(store.pendingPulses("pre_sync") == 0);
    }

    // 5. NVS write retry + probeStorage recovery
    {
        MockNvs nvs;
        GatewaySessionStore<MockNvs> store(nvs);
        CHECK(store.init(1000));
        // 2 write failures still succeed within 3 retries inside recordPulses
        nvs.failWrites = 2;
        CHECK(store.recordPulses("retry_ok", 1, 1000, 0));
        CHECK(store.isStorageReady());
        CHECK(!store.hasUnpersisted());

        // >6 write failures exhaust immediate 3 retries for both life_pulses and rec_XX
        nvs.failWrites = 100;
        CHECK(store.recordPulses("retry_later", 2, 2000, 0));
        CHECK(!store.isStorageReady());
        CHECK(store.hasUnpersisted());
        CHECK(store.persistFailures() == 1);

        // Once flash recovers, tick() persists pending data and restores storageReady_
        nvs.failWrites = 0;
        store.tick(4000, 0, ""); // backoff 1s for slot (from 2000ms) and immediate for lifeDirty
        store.tick(8000, 0, "");
        CHECK(!store.hasUnpersisted());
        CHECK(store.isStorageReady());

        // Also test probeStorage() recovery when an unpersisted slot was acked while storageReady_ == false
        nvs.failWrites = 100;
        CHECK(store.recordPulses("probe_case", 1, 10000, 0));
        CHECK(!store.isStorageReady());
        int acked = 0;
        CHECK(store.acknowledge("probe_case", false, acked) == AckResult::Ok);
        CHECK(!store.isStorageReady());
        nvs.failWrites = 0;
        store.tick(16000, 0, ""); // lifeDirty flushes
        store.probeStorage(22000);
        CHECK(store.isStorageReady());
    }

    printf("session_store_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
