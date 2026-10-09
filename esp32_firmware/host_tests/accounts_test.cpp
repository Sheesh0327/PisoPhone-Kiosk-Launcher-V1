#include "../include/Accounts.h"

#include <cstdio>
#include <memory>
#include <vector>

using namespace accounts;

static int failures = 0;
#define CHECK(c)                                                                                                       \
    do {                                                                                                               \
        if (!(c)) {                                                                                                    \
            printf("FAIL line %d: %s\n", __LINE__, #c);                                                                \
            failures++;                                                                                                \
        }                                                                                                              \
    } while (0)

static const uint32_t DAY = 24 * 3600;
static const uint32_t T0 = 1000000;
static const uint32_t BONUS = 10800; // the 3-hour starter card

static std::vector<uint8_t> save(AccountTable& t) {
    std::vector<uint8_t> out;
    t.serialize([&](const uint8_t* d, size_t n) { out.insert(out.end(), d, d + n); });
    return out;
}

// Scans a card the way the box does: redeem, then sign in on `slot`.
static Result scan(AccountTable& t, uint32_t id, uint8_t slot, uint32_t nowS, bool* firstTime = nullptr) {
    bool first = false;
    Result r = t.redeemCard(id, BONUS, nowS, first);
    if (firstTime) *firstTime = first;
    if (r != Result::OK) return r;
    return t.signIn(id, slot, nowS);
}

int main() {
    auto tp = std::make_unique<AccountTable>();
    AccountTable& t = *tp;

    // ---- the first scan of a card gives the starter time, once ----
    bool first = false;
    CHECK(scan(t, 42, 1, T0, &first) == Result::OK && first);
    CHECK(t.find(42) != nullptr && t.find(42)->balanceSec == BONUS && t.find(42)->everFunded);
    CHECK(t.find(42)->signedInSlot == 1 && t.find(42)->name[0] == 0); // no name yet
    CHECK(t.isRedeemed(42) && !t.isRedeemed(43));
    CHECK(t.signOut(42, T0) == Result::OK);
    CHECK(scan(t, 42, 2, T0, &first) == Result::OK && !first); // a second scan only signs in: no second bonus
    CHECK(t.find(42)->balanceSec == BONUS);
    CHECK(t.count() == 1);

    // ---- cards that are not valid ids ----
    CHECK(t.redeemCard(0, BONUS, T0, first) == Result::BAD_CARD);
    CHECK(t.redeemCard(65536, BONUS, T0, first) == Result::BAD_CARD);
    CHECK(t.redeemCard(MAX_ID, BONUS, T0, first) == Result::OK && first);
    CHECK(t.find(0) == nullptr && t.find(65536) == nullptr);
    CHECK(t.count() == 2);

    // ---- one phone at a time ----
    CHECK(t.signIn(42, 2, T0) == Result::OK); // same slot again is fine
    CHECK(t.signIn(42, 3, T0) == Result::ALREADY_SIGNED_IN);
    CHECK(scan(t, 42, 3, T0) == Result::ALREADY_SIGNED_IN);
    CHECK(t.signOut(42, T0) == Result::OK);
    CHECK(t.signIn(42, 3, T0) == Result::OK);
    CHECK(t.signIn(42, 0, T0) == Result::INTERNAL);
    CHECK(t.signIn(777, 1, T0) == Result::NO_SUCH_ACCOUNT);
    CHECK(t.findBySlot(3) != nullptr && t.findBySlot(3)->id == 42 && t.findBySlot(9) == nullptr &&
          t.findBySlot(0) == nullptr);

    // ---- names ----
    CHECK(t.setName(42, "Juan") == Result::OK && std::string(t.find(42)->name) == "Juan");
    CHECK(t.setName(42, "Ana Maria_2-b") == Result::OK && std::string(t.find(42)->name) == "Ana Maria_2-b");
    CHECK(t.setName(42, "Pedro") == Result::OK &&
          std::string(t.find(42)->name) == "Pedro"); // a shorter name fully replaces
    CHECK(t.setName(42, "") == Result::BAD_NAME);
    CHECK(t.setName(42, " lead") == Result::BAD_NAME && t.setName(42, "trail ") == Result::BAD_NAME);
    CHECK(t.setName(42, "seventeen chars!!") == Result::BAD_NAME);
    CHECK(t.setName(42, "a\"b") == Result::BAD_NAME && t.setName(42, "<b>") == Result::BAD_NAME);
    CHECK(t.setName(42, "a:b") == Result::BAD_NAME && t.setName(42, "a&b") == Result::BAD_NAME);
    CHECK(t.setName(42, "caf\xC3\xA9") == Result::BAD_NAME); // not ASCII
    CHECK(std::string(t.find(42)->name) == "Pedro");         // refused names change nothing
    CHECK(t.setName(999, "Juan") == Result::NO_SUCH_ACCOUNT);

    // ---- credits are idempotent by tx id ----
    CHECK(t.creditSeconds(42, 600, "tx-1", T0) == Result::OK);
    CHECK(t.creditSeconds(42, 600, "tx-1", T0) == Result::DUPLICATE_TX); // box retry
    CHECK(t.find(42)->balanceSec == BONUS + 600);
    CHECK(t.creditSeconds(42, 0xFFFFFFF0u, "tx-3", T0) == Result::OK &&
          t.find(42)->balanceSec == UINT32_MAX); // saturates
    CHECK(t.setBalance(42, 660, T0) == Result::OK);
    for (int i = 0; i < 60; i++)
        t.creditSeconds(42, 0, "filler-" + std::to_string(i), T0);
    CHECK(t.creditSeconds(42, 5, "tx-1", T0) == Result::DUPLICATE_TX); // the ring remembers 64 ids
    for (int i = 0; i < 70; i++)
        t.creditSeconds(42, 0, "more-" + std::to_string(i), T0);
    CHECK(t.creditSeconds(42, 5, "tx-1", T0) == Result::OK);
    CHECK(t.setBalance(42, 660, T0) == Result::OK);

    // ---- a phone can lower the balance but never raise it ----
    CHECK(t.reportRemaining(42, 3, 500, T0 + 5) == Result::OK && t.find(42)->balanceSec == 500);
    CHECK(t.reportRemaining(42, 3, 99999, T0 + 6) == Result::OK &&
          t.find(42)->balanceSec == 500); // inflated report ignored
    CHECK(t.reportRemaining(42, 4, 100, T0) == Result::NOT_SIGNED_IN &&
          t.find(42)->balanceSec == 500); // another slot cannot
    CHECK(t.signOut(42, T0) == Result::OK);
    CHECK(t.reportRemaining(42, 3, 100, T0) == Result::NOT_SIGNED_IN);
    CHECK(t.signIn(42, 3, T0) == Result::OK);
    CHECK(t.signOutSlot(3, T0 + 90) == 1 && t.find(42)->signedInSlot == 0 && t.find(42)->balanceSec == 500);
    CHECK(t.signOutSlot(0, T0) == 0);

    // ---- admin: adjust, delete ----
    {
        AccountTable a;
        scan(a, 5, 1, T0);
        a.signOut(5, T0);
        CHECK(a.adjustSeconds(5, 600, T0) == Result::OK && a.find(5)->balanceSec == BONUS + 600);
        CHECK(a.adjustSeconds(5, -100000, T0) == Result::OK && a.find(5)->balanceSec == 0); // never negative
        CHECK(a.adjustSeconds(5, (int64_t)UINT32_MAX * 3, T0) == Result::OK && a.find(5)->balanceSec == UINT32_MAX);
        CHECK(a.adjustSeconds(5, 5, T0) == Result::OK && a.find(5)->balanceSec == UINT32_MAX); // never wraps
        CHECK(a.adjustSeconds(6, 60, T0) == Result::NO_SUCH_ACCOUNT);
        a.setBalance(5, 300, T0);
        a.signIn(5, 4, T0);
        CHECK(a.adjustSeconds(5, 60, T0) == Result::ALREADY_SIGNED_IN && a.find(5)->balanceSec == 300);
    }

    // ---- the starter time can NEVER be claimed twice: not after pruning, not after deleting ----
    {
        AccountTable a;
        scan(a, 10, 1, T0);
        a.signOut(10, T0);
        a.setBalance(10, 0, T0); // all spent
        CHECK(a.prune(T0 + 31 * DAY) == 1 && a.find(10) == nullptr);
        CHECK(a.isRedeemed(10)); // the account is gone, the fact that its card was used is not
        CHECK(scan(a, 10, 1, T0 + 40 * DAY, &first) == Result::OK && !first);
        CHECK(a.find(10)->balanceSec == 0 && !a.find(10)->everFunded); // an EMPTY account: no second bonus
        a.signOut(10, T0 + 40 * DAY);

        scan(a, 11, 1, T0);
        a.signOut(11, T0);
        CHECK(a.deleteAccount(11) == Result::OK && a.find(11) == nullptr);
        CHECK(a.deleteAccount(11) == Result::NO_SUCH_ACCOUNT);
        CHECK(scan(a, 11, 1, T0, &first) == Result::OK && !first &&
              a.find(11)->balanceSec == 0); // revoked card = empty account

        // and it survives a save and a load
        std::vector<uint8_t> img = save(a);
        AccountTable b;
        CHECK(b.deserialize(img.data(), img.size()));
        CHECK(b.isRedeemed(10) && b.isRedeemed(11) && !b.isRedeemed(12));
        CHECK(scan(b, 12, 1, T0, &first) == Result::OK && first && b.find(12)->balanceSec == BONUS);
    }

    // ---- prune: never touches accounts with time or in use ----
    {
        AccountTable p;
        scan(p, 1, 1, T0); // has time and is signed in
        scan(p, 2, 2, T0);
        p.signOut(2, T0); // has time
        scan(p, 3, 3, T0);
        p.signOut(3, T0);
        p.setBalance(3, 0, T0); // spent everything
        p.redeemCard(4, BONUS, T0, first);
        p.setBalance(4, 0, T0);
        p.deleteAccount(4);
        bool f2;
        p.redeemCard(4, BONUS, T0, f2); // a rescan after revoking: empty and never funded
        CHECK(p.count() == 4);
        CHECK(p.prune(0) == 0); // box has no clock yet: nothing is deleted
        CHECK(p.prune(T0 + DAY) == 0);
        CHECK(p.prune(T0 + 7 * DAY - 1) == 0);
        CHECK(p.prune(T0 + 7 * DAY) == 1 && p.find(4) == nullptr); // never funded: gone after a week
        CHECK(p.prune(T0 + 29 * DAY) == 0);
        CHECK(p.prune(T0 + 30 * DAY) == 1 && p.find(3) == nullptr); // spent: gone after 30 idle days
        CHECK(p.prune(T0 + 3650UL * DAY) == 0);                     // 10 years: time and in-use accounts still stay
        CHECK(p.find(1) != nullptr && p.find(2) != nullptr && p.count() == 2);
        CHECK(p.prune(10) == 0); // a clock that moved back never underflows into "idle for 136 years"
    }

    // ---- full table: prune first, then redeem; a full table never burns a card's one-time bonus ----
    {
        auto fp = std::make_unique<AccountTable>();
        AccountTable& f = *fp;
        for (uint32_t i = 1; i <= MAX_ACCOUNTS; i++) {
            CHECK(f.redeemCard(i, BONUS, T0, first) == Result::OK);
            if (i > 100) f.setBalance(i, 0, T0); // 100 keep their time, the rest spent it
        }
        CHECK(f.count() == MAX_ACCOUNTS);
        uint32_t newcomer = MAX_ACCOUNTS + 1;
        CHECK(f.redeemCard(newcomer, BONUS, T0, first) == Result::ACCOUNTS_FULL && !first);
        CHECK(!f.isRedeemed(newcomer)); // nothing changed: the card's bonus is still unspent
        CHECK(f.redeemCard(newcomer, BONUS, T0 + 31 * DAY, first) == Result::OK && first); // the spent ones were pruned
        CHECK(f.find(newcomer)->balanceSec == BONUS && f.count() == 101);
        CHECK(f.find(1) != nullptr && f.find(300) == nullptr);
    }

    // ---- storage round trip, torn writes, corruption ----
    {
        AccountTable a;
        scan(a, 7, 2, T0 + 1);
        a.setName(7, "Carol");
        a.creditSeconds(7, 1234, "c1", T0);
        scan(a, 9, 1, T0);
        a.signOut(9, T0);
        std::vector<uint8_t> img = save(a);
        CHECK(img.size() == AccountTable::HEADER_BYTES + 2 * AccountTable::ROW_BYTES + 4);

        AccountTable b;
        CHECK(b.deserialize(img.data(), img.size()));
        CHECK(b.count() == 2 && b.seq() == a.seq());
        CHECK(b.find(7)->balanceSec == BONUS + 1234 && b.find(7)->signedInSlot == 2 &&
              std::string(b.find(7)->name) == "Carol");
        CHECK(b.find(9)->signedInSlot == 0 && b.find(9)->name[0] == 0);
        CHECK(b.creditSeconds(7, 9, "c1", T0) == Result::DUPLICATE_TX); // the tx ring survived
        CHECK(b.isRedeemed(7) && b.isRedeemed(9) && !b.isRedeemed(8));

        // Every possible cut-off point (a power cut part way through a write) is refused and changes nothing.
        for (size_t cut = 0; cut < img.size(); cut += 97) {
            AccountTable c;
            c.redeemCard(5, BONUS, T0, first);
            CHECK(!c.deserialize(img.data(), cut));
            CHECK(c.count() == 1 && c.find(5) != nullptr);
        }
        // One flipped bit anywhere is caught by the CRC.
        for (size_t i = 0; i < img.size(); i += 113) {
            std::vector<uint8_t> bad(img);
            bad[i] ^= 0x10;
            AccountTable c;
            CHECK(!c.deserialize(bad.data(), bad.size()));
        }
        // The old username-and-PIN file format (version 1) is refused, not misread.
        std::vector<uint8_t> v1(img);
        v1[4] = 1;
        AccountTable c;
        CHECK(!c.deserialize(v1.data(), v1.size()));
        uint32_t s1 = a.seq();
        save(a);
        CHECK(a.seq() == s1 + 1); // saving again bumps the sequence number so the newer of two files can be told apart
    }

    // ---- the cost limiter: a flood is refused, the window reopens, millis() wrap-around is safe ----
    {
        CostLimiter lim(3, 60000);
        CHECK(lim.allow(1000) && lim.allow(1001) && lim.allow(1002));
        CHECK(!lim.allow(1003) && !lim.allow(30000));
        CHECK(!lim.allow(1000 + 59999));
        CHECK(lim.allow(1000 + 60000)); // window over: open again
        CHECK(lim.allow(1000 + 60001) && lim.allow(1000 + 60002) && !lim.allow(1000 + 60003));
        CostLimiter wrap(2, 1000);
        uint32_t near = 0xFFFFFF00u; // the 32-bit millis() counter is about to wrap
        CHECK(wrap.allow(near) && wrap.allow(near + 10) && !wrap.allow(near + 20));
        CHECK(!wrap.allow(near + 500));
        CHECK(wrap.allow(near + 1000));
    }

    // ---- fuzz: random damage to a saved file is never accepted as a different valid table, and never crashes ----
    {
        AccountTable src;
        for (uint32_t i = 1; i <= 15; i++) {
            scan(src, i * 100, 1, T0);
            src.signOut(i * 100, T0);
            src.setName(i * 100, "player" + std::to_string(i));
            src.creditSeconds(i * 100, 60 + i, "fz" + std::to_string(i), T0);
        }
        std::vector<uint8_t> good = save(src);
        uint32_t rng = 12345;
        auto next = [&]() {
            rng = rng * 1664525u + 1013904223u;
            return rng >> 8;
        };
        AccountTable keep;
        keep.redeemCard(5, BONUS, T0, first);
        int accepted = 0;
        for (int round = 0; round < 3000; round++) {
            std::vector<uint8_t> bad(good);
            int edits = 1 + (int)(next() % 4);
            for (int e = 0; e < edits; e++)
                bad[next() % bad.size()] ^= (uint8_t)(1u << (next() % 8));
            if (next() % 5 == 0) bad.resize(next() % bad.size());
            AccountTable victim = keep;
            if (victim.deserialize(bad.data(), bad.size())) {
                accepted++; // only possible if the edits cancelled out, i.e. the bytes are the original
                CHECK(bad == good);
            } else {
                CHECK(victim.count() == 1 && victim.find(5) != nullptr); // a refused file changes nothing
            }
        }
        CHECK(accepted < 5);
    }

    if (failures == 0) printf("accounts_test: all passed\n");
    return failures == 0 ? 0 : 1;
}
