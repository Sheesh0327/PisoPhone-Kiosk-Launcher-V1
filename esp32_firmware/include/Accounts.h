#ifndef ACCOUNTS_H
#define ACCOUNTS_H

// Player accounts: the one master table of who has how much time left. Pure logic (no Arduino types), so the exact code that
// runs on the ESP32 is also tested on a PC (host_tests/accounts_test.cpp). Flash access lives in AccountStorage.cpp; this file
// only turns requests into table changes and the table into bytes and back.
//
// An account is a QR card: its number (the card's serial, 1 to 65535) is the account id, and holding the card is the login.
// There are no usernames and PINs. The player only gives the account a short display name after the first scan.
//
// Times are whole seconds. `nowS` is the box clock in seconds (0 while the box has no clock yet).
//
// A card's starter time is given ONCE. The table therefore keeps a permanent "redeemed" bit per serial that outlives the
// account itself: pruning an unused account or deleting one never makes its card eligible for the starter time again.

#include <cstdint>
#include <cstring>
#include <string>

namespace accounts {

static const size_t MAX_ACCOUNTS = 600;
static const uint32_t MAX_ID = 65535;
static const size_t NAME_MAX = 16;
static const size_t TX_RING = 64;
static const size_t REDEEMED_BYTES = (MAX_ID + 1) / 8;

// Pruning: an account with no time that nobody is using is deleted after this long.
static const uint32_t PRUNE_IDLE_S = 30UL * 24 * 3600;
static const uint32_t PRUNE_NEVER_FUNDED_S = 7UL * 24 * 3600;

enum class Result : uint8_t {
    OK = 0,
    DUPLICATE_TX, // already applied; nothing changed, the caller may treat it as success
    BAD_CARD,
    BAD_NAME,
    ACCOUNTS_FULL,
    NO_SUCH_ACCOUNT,
    ALREADY_SIGNED_IN,
    NOT_SIGNED_IN,
    INTERNAL,
};

struct Account {
    uint32_t id;             // the card's serial
    char name[NAME_MAX + 1]; // display name, empty until the player gives one
    uint32_t balanceSec;
    uint8_t signedInSlot; // 0 = nobody
    bool everFunded;      // has ever been given time
    uint32_t createdS;
    uint32_t lastActiveS;
};

// A display name: 1 to 16 of letters, digits, space, '_' and '-', not starting or ending with a space. The narrow character set
// keeps names safe to show on the dashboard and to carry in URLs and JSON without any escaping.
inline bool validName(const std::string& name) {
    if (name.empty() || name.size() > NAME_MAX) return false;
    if (name.front() == ' ' || name.back() == ' ') return false;
    for (char c : name) {
        bool ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == ' ' || c == '_' ||
                  c == '-';
        if (!ok) return false;
    }
    return true;
}

inline bool validId(uint32_t id) {
    return id >= 1 && id <= MAX_ID;
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

// Caps how many expensive operations (checking a card's signature takes a noticeable fraction of a second on the box) run per
// window, for the whole box. Without it anyone on the network could keep the CPU busy with scan attempts and starve the coin
// loop. Wrap-safe millis() arithmetic; one fixed window at a time.
class CostLimiter {
public:
    CostLimiter(uint32_t maxPerWindow, uint32_t windowMs) : max_(maxPerWindow), windowMs_(windowMs) {}

    bool allow(uint32_t nowMs) {
        if (!started_ || (uint32_t)(nowMs - windowStartMs_) >= windowMs_) {
            started_ = true;
            windowStartMs_ = nowMs;
            used_ = 0;
        }
        if (used_ >= max_) return false;
        used_++;
        return true;
    }

private:
    uint32_t max_;
    uint32_t windowMs_;
    uint32_t windowStartMs_ = 0;
    uint32_t used_ = 0;
    bool started_ = false;
};

class AccountTable {
public:
    AccountTable() { clear(); }

    void clear() {
        count_ = 0;
        txPos_ = 0;
        seq_ = 0;
        memset(txRing_, 0, sizeof(txRing_));
        memset(redeemed_, 0, sizeof(redeemed_));
        memset(rows_, 0, sizeof(rows_));
    }

    size_t count() const { return count_; }
    uint32_t seq() const { return seq_; }
    const Account& at(size_t i) const { return rows_[i]; }

    const Account* find(uint32_t id) const {
        int i = indexOf(id);
        return i < 0 ? nullptr : &rows_[i];
    }

    // The account signed in on this phone slot, or nullptr.
    const Account* findBySlot(uint8_t slot) const {
        if (slot == 0) return nullptr;
        for (size_t i = 0; i < count_; i++)
            if (rows_[i].signedInSlot == slot) return &rows_[i];
        return nullptr;
    }

    // True once a card's starter time has been given, for good (survives pruning and deletion of the account).
    bool isRedeemed(uint32_t id) const { return validId(id) && (redeemed_[id >> 3] & (1u << (id & 7))) != 0; }

    // What a scan of a valid card does to the table. `bonusSec` is the starter time printed on (and signed into) the card.
    //  - the account exists: nothing changes (the caller signs in);
    //  - first scan of this card ever: the account is created holding the starter time and the card is marked redeemed;
    //  - the card was redeemed before but its account is gone (pruned or deleted): an EMPTY account is created, no bonus.
    // `firstTime` tells which of the last two happened. Capacity is checked before anything changes, so a full table never
    // burns a card's one-time bonus.
    Result redeemCard(uint32_t id, uint32_t bonusSec, uint32_t nowS, bool& firstTime) {
        firstTime = false;
        if (!validId(id)) return Result::BAD_CARD;
        if (indexOf(id) >= 0) return Result::OK;
        if (count_ >= MAX_ACCOUNTS) prune(nowS);
        if (count_ >= MAX_ACCOUNTS) return Result::ACCOUNTS_FULL;

        Account a;
        memset(&a, 0, sizeof(a));
        a.id = id;
        a.createdS = nowS;
        a.lastActiveS = nowS;
        if (!isRedeemed(id)) {
            firstTime = true;
            a.balanceSec = bonusSec;
            a.everFunded = bonusSec > 0;
            redeemed_[id >> 3] |= (uint8_t)(1u << (id & 7));
        }
        rows_[count_++] = a;
        return Result::OK;
    }

    // Gives the account its display name (the player picks it after the first scan; they may change it later).
    Result setName(uint32_t id, const std::string& name) {
        int i = indexOf(id);
        if (i < 0) return Result::NO_SUCH_ACCOUNT;
        if (!validName(name)) return Result::BAD_NAME;
        memset(rows_[i].name, 0, sizeof(rows_[i].name));
        memcpy(rows_[i].name, name.c_str(), name.size());
        return Result::OK;
    }

    // One phone at a time: a second slot is refused until the first signs out.
    Result signIn(uint32_t id, uint8_t slot, uint32_t nowS) {
        int i = indexOf(id);
        if (i < 0) return Result::NO_SUCH_ACCOUNT;
        Account& a = rows_[i];
        if (slot == 0) return Result::INTERNAL;
        if (a.signedInSlot != 0 && a.signedInSlot != slot) return Result::ALREADY_SIGNED_IN;
        a.signedInSlot = slot;
        a.lastActiveS = nowS;
        return Result::OK;
    }

    Result signOut(uint32_t id, uint32_t nowS) {
        int i = indexOf(id);
        if (i < 0) return Result::NO_SUCH_ACCOUNT;
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
    Result creditSeconds(uint32_t id, uint32_t sec, const std::string& txId, uint32_t nowS) {
        int i = indexOf(id);
        if (i < 0) return Result::NO_SUCH_ACCOUNT;
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

    // The signed-in phone says how much time it has left. The balance only ever goes DOWN to that figure: more time can come
    // only through creditSeconds, so a phone cannot make time up.
    Result reportRemaining(uint32_t id, uint8_t slot, uint32_t remainingSec, uint32_t nowS) {
        int i = indexOf(id);
        if (i < 0) return Result::NO_SUCH_ACCOUNT;
        Account& a = rows_[i];
        if (slot == 0 || a.signedInSlot != slot) return Result::NOT_SIGNED_IN;
        if (remainingSec < a.balanceSec) a.balanceSec = remainingSec;
        a.lastActiveS = nowS;
        return Result::OK;
    }

    // Admin override: sets the balance exactly (a correction, not a phone report).
    Result setBalance(uint32_t id, uint32_t sec, uint32_t nowS) {
        int i = indexOf(id);
        if (i < 0) return Result::NO_SUCH_ACCOUNT;
        rows_[i].balanceSec = sec;
        if (sec > 0) rows_[i].everFunded = true;
        rows_[i].lastActiveS = nowS;
        return Result::OK;
    }

    // Admin change of the balance by `deltaSec` (negative removes time), kept within 0 and the maximum. Refused while the
    // player is signed in: their time is running on a phone, which an admin change here would not reach.
    Result adjustSeconds(uint32_t id, int64_t deltaSec, uint32_t nowS) {
        int i = indexOf(id);
        if (i < 0) return Result::NO_SUCH_ACCOUNT;
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

    // Deletes the account and its time. The card stays marked as redeemed: a deleted card cannot claim its starter time again,
    // so deleting is also how an admin revokes a card (scanning it later gives an empty account).
    Result deleteAccount(uint32_t id) {
        int i = indexOf(id);
        if (i < 0) return Result::NO_SUCH_ACCOUNT;
        removeAt((size_t)i);
        return Result::OK;
    }

    // Deletes accounts nobody can miss: no time, not signed in, and either untouched for PRUNE_IDLE_S or created and never given
    // any time for PRUNE_NEVER_FUNDED_S. An account with time, or one in use, is never deleted. Does nothing while the box has
    // no clock. A pruned card is not lost: scanning it again recreates an empty account (the starter time was already given).
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
    // header: "PACC" | u8 version | u32 seq | u16 count | u8 txPos | txRing[64] u64 | redeemed[8192] bits
    // rows:   u32 id | name[16] | u32 balance | u8 slot | u8 funded | u32 created | u32 lastActive
    // footer: u32 CRC-32 of everything before it
    static const uint8_t FORMAT_VERSION = 2;
    static const size_t ROW_BYTES = 4 + NAME_MAX + 4 + 1 + 1 + 4 + 4;
    static const size_t HEADER_BYTES = 4 + 1 + 4 + 2 + 1 + TX_RING * 8 + REDEEMED_BYTES;

    // Emits the bytes through emit(const uint8_t*, size_t) so a caller can stream them to a file without a second copy in
    // RAM. The sequence number is bumped so the newer of two files can be told apart.
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
        out(redeemed_, REDEEMED_BYTES);
        for (size_t i = 0; i < count_; i++) {
            const Account& a = rows_[i];
            put32(b, a.id);
            out(b, 4);
            uint8_t name[NAME_MAX];
            memset(name, 0, sizeof(name));
            memcpy(name, a.name, strnlen(a.name, NAME_MAX));
            out(name, NAME_MAX);
            put32(b, a.balanceSec);
            out(b, 4);
            b[0] = a.signedInSlot;
            b[1] = a.everFunded ? 1 : 0;
            out(b, 2);
            put32(b, a.createdS);
            out(b, 4);
            put32(b, a.lastActiveS);
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

    // Replaces the table with the contents of a saved file. Anything damaged (short, wrong magic or version, bad CRC,
    // impossible counts, ids or names) is refused and the table is left as it was.
    bool deserialize(const uint8_t* d, size_t n) {
        if (n < HEADER_BYTES + 4) return false;
        if (memcmp(d, "PACC", 4) != 0 || d[4] != FORMAT_VERSION) return false;
        size_t count = get16(d + 9);
        if (count > MAX_ACCOUNTS) return false;
        if (n != HEADER_BYTES + count * ROW_BYTES + 4) return false;
        if (crc32(d, n - 4) != get32(d + n - 4)) return false;
        if (d[11] >= TX_RING) return false;

        // Check every row before touching the table, so a damaged file never leaves it half loaded. (The table is large: it
        // must not be copied onto a task stack, so there is no temporary table.)
        const uint8_t* rows = d + HEADER_BYTES;
        for (size_t i = 0; i < count; i++) {
            const uint8_t* r = rows + i * ROW_BYTES;
            uint32_t id = get32(r);
            if (!validId(id)) return false;
            char name[NAME_MAX + 1];
            memcpy(name, r + 4, NAME_MAX);
            name[NAME_MAX] = 0;
            if (name[0] != 0 && !validName(name)) return false;
            for (size_t j = 0; j < i; j++)
                if (get32(rows + j * ROW_BYTES) == id) return false; // duplicate account
        }

        seq_ = get32(d + 5);
        txPos_ = d[11];
        const uint8_t* p = d + 12;
        for (size_t k = 0; k < TX_RING; k++, p += 8)
            txRing_[k] = get64(p);
        memcpy(redeemed_, p, REDEEMED_BYTES);
        p += REDEEMED_BYTES;
        memset(rows_, 0, sizeof(rows_));
        count_ = 0;
        for (size_t i = 0; i < count; i++) {
            Account& a = rows_[count_++];
            a.id = get32(p);
            p += 4;
            memcpy(a.name, p, NAME_MAX);
            a.name[NAME_MAX] = 0;
            p += NAME_MAX;
            a.balanceSec = get32(p);
            p += 4;
            a.signedInSlot = p[0];
            a.everFunded = p[1] != 0;
            p += 2;
            a.createdS = get32(p);
            p += 4;
            a.lastActiveS = get32(p);
            p += 4;
            // An account always has its redeemed bit set; repair it if a file ever says otherwise.
            redeemed_[a.id >> 3] |= (uint8_t)(1u << (a.id & 7));
        }
        return true;
    }

private:
    Account rows_[MAX_ACCOUNTS];
    size_t count_;
    uint64_t txRing_[TX_RING];
    uint8_t txPos_;
    uint32_t seq_;
    uint8_t redeemed_[REDEEMED_BYTES];

    int indexOf(uint32_t id) const {
        if (!validId(id)) return -1;
        for (size_t i = 0; i < count_; i++)
            if (rows_[i].id == id) return (int)i;
        return -1;
    }

    void removeAt(size_t i) {
        for (size_t k = i + 1; k < count_; k++)
            rows_[k - 1] = rows_[k];
        count_--;
        memset(&rows_[count_], 0, sizeof(Account));
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
