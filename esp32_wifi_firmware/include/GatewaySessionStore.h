#ifndef GATEWAY_SESSION_STORE_H
#define GATEWAY_SESSION_STORE_H

// Pure C++ (no Arduino types) durable payment store for PisoWiFi gateway sessions.
// Manages up to 16 session slots ("rec_00".."rec_15") and a monotonic "life_pulses" counter in NVS ("pay_queue").
// Tested in host_tests/session_store_test.cpp.

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>

namespace sessionstore {

static const char* const NVS_PAY_NAMESPACE = "pay_queue";
static const char* const KEY_LIFE_PULSES = "life_pulses";
static const char* const KEY_PROBE = "probe";

static const int MAX_SLOTS = 16;
static const int PRESSURE_THRESHOLD = 14;
static const uint32_t RECORD_MAGIC = 0x50574631UL; // "PWF1"
static const size_t MAX_SESSION_LEN = 64;

static const uint64_t TTL_NORMAL_MS = 24ULL * 60ULL * 60ULL * 1000ULL;
static const uint64_t TTL_PRESSURE_MS = 30ULL * 60ULL * 1000ULL;
static const uint32_t PROBE_INTERVAL_MS = 5000UL;
static const uint32_t EVICT_INTERVAL_MS = 60000UL;
static const int WRITE_RETRIES = 3;

struct SessionRecord {
    uint32_t magic = 0;
    char session[MAX_SESSION_LEN + 1] = {};
    int32_t pulses = 0;
    uint64_t createdAtMasterMs = 0;
};

enum class AckResult { Ok, Busy, StorageError };

template <class Store> class GatewaySessionStore {
public:
    explicit GatewaySessionStore(Store& store) : store_(store) { resetInMemory(); }

    bool init(uint32_t nowMs) {
        resetInMemory();
        if (!store_.begin(NVS_PAY_NAMESPACE, false)) {
            storageReady_ = false;
            return false;
        }
        storageReady_ = true;
        lifePulses_ = store_.getULong(KEY_LIFE_PULSES, 0);

        for (int i = 0; i < MAX_SLOTS; i++) {
            char key[8];
            slotKey(i, key, sizeof(key));
            size_t len = store_.getBytesLength(key);
            if (len == 0) continue;

            SessionRecord rec{};
            if (len == sizeof(rec) && store_.getBytes(key, &rec, sizeof(rec)) == sizeof(rec) && validRecord(rec)) {
                records_[i] = rec;
                slotUsed_[i] = true;
                slotPersisted_[i] = true;
                firstSeenMs_[i] = nowMs;
                activeCount_++;
            } else {
                store_.remove(key);
            }
        }
        store_.end();
        return true;
    }

    bool hasSession(const std::string& session) const { return findSlot(session) >= 0; }

    int pendingPulses(const std::string& session) const {
        int idx = findSlot(session);
        return idx >= 0 ? (int)records_[idx].pulses : 0;
    }

    uint32_t lifetimePulses() const { return lifePulses_; }
    bool isStorageReady() const { return storageReady_; }
    int activeCount() const { return activeCount_; }

    bool hasUnpersisted() const {
        if (lifeDirty_) return true;
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (slotUsed_[i] && !slotPersisted_[i]) return true;
        }
        return false;
    }

    uint32_t persistFailures() const { return persistFailures_; }
    uint32_t ackedCount() const { return ackedCount_; }
    uint32_t evictedCount() const { return evictedCount_; }

    // Always allows re-arming a session that already owns a slot, even at 16/16 capacity.
    // For new sessions when full, runs TTL eviction first; returns false if all 16 slots remain fresh.
    bool canAcceptSession(const std::string& session, uint32_t nowMs, uint64_t masterNowMs,
                          const std::string& activeSession) {
        if (!storageReady_) return false;
        if (hasSession(session)) return true;
        if (activeCount_ < MAX_SLOTS) return true;
        evictExpired(nowMs, masterNowMs, activeSession);
        return activeCount_ < MAX_SLOTS;
    }

    bool recordPulses(const std::string& session, int count, uint32_t nowMs, uint64_t masterNowMs) {
        if (session.empty() || session.size() > MAX_SESSION_LEN || count <= 0) return false;

        int idx = findSlot(session);
        if (idx < 0) {
            if (activeCount_ >= MAX_SLOTS) evictExpired(nowMs, masterNowMs, session);
            for (int i = 0; i < MAX_SLOTS; i++) {
                if (!slotUsed_[i]) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) return false;
            records_[idx] = SessionRecord{};
            records_[idx].magic = RECORD_MAGIC;
            std::memcpy(records_[idx].session, session.data(), session.size());
            records_[idx].session[session.size()] = '\0';
            records_[idx].pulses = count;
            records_[idx].createdAtMasterMs = masterNowMs;
            slotUsed_[idx] = true;
            slotPersisted_[idx] = false;
            firstSeenMs_[idx] = nowMs;
            lastPersistAttemptMs_[idx] = nowMs;
            persistRetries_[idx] = 0;
            activeCount_++;
        } else {
            records_[idx].pulses += count;
            slotPersisted_[idx] = false;
            lastPersistAttemptMs_[idx] = nowMs;
            persistRetries_[idx] = 0;
        }

        lifePulses_ += (uint32_t)count;
        lifeDirty_ = true;

        bool lifeOk = writeLifeWithRetries();
        bool slotOk = writeSlotWithRetries(idx);
        if (lifeOk) lifeDirty_ = false;
        if (slotOk) slotPersisted_[idx] = true;
        if (lifeOk && slotOk) {
            storageReady_ = true;
        } else {
            storageReady_ = false;
            persistFailures_++;
            if (!slotOk) persistRetries_[idx] = 1;
        }
        return true;
    }

    AckResult acknowledge(const std::string& session, bool sessionBusy, int& acknowledgedOut) {
        acknowledgedOut = 0;
        if (sessionBusy) return AckResult::Busy;

        int idx = findSlot(session);
        if (idx < 0) return AckResult::Ok;

        if (!eraseSlot(idx)) return AckResult::StorageError;

        acknowledgedOut = (int)records_[idx].pulses;
        clearSlot(idx);
        ackedCount_++;
        return AckResult::Ok;
    }

    int evictExpired(uint32_t nowMs, uint64_t masterNowMs, const std::string& activeSession) {
        bool underPressure = activeCount_ >= PRESSURE_THRESHOLD;
        uint64_t ttl = underPressure ? TTL_PRESSURE_MS : TTL_NORMAL_MS;
        int evicted = 0;

        for (int i = 0; i < MAX_SLOTS; i++) {
            if (!slotUsed_[i]) continue;
            if (!activeSession.empty() && activeSession == records_[i].session) continue;

            uint64_t age = (uint64_t)(uint32_t)(nowMs - firstSeenMs_[i]);
            uint64_t recTs = records_[i].createdAtMasterMs;
            // Only use wall-clock elapsed time when both timestamps are post-sync (prevents NTP jump eviction).
            if (recTs > 0 && masterNowMs > recTs) {
                uint64_t wallAge = masterNowMs - recTs;
                if (wallAge > age) age = wallAge;
            }
            if (age < ttl) continue;

            if (slotPersisted_[i] && !eraseSlot(i)) continue;
            clearSlot(i);
            evictedCount_++;
            evicted++;
        }
        return evicted;
    }

    bool probeStorage(uint32_t nowMs) {
        if (storageReady_ || hasUnpersisted()) return storageReady_;
        if (hasProbed_ && (uint32_t)(nowMs - lastProbeMs_) < PROBE_INTERVAL_MS) return false;
        hasProbed_ = true;
        lastProbeMs_ = nowMs;

        bool ok = false;
        if (store_.begin(NVS_PAY_NAMESPACE, false)) {
            ok = store_.putULong(KEY_PROBE, nowMs) > 0;
            if (ok) store_.remove(KEY_PROBE);
            store_.end();
        }
        if (ok) storageReady_ = true;
        return storageReady_;
    }

    void tick(uint32_t nowMs, uint64_t masterNowMs, const std::string& activeSession) {
        bool wroteAny = false;
        // 1. Retry unpersisted life_pulses / session records with exponential backoff.
        if (lifeDirty_) {
            uint8_t shift = lifeRetries_ > 5 ? 5 : lifeRetries_;
            uint32_t backoff = 1000UL << shift;
            if ((int32_t)(nowMs - (lastLifeAttemptMs_ + backoff)) >= 0) {
                lastLifeAttemptMs_ = nowMs;
                if (writeLifeOnce()) {
                    lifeDirty_ = false;
                    lifeRetries_ = 0;
                    wroteAny = true;
                } else if (lifeRetries_ < 10) {
                    lifeRetries_++;
                }
            }
        }

        for (int i = 0; i < MAX_SLOTS; i++) {
            if (!slotUsed_[i] || slotPersisted_[i]) continue;
            uint8_t shift = persistRetries_[i] > 5 ? 5 : persistRetries_[i];
            uint32_t backoff = 1000UL << shift;
            if ((int32_t)(nowMs - (lastPersistAttemptMs_[i] + backoff)) >= 0) {
                lastPersistAttemptMs_[i] = nowMs;
                if (writeSlotOnce(i)) {
                    slotPersisted_[i] = true;
                    persistRetries_[i] = 0;
                    wroteAny = true;
                } else if (persistRetries_[i] < 10) {
                    persistRetries_[i]++;
                }
            }
        }

        if (!storageReady_ && !hasUnpersisted()) {
            if (wroteAny) {
                storageReady_ = true;
            } else {
                probeStorage(nowMs);
            }
        }

        // 2. Periodic TTL eviction.
        if (!hasEvictTick_ || (uint32_t)(nowMs - lastEvictTickMs_) >= EVICT_INTERVAL_MS) {
            hasEvictTick_ = true;
            lastEvictTickMs_ = nowMs;
            evictExpired(nowMs, masterNowMs, activeSession);
        }
    }

    static void slotKey(int index, char* buf, size_t len) { snprintf(buf, len, "rec_%02d", index); }

private:
    static bool validRecord(const SessionRecord& rec) {
        if (rec.magic != RECORD_MAGIC || rec.session[0] == '\0' || rec.pulses <= 0) return false;
        for (size_t i = 0; i <= MAX_SESSION_LEN; i++) {
            if (rec.session[i] == '\0') return true;
        }
        return false;
    }

    int findSlot(const std::string& session) const {
        if (session.empty()) return -1;
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (slotUsed_[i] && session == records_[i].session) return i;
        }
        return -1;
    }

    void resetInMemory() {
        for (int i = 0; i < MAX_SLOTS; i++)
            records_[i] = SessionRecord{};
        std::memset(slotUsed_, 0, sizeof(slotUsed_));
        std::memset(slotPersisted_, 0, sizeof(slotPersisted_));
        std::memset(firstSeenMs_, 0, sizeof(firstSeenMs_));
        std::memset(lastPersistAttemptMs_, 0, sizeof(lastPersistAttemptMs_));
        std::memset(persistRetries_, 0, sizeof(persistRetries_));
        activeCount_ = 0;
        lifePulses_ = 0;
        lifeDirty_ = false;
        lifeRetries_ = 0;
        lastLifeAttemptMs_ = 0;
        storageReady_ = false;
        hasProbed_ = false;
        lastProbeMs_ = 0;
        hasEvictTick_ = false;
        lastEvictTickMs_ = 0;
    }

    void clearSlot(int idx) {
        records_[idx] = SessionRecord{};
        if (slotUsed_[idx] && activeCount_ > 0) activeCount_--;
        slotUsed_[idx] = false;
        slotPersisted_[idx] = false;
        firstSeenMs_[idx] = 0;
        lastPersistAttemptMs_[idx] = 0;
        persistRetries_[idx] = 0;
    }

    bool writeLifeOnce() {
        if (!store_.begin(NVS_PAY_NAMESPACE, false)) return false;
        bool ok = store_.putULong(KEY_LIFE_PULSES, lifePulses_) > 0;
        store_.end();
        return ok;
    }

    bool writeLifeWithRetries() {
        for (int attempt = 0; attempt < WRITE_RETRIES; attempt++) {
            if (writeLifeOnce()) return true;
        }
        return false;
    }

    bool writeSlotOnce(int idx) {
        if (!store_.begin(NVS_PAY_NAMESPACE, false)) return false;
        char key[8];
        slotKey(idx, key, sizeof(key));
        bool ok = store_.putBytes(key, &records_[idx], sizeof(SessionRecord)) == sizeof(SessionRecord);
        store_.end();
        return ok;
    }

    bool writeSlotWithRetries(int idx) {
        for (int attempt = 0; attempt < WRITE_RETRIES; attempt++) {
            if (writeSlotOnce(idx)) return true;
        }
        return false;
    }

    bool eraseSlot(int idx) {
        if (!store_.begin(NVS_PAY_NAMESPACE, false)) return false;
        char key[8];
        slotKey(idx, key, sizeof(key));
        bool ok = !store_.isKey(key) || store_.remove(key);
        store_.end();
        return ok;
    }

    Store& store_;
    SessionRecord records_[MAX_SLOTS];
    bool slotUsed_[MAX_SLOTS];
    bool slotPersisted_[MAX_SLOTS];
    uint32_t firstSeenMs_[MAX_SLOTS];
    uint32_t lastPersistAttemptMs_[MAX_SLOTS];
    uint8_t persistRetries_[MAX_SLOTS];
    int activeCount_ = 0;

    uint32_t lifePulses_ = 0;
    bool lifeDirty_ = false;
    uint8_t lifeRetries_ = 0;
    uint32_t lastLifeAttemptMs_ = 0;

    bool storageReady_ = false;
    bool hasProbed_ = false;
    uint32_t lastProbeMs_ = 0;
    bool hasEvictTick_ = false;
    uint32_t lastEvictTickMs_ = 0;

    uint32_t persistFailures_ = 0;
    uint32_t ackedCount_ = 0;
    uint32_t evictedCount_ = 0;
};

} // namespace sessionstore

#ifdef ARDUINO
#include <Arduino.h>
#include <Preferences.h>

class NvsPreferencesStore {
public:
    bool begin(const char* ns, bool readOnly) { return prefs_.begin(ns, readOnly); }
    void end() { prefs_.end(); }
    size_t getBytesLength(const char* key) { return prefs_.getBytesLength(key); }
    size_t getBytes(const char* key, void* buf, size_t len) { return prefs_.getBytes(key, buf, len); }
    size_t putBytes(const char* key, const void* buf, size_t len) { return prefs_.putBytes(key, buf, len); }
    uint32_t getULong(const char* key, uint32_t def) { return prefs_.getULong(key, def); }
    size_t putULong(const char* key, uint32_t val) { return prefs_.putULong(key, val); }
    bool isKey(const char* key) { return prefs_.isKey(key); }
    bool remove(const char* key) { return prefs_.remove(key); }
    bool clear() { return prefs_.clear(); }

private:
    Preferences prefs_;
};

void initGatewaySessionStore();
sessionstore::GatewaySessionStore<NvsPreferencesStore>& gatewayStore();
bool isPaymentStorageReady();
bool hasUnpersistedPayments();
int getPendingPaymentCount();
bool canPerformRebootOrOta();
void processGatewaySessionStore();
#endif

#endif // GATEWAY_SESSION_STORE_H
