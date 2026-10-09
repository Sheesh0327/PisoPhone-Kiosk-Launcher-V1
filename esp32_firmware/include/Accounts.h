#ifndef ACCOUNTS_H
#define ACCOUNTS_H

// Player accounts: the one master table of who has how much time left. Pure logic (no Arduino types), so the exact
// code that runs on the ESP32 is also tested on a PC (host_tests/accounts_test.cpp). Flash access lives in
// AccountStorage.cpp; this file only turns requests into table changes and the table into bytes and back.
//
// Times are whole seconds. `nowS` is the box clock in seconds (0 while the box has no clock yet). A PIN is 4 to 6
// digits and is stored only as a salted PBKDF2-HMAC-SHA256 hash.

#include "CredCrypto.h"

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace accounts {

static const size_t MAX_ACCOUNTS = 300;
static const size_t USERNAME_MIN = 3;
static const size_t USERNAME_MAX = 16;
static const size_t PIN_MIN = 4;
static const size_t PIN_MAX = 6;
static const size_t SALT_BYTES = 8;
static const uint32_t PBKDF2_ITERATIONS = 10000;
static const size_t TX_RING = 64;

// Pruning: an account with no time that nobody is using is deleted after this long.
static const uint32_t PRUNE_IDLE_S = 30UL * 24 * 3600;
static const uint32_t PRUNE_NEVER_FUNDED_S = 7UL * 24 * 3600;

// Wrong-PIN lockout: from the 5th failure in a row the account is locked, 5 minutes, doubling each time, up to 1 hour.
static const uint8_t LOCK_AFTER_FAILURES = 5;
static const uint32_t LOCK_BASE_S = 300;
static const uint32_t LOCK_MAX_S = 3600;

enum class Result : uint8_t {
    OK = 0,
    DUPLICATE_TX, // already applied; nothing changed, the caller may treat it as success
    BAD_NAME,
    BAD_PIN_FORMAT,
    NAME_TAKEN,
    ACCOUNTS_FULL,
    NO_SUCH_USER,
    BAD_PIN,
    LOCKED,
    ALREADY_SIGNED_IN,
    NOT_SIGNED_IN,
    INTERNAL,
};

struct Account {
    char username[USERNAME_MAX + 1];
    uint8_t salt[SALT_BYTES];
    uint8_t hash[32];
    uint32_t balanceSec;
    uint8_t signedInSlot; // 0 = nobody
    uint8_t failCount;
    bool everFunded; // has ever been given time
    uint32_t createdS;
    uint32_t lastActiveS;
    uint32_t lockedUntilS;
};

inline bool validUsername(const std::string& name) {
    if (name.size() < USERNAME_MIN || name.size() > USERNAME_MAX) return false;
    for (char c : name) {
        bool ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
        if (!ok) return false;
    }
    return true;
}

inline bool validPin(const std::string& pin) {
    if (pin.size() < PIN_MIN || pin.size() > PIN_MAX) return false;
    for (char c : pin)
        if (c < '0' || c > '9') return false;
    return true;
}

// Usernames are case-insensitive: stored and compared in lowercase.
inline std::string normalizeUsername(const std::string& name) {
    std::string out(name);
    for (char& c : out)
        if (c >= 'A' && c <= 'Z') c = (char)(c - 'A' + 'a');
    return out;
}

inline uint64_t txHash(const std::string& txId) {
    uint64_t h = 1469598103934665603ULL; // FNV-1a
    for (unsigned char c : txId) {
        h ^= c;
        h *= 1099511628211ULL;
    }
    return h ? h : 1; // 0 marks an empty slot in the ring
}

inline uint32_t crc32(const uint8_t* d, size_t n, uint32_t crc = 0) {
    crc = ~crc;
    for (size_t i = 0; i < n; i++) {
        crc ^= d[i];
        for (int k = 0; k < 8; k++)
            crc = (crc >> 1) ^ ((crc & 1) ? 0xEDB88320u : 0u);
    }
    return ~crc;
}

inline uint32_t idleFor(uint32_t nowS, uint32_t sinceS) {
    return nowS > sinceS ? nowS - sinceS : 0; // a clock that moved back counts as "just now"
}

class AccountTable {
public:
    AccountTable() { clear(); }

    void clear() {
        count_ = 0;
        txPos_ = 0;
        seq_ = 0;
        memset(txRing_, 0, sizeof(txRing_));
    }

    size_t count() const { return count_; }
    uint32_t seq() const { return seq_; }
    const Account& at(size_t i) const { return rows_[i]; }

    const Account* find(const std::string& username) const {
        int i = indexOf(normalizeUsername(username));
        return i < 0 ? nullptr : &rows_[i];
    }

    // The account signed in on this phone slot, or nullptr.
    const Account* findBySlot(uint8_t slot) const {
        if (slot == 0) return nullptr;
        for (size_t i = 0; i < count_; i++)
            if (rows_[i].signedInSlot == slot) return &rows_[i];
        return nullptr;
    }

    Result create(const std::string& username, const std::string& pin, const uint8_t salt[SALT_BYTES], uint32_t nowS) {
        std::string name = normalizeUsername(username);
        if (!validUsername(name)) return Result::BAD_NAME;
        if (!validPin(pin)) return Result::BAD_PIN_FORMAT;
        if (indexOf(name) >= 0) return Result::NAME_TAKEN;
        if (count_ >= MAX_ACCOUNTS) prune(nowS);
        if (count_ >= MAX_ACCOUNTS) return Result::ACCOUNTS_FULL;

        Account a;
        memset(&a, 0, sizeof(a));
        memcpy(a.username, name.c_str(), name.size());
        memcpy(a.salt, salt, SALT_BYTES);
        if (!hashPin(pin, a.salt, a.hash)) return Result::INTERNAL;
        a.createdS = nowS;
        a.lastActiveS = nowS;
        rows_[count_++] = a;
        return Result::OK;
    }

    // Checks the PIN. Wrong guesses count; at the limit the account locks and the PIN is not even looked at until
    // the lock ends, so guessing is slow no matter how fast requests arrive.
    Result verifyPin(const std::string& username, const std::string& pin, uint32_t nowS) {
        int i = indexOf(normalizeUsername(username));
        if (i < 0) return Result::NO_SUCH_USER;
        Account& a = rows_[i];
        if (nowS < a.lockedUntilS) return Result::LOCKED;
        if (!validPin(pin)) return Result::BAD_PIN_FORMAT;
        uint8_t got[32];
        if (!hashPin(pin, a.salt, got)) return Result::INTERNAL;
        if (!credcrypto::constantTimeEquals(got, a.hash, 32)) {
            if (a.failCount < 255) a.failCount++;
            if (a.failCount >= LOCK_AFTER_FAILURES) {
                uint32_t lock = LOCK_BASE_S;
                for (uint8_t k = LOCK_AFTER_FAILURES; k < a.failCount && lock < LOCK_MAX_S; k++)
                    lock *= 2;
                if (lock > LOCK_MAX_S) lock = LOCK_MAX_S;
                a.lockedUntilS = nowS + lock;
            }
            return Result::BAD_PIN;
        }
        a.failCount = 0;
        a.lockedUntilS = 0;
        return Result::OK;
    }

    // The caller verifies the PIN first. One phone at a time: a second slot is refused until the first signs out.
    Result signIn(const std::string& username, uint8_t slot, uint32_t nowS) {
        int i = indexOf(normalizeUsername(username));
        if (i < 0) return Result::NO_SUCH_USER;
        Account& a = rows_[i];
        if (slot == 0) return Result::INTERNAL;
        if (a.signedInSlot != 0 && a.signedInSlot != slot) return Result::ALREADY_SIGNED_IN;
        a.signedInSlot = slot;
        a.lastActiveS = nowS;
        return Result::OK;
    }

    Result signOut(const std::string& username, uint32_t nowS) {
        int i = indexOf(normalizeUsername(username));
        if (i < 0) return Result::NO_SUCH_USER;
        rows_[i].signedInSlot = 0;
        rows_[i].lastActiveS = nowS;
        return Result::OK;
    }

    // For a phone that stopped answering: whoever was signed in on that slot is signed out, balance as last reported.
    size_t signOutSlot(uint8_t slot, uint32_t nowS) {
        size_t n = 0;
        for (size_t i = 0; i < count_; i++) {
            if (slot != 0 && rows_[i].signedInSlot == slot) {
                rows_[i].signedInSlot = 0;
                rows_[i].lastActiveS = nowS;
                n++;
            }
        }
        return n;
    }

    // Adds time (a coin, an admin top-up). Each txId counts once, however often the box retries it.
    Result creditSeconds(const std::string& username, uint32_t sec, const std::string& txId, uint32_t nowS) {
        int i = indexOf(normalizeUsername(username));
        if (i < 0) return Result::NO_SUCH_USER;
        if (!txId.empty()) {
            uint64_t h = txHash(txId);
            for (size_t k = 0; k < TX_RING; k++)
                if (txRing_[k] == h) return Result::DUPLICATE_TX;
            txRing_[txPos_] = h;
            txPos_ = (uint8_t)((txPos_ + 1) % TX_RING);
        }
        Account& a = rows_[i];
        a.balanceSec = (sec > UINT32_MAX - a.balanceSec) ? UINT32_MAX : a.balanceSec + sec;
        if (sec > 0) a.everFunded = true;
        a.lastActiveS = nowS;
        return Result::OK;
    }

    // The signed-in phone says how much time it has left. The balance only ever goes DOWN to that figure: more time
    // can come only through creditSeconds, so a phone cannot make time up.
    Result reportRemaining(const std::string& username, uint8_t slot, uint32_t remainingSec, uint32_t nowS) {
        int i = indexOf(normalizeUsername(username));
        if (i < 0) return Result::NO_SUCH_USER;
        Account& a = rows_[i];
        if (slot == 0 || a.signedInSlot != slot) return Result::NOT_SIGNED_IN;
        if (remainingSec < a.balanceSec) a.balanceSec = remainingSec;
        a.lastActiveS = nowS;
        return Result::OK;
    }

    // Admin override: sets the balance exactly (a correction, not a phone report).
    Result setBalance(const std::string& username, uint32_t sec, uint32_t nowS) {
        int i = indexOf(normalizeUsername(username));
        if (i < 0) return Result::NO_SUCH_USER;
        rows_[i].balanceSec = sec;
        if (sec > 0) rows_[i].everFunded = true;
        rows_[i].lastActiveS = nowS;
        return Result::OK;
    }

    // Admin change of the balance by `deltaSec` (negative removes time), kept within 0 and the maximum. Refused while the
    // player is signed in: their time is running on a phone, which an admin change here would not reach.
    Result adjustSeconds(const std::string& username, int64_t deltaSec, uint32_t nowS) {
        int i = indexOf(normalizeUsername(username));
        if (i < 0) return Result::NO_SUCH_USER;
        Account& a = rows_[i];
        if (a.signedInSlot != 0) return Result::ALREADY_SIGNED_IN;
        int64_t next = (int64_t)a.balanceSec + deltaSec;
        if (next < 0) next = 0;
        if (next > (int64_t)UINT32_MAX) next = UINT32_MAX;
        a.balanceSec = (uint32_t)next;
        if (deltaSec > 0) a.everFunded = true;
        a.lastActiveS = nowS;
        return Result::OK;
    }

    // Clears the wrong-PIN count and any lock (the player forgot nothing, someone else guessed, or the admin trusts them).
    Result unlock(const std::string& username) {
        int i = indexOf(normalizeUsername(username));
        if (i < 0) return Result::NO_SUCH_USER;
        rows_[i].failCount = 0;
        rows_[i].lockedUntilS = 0;
        return Result::OK;
    }

    Result deleteAccount(const std::string& username) {
        int i = indexOf(normalizeUsername(username));
        if (i < 0) return Result::NO_SUCH_USER;
        removeAt((size_t)i);
        return Result::OK;
    }

    // Deletes accounts nobody can miss: no time, not signed in, and either untouched for PRUNE_IDLE_S or created and
    // never given any time for PRUNE_NEVER_FUNDED_S. An account with time, or one in use, is never deleted. Does
    // nothing while the box has no clock.
    size_t prune(uint32_t nowS) {
        if (nowS == 0) return 0;
        size_t removed = 0;
        for (size_t i = 0; i < count_;) {
            const Account& a = rows_[i];
            bool unused = a.balanceSec == 0 && a.signedInSlot == 0;
            bool stale = idleFor(nowS, a.lastActiveS) >= PRUNE_IDLE_S;
            bool neverFunded = !a.everFunded && idleFor(nowS, a.createdS) >= PRUNE_NEVER_FUNDED_S;
            if (unused && (stale || neverFunded)) {
                removeAt(i);
                removed++;
            } else {
                i++;
            }
        }
        return removed;
    }

    // ---- storage format (little-endian, field by field, so it never depends on struct padding) ----
    // header: "PACC" | u8 version | u32 seq | u16 count | u8 txPos | txRing[64] u64
    // rows:   username[16] | salt[8] | hash[32] | u32 balance | u8 slot | u8 fails | u8 funded | u32 created |
    //         u32 lastActive | u32 lockedUntil
    // footer: u32 CRC-32 of everything before it
    static const uint8_t FORMAT_VERSION = 1;
    static const size_t ROW_BYTES = USERNAME_MAX + SALT_BYTES + 32 + 4 + 1 + 1 + 1 + 4 + 4 + 4;

    // Emits the bytes through emit(const uint8_t*, size_t) so a caller can stream them to a file without a second
    // copy in RAM. The sequence number is bumped so the newer of two files can be told apart.
    template <typename Emit> void serialize(Emit emit) {
        seq_++;
        uint32_t crc = 0;
        auto out = [&](const uint8_t* d, size_t n) {
            crc = crc32(d, n, crc);
            emit(d, n);
        };
        uint8_t b[8];
        out((const uint8_t*)"PACC", 4);
        b[0] = FORMAT_VERSION;
        out(b, 1);
        put32(b, seq_);
        out(b, 4);
        put16(b, (uint16_t)count_);
        out(b, 2);
        b[0] = txPos_;
        out(b, 1);
        for (size_t k = 0; k < TX_RING; k++) {
            put64(b, txRing_[k]);
            out(b, 8);
        }
        for (size_t i = 0; i < count_; i++) {
            const Account& a = rows_[i];
            uint8_t name[USERNAME_MAX];
            memset(name, 0, sizeof(name));
            memcpy(name, a.username, strnlen(a.username, USERNAME_MAX));
            out(name, USERNAME_MAX);
            out(a.salt, SALT_BYTES);
            out(a.hash, 32);
            put32(b, a.balanceSec);
            out(b, 4);
            b[0] = a.signedInSlot;
            b[1] = a.failCount;
            b[2] = a.everFunded ? 1 : 0;
            out(b, 3);
            put32(b, a.createdS);
            out(b, 4);
            put32(b, a.lastActiveS);
            out(b, 4);
            put32(b, a.lockedUntilS);
            out(b, 4);
        }
        uint8_t foot[4];
        put32(foot, crc);
        emit(foot, 4);
    }

    // The sequence number of a saved file, or false if it is not one of ours (a cheap look before loading it).
    static bool peekSeq(const uint8_t* d, size_t n, uint32_t& seq) {
        if (n < 9 || memcmp(d, "PACC", 4) != 0 || d[4] != FORMAT_VERSION) return false;
        seq = get32(d + 5);
        return true;
    }

    // Replaces the table with the contents of a saved file. Anything damaged (short, wrong magic or version, bad
    // CRC, impossible counts or names) is refused and the table is left as it was.
    bool deserialize(const uint8_t* d, size_t n) {
        const size_t head = 4 + 1 + 4 + 2 + 1 + TX_RING * 8;
        if (n < head + 4) return false;
        if (memcmp(d, "PACC", 4) != 0 || d[4] != FORMAT_VERSION) return false;
        size_t count = get16(d + 9);
        if (count > MAX_ACCOUNTS) return false;
        if (n != head + count * ROW_BYTES + 4) return false;
        if (crc32(d, n - 4) != get32(d + n - 4)) return false;

        // Check every row before touching the table, so a damaged file never leaves it half loaded. (The table is
        // about 25 KB: it must not be copied onto a task stack, so there is no temporary table.)
        const uint8_t* rows = d + head;
        for (size_t i = 0; i < count; i++) {
            const uint8_t* r = rows + i * ROW_BYTES;
            char name[USERNAME_MAX + 1];
            memcpy(name, r, USERNAME_MAX);
            name[USERNAME_MAX] = 0;
            if (!validUsername(name)) return false;
            for (size_t j = 0; j < i; j++)
                if (memcmp(rows + j * ROW_BYTES, r, USERNAME_MAX) == 0) return false; // duplicate name
        }
        if (d[11] >= TX_RING) return false;

        seq_ = get32(d + 5);
        txPos_ = d[11];
        const uint8_t* p = d + 12;
        for (size_t k = 0; k < TX_RING; k++, p += 8)
            txRing_[k] = get64(p);
        count_ = 0;
        for (size_t i = 0; i < count; i++) {
            Account& a = rows_[count_++];
            memset(&a, 0, sizeof(a));
            memcpy(a.username, p, USERNAME_MAX);
            p += USERNAME_MAX;
            memcpy(a.salt, p, SALT_BYTES);
            p += SALT_BYTES;
            memcpy(a.hash, p, 32);
            p += 32;
            a.balanceSec = get32(p);
            p += 4;
            a.signedInSlot = p[0];
            a.failCount = p[1];
            a.everFunded = p[2] != 0;
            p += 3;
            a.createdS = get32(p);
            p += 4;
            a.lastActiveS = get32(p);
            p += 4;
            a.lockedUntilS = get32(p);
            p += 4;
        }
        memset(&rows_[count_], 0, (MAX_ACCOUNTS - count_) * sizeof(Account));
        return true;
    }

private:
    Account rows_[MAX_ACCOUNTS];
    size_t count_;
    uint64_t txRing_[TX_RING];
    uint8_t txPos_;
    uint32_t seq_;

    int indexOf(const std::string& name) const {
        if (!validUsername(name)) return -1;
        for (size_t i = 0; i < count_; i++)
            if (strcmp(rows_[i].username, name.c_str()) == 0) return (int)i;
        return -1;
    }

    void removeAt(size_t i) {
        for (size_t k = i + 1; k < count_; k++)
            rows_[k - 1] = rows_[k];
        count_--;
        memset(&rows_[count_], 0, sizeof(Account));
    }

    static bool hashPin(const std::string& pin, const uint8_t salt[SALT_BYTES], uint8_t out[32]) {
        std::vector<uint8_t> s(salt, salt + SALT_BYTES);
        return credcrypto::pbkdf2Sha256(pin, s, PBKDF2_ITERATIONS, out);
    }

    static void put16(uint8_t* b, uint16_t v) {
        b[0] = (uint8_t)v;
        b[1] = (uint8_t)(v >> 8);
    }
    static void put32(uint8_t* b, uint32_t v) {
        for (int k = 0; k < 4; k++)
            b[k] = (uint8_t)(v >> (8 * k));
    }
    static void put64(uint8_t* b, uint64_t v) {
        for (int k = 0; k < 8; k++)
            b[k] = (uint8_t)(v >> (8 * k));
    }
    static uint16_t get16(const uint8_t* b) { return (uint16_t)(b[0] | (b[1] << 8)); }
    static uint32_t get32(const uint8_t* b) {
        uint32_t v = 0;
        for (int k = 3; k >= 0; k--)
            v = (v << 8) | b[k];
        return v;
    }
    static uint64_t get64(const uint8_t* b) {
        uint64_t v = 0;
        for (int k = 7; k >= 0; k--)
            v = (v << 8) | b[k];
        return v;
    }
};

} // namespace accounts

#endif // ACCOUNTS_H
