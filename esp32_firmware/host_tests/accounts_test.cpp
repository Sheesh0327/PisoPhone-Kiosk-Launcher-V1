#include "../include/Accounts.h"

#include <cstdio>
#include <memory>

using namespace accounts;

static int failures = 0;
#define CHECK(c)                                                                                                       \
    do {                                                                                                               \
        if (!(c)) {                                                                                                    \
            printf("FAIL line %d: %s\n", __LINE__, #c);                                                                \
            failures++;                                                                                                \
        }                                                                                                              \
    } while (0)

static const uint8_t SALT[SALT_BYTES] = {1, 2, 3, 4, 5, 6, 7, 8};
static const uint32_t DAY = 24 * 3600;
static const uint32_t T0 = 1000000;

static std::vector<uint8_t> save(AccountTable& t) {
    std::vector<uint8_t> out;
    t.serialize([&](const uint8_t* d, size_t n) { out.insert(out.end(), d, d + n); });
    return out;
}

int main() {
    auto tp = std::make_unique<AccountTable>();
    AccountTable& t = *tp;

    // ---- create / validation ----
    CHECK(t.create("Alice", "1234", SALT, T0) == Result::OK); // stored lowercase
    CHECK(t.find("ALICE") != nullptr && strcmp(t.find("alice")->username, "alice") == 0);
    CHECK(t.create("alice", "9999", SALT, T0) == Result::NAME_TAKEN);
    CHECK(t.create("al", "1234", SALT, T0) == Result::BAD_NAME);
    CHECK(t.create("bad name", "1234", SALT, T0) == Result::BAD_NAME);
    CHECK(t.create("averyveryverylongname", "1234", SALT, T0) == Result::BAD_NAME);
    CHECK(t.create("bob", "123", SALT, T0) == Result::BAD_PIN_FORMAT);
    CHECK(t.create("bob", "1234567", SALT, T0) == Result::BAD_PIN_FORMAT);
    CHECK(t.create("bob", "12a4", SALT, T0) == Result::BAD_PIN_FORMAT);
    CHECK(t.count() == 1);
    // The PIN is never stored: the hash is not the PIN bytes
    CHECK(memcmp(t.find("alice")->hash, "1234", 4) != 0);

    // ---- PIN check ----
    CHECK(t.verifyPin("alice", "1234", T0) == Result::OK);
    CHECK(t.verifyPin("alice", "0000", T0) == Result::BAD_PIN);
    CHECK(t.verifyPin("nobody", "1234", T0) == Result::NO_SUCH_USER);

    // ---- wrong-PIN lockout: 5 failures lock 5 min, then 10, 20 ... up to 1 hour ----
    {
        uint32_t now = T0;
        t.verifyPin("alice", "1234", now); // reset the count from the failure above
        for (int i = 0; i < 4; i++)
            CHECK(t.verifyPin("alice", "0000", now) == Result::BAD_PIN);
        CHECK(t.verifyPin("alice", "0000", now) == Result::BAD_PIN); // 5th: locks
        CHECK(t.verifyPin("alice", "1234", now) == Result::LOCKED);  // right PIN is refused while locked
        CHECK(t.verifyPin("alice", "1234", now + 299) == Result::LOCKED);
        now += 300;
        CHECK(t.verifyPin("alice", "0000", now) == Result::BAD_PIN); // 6th failure: locks 10 min
        CHECK(t.verifyPin("alice", "1234", now + 599) == Result::LOCKED);
        CHECK(t.verifyPin("alice", "1234", now + 600) == Result::OK); // lock over, right PIN clears it
        uint32_t tt = now + 600;
        for (int i = 0; i < 40; i++) {
            CHECK(t.verifyPin("alice", "0000", tt) == Result::BAD_PIN);
            tt = t.find("alice")->lockedUntilS; // wait out each lock
        }
        // after many failures the lock is exactly one hour, never more
        CHECK(t.verifyPin("alice", "0000", tt) == Result::BAD_PIN);
        CHECK(t.find("alice")->lockedUntilS - tt == 3600);
    }
    t.verifyPin("alice", "1234", T0 + 10 * DAY); // lock long over, success resets
    CHECK(t.find("alice")->failCount == 0);

    // ---- one phone at a time ----
    CHECK(t.signIn("alice", 2, T0) == Result::OK);
    CHECK(t.signIn("alice", 2, T0) == Result::OK); // same slot again is fine
    CHECK(t.signIn("alice", 3, T0) == Result::ALREADY_SIGNED_IN);
    CHECK(t.signOut("alice", T0) == Result::OK);
    CHECK(t.signIn("alice", 3, T0) == Result::OK);
    CHECK(t.signIn("alice", 0, T0) == Result::INTERNAL);
    CHECK(t.signIn("ghost", 1, T0) == Result::NO_SUCH_USER);

    // ---- credits are idempotent by tx id ----
    CHECK(t.creditSeconds("alice", 600, "tx-1", T0) == Result::OK);
    CHECK(t.creditSeconds("alice", 600, "tx-1", T0) == Result::DUPLICATE_TX); // box retry
    CHECK(t.find("alice")->balanceSec == 600);
    CHECK(t.creditSeconds("alice", 60, "tx-2", T0) == Result::OK);
    CHECK(t.find("alice")->balanceSec == 660);
    CHECK(t.find("alice")->everFunded);
    CHECK(t.creditSeconds("alice", 0xFFFFFFF0u, "tx-3", T0) == Result::OK); // saturates, never wraps
    CHECK(t.find("alice")->balanceSec == UINT32_MAX);
    CHECK(t.setBalance("alice", 660, T0) == Result::OK);
    // The ring forgets the oldest ids after 64 others, never sooner
    for (int i = 0; i < 60; i++)
        t.creditSeconds("alice", 0, "filler-" + std::to_string(i), T0);
    CHECK(t.creditSeconds("alice", 5, "tx-1", T0) == Result::DUPLICATE_TX);
    for (int i = 0; i < 70; i++)
        t.creditSeconds("alice", 0, "more-" + std::to_string(i), T0);
    CHECK(t.creditSeconds("alice", 5, "tx-1", T0) == Result::OK);
    CHECK(t.setBalance("alice", 660, T0) == Result::OK);

    // ---- a phone can lower the balance but never raise it ----
    CHECK(t.reportRemaining("alice", 3, 500, T0 + 5) == Result::OK);
    CHECK(t.find("alice")->balanceSec == 500);
    CHECK(t.reportRemaining("alice", 3, 99999, T0 + 6) == Result::OK); // inflated report is ignored
    CHECK(t.find("alice")->balanceSec == 500);
    CHECK(t.reportRemaining("alice", 4, 100, T0) == Result::NOT_SIGNED_IN); // a different slot cannot report
    CHECK(t.find("alice")->balanceSec == 500);
    CHECK(t.signOut("alice", T0) == Result::OK);
    CHECK(t.reportRemaining("alice", 3, 100, T0) == Result::NOT_SIGNED_IN); // signed out
    CHECK(t.signIn("alice", 3, T0) == Result::OK);
    CHECK(t.signOutSlot(3, T0 + 90) == 1); // the box drops a phone that went silent
    CHECK(t.find("alice")->signedInSlot == 0 && t.find("alice")->balanceSec == 500);
    CHECK(t.signOutSlot(0, T0) == 0);
    CHECK(t.findBySlot(3) == nullptr && t.findBySlot(0) == nullptr);
    CHECK(t.signIn("alice", 3, T0) == Result::OK);
    CHECK(t.findBySlot(3) != nullptr && strcmp(t.findBySlot(3)->username, "alice") == 0);
    CHECK(t.signOut("alice", T0) == Result::OK);

    // ---- prune: never touches accounts with time or in use ----
    {
        AccountTable p;
        p.create("funded", "1234", SALT, T0);
        p.creditSeconds("funded", 60, "a", T0);
        p.create("online", "1234", SALT, T0);
        p.signIn("online", 1, T0);
        p.create("empty", "1234", SALT, T0);
        p.creditSeconds("empty", 60, "b", T0);
        p.reportRemaining("empty", 1, 0, T0); // not signed in: ignored
        p.setBalance("empty", 0, T0);         // spent everything
        p.create("never", "1234", SALT, T0);  // never given any time
        CHECK(p.count() == 4);
        CHECK(p.prune(0) == 0); // box has no clock yet: nothing is deleted
        CHECK(p.prune(T0 + DAY) == 0);
        CHECK(p.prune(T0 + 7 * DAY - 1) == 0);
        CHECK(p.prune(T0 + 7 * DAY) == 1); // "never" goes after a week
        CHECK(p.find("never") == nullptr);
        CHECK(p.prune(T0 + 29 * DAY) == 0);
        CHECK(p.prune(T0 + 30 * DAY) == 1); // "empty" goes after 30 days idle
        CHECK(p.find("empty") == nullptr);
        CHECK(p.prune(T0 + 3650UL * DAY) == 0); // 10 years: time and in-use accounts still stay
        CHECK(p.find("funded") != nullptr && p.find("online") != nullptr && p.count() == 2);
        // a clock that jumped backwards never underflows into "idle for 136 years"
        CHECK(p.prune(10) == 0);
    }

    // ---- full table: prune first, then create; otherwise refuse ----
    {
        auto fp = std::make_unique<AccountTable>();
        AccountTable& f = *fp;
        for (size_t i = 0; i < MAX_ACCOUNTS; i++) {
            char n[20];
            snprintf(n, sizeof(n), "user%03u", (unsigned)i);
            CHECK(f.create(n, "1234", SALT, T0) == Result::OK);
            if (i < 100) f.creditSeconds(n, 10, "", T0); // 100 have time, 200 never got any
        }
        CHECK(f.count() == MAX_ACCOUNTS);
        CHECK(f.create("newcomer", "1234", SALT, T0) == Result::ACCOUNTS_FULL); // nothing is old enough yet
        CHECK(f.create("newcomer", "1234", SALT, T0 + 8 * DAY) == Result::OK);  // 200 unfunded were pruned
        CHECK(f.count() == 101);
        CHECK(f.find("user000") != nullptr && f.find("user250") == nullptr);
    }

    // ---- admin: adjust, unlock ----
    {
        AccountTable a;
        a.create("dana", "1234", SALT, T0);
        CHECK(a.adjustSeconds("dana", 600, T0) == Result::OK && a.find("dana")->balanceSec == 600);
        CHECK(a.find("dana")->everFunded);
        CHECK(a.adjustSeconds("DANA", -200, T0) == Result::OK && a.find("dana")->balanceSec == 400);
        CHECK(a.adjustSeconds("dana", -100000, T0) == Result::OK && a.find("dana")->balanceSec == 0); // never negative
        CHECK(a.adjustSeconds("dana", (int64_t)UINT32_MAX * 3, T0) == Result::OK &&
              a.find("dana")->balanceSec == UINT32_MAX);
        CHECK(a.adjustSeconds("dana", 5, T0) == Result::OK && a.find("dana")->balanceSec == UINT32_MAX); // never wraps
        CHECK(a.adjustSeconds("ghost", 60, T0) == Result::NO_SUCH_USER);
        a.setBalance("dana", 300, T0);
        a.signIn("dana", 4, T0);
        CHECK(a.adjustSeconds("dana", 60, T0) == Result::ALREADY_SIGNED_IN); // time is running on a phone
        CHECK(a.find("dana")->balanceSec == 300);
        a.signOut("dana", T0);
        for (int i = 0; i < 5; i++)
            a.verifyPin("dana", "0000", T0);
        CHECK(a.verifyPin("dana", "1234", T0) == Result::LOCKED);
        CHECK(a.unlock("dana") == Result::OK);
        CHECK(a.verifyPin("dana", "1234", T0) == Result::OK);
        CHECK(a.unlock("ghost") == Result::NO_SUCH_USER);
    }

    // ---- delete ----
    CHECK(t.deleteAccount("alice") == Result::OK);
    CHECK(t.find("alice") == nullptr);
    CHECK(t.deleteAccount("alice") == Result::NO_SUCH_USER);

    // ---- storage round trip, torn writes, corruption ----
    {
        AccountTable a;
        a.create("carol", "4321", SALT, T0);
        a.create("dave", "5555", SALT, T0);
        a.creditSeconds("carol", 1234, "c1", T0);
        a.signIn("carol", 2, T0 + 1);
        std::vector<uint8_t> img = save(a);
        CHECK(img.size() == 4 + 1 + 4 + 2 + 1 + TX_RING * 8 + 2 * AccountTable::ROW_BYTES + 4);

        AccountTable b;
        CHECK(b.deserialize(img.data(), img.size()));
        CHECK(b.count() == 2 && b.seq() == a.seq());
        CHECK(b.find("carol")->balanceSec == 1234 && b.find("carol")->signedInSlot == 2);
        CHECK(b.verifyPin("carol", "4321", T0) == Result::OK); // the PIN hash survived
        CHECK(b.verifyPin("carol", "1111", T0) == Result::BAD_PIN);
        CHECK(b.creditSeconds("carol", 9, "c1", T0) == Result::DUPLICATE_TX); // so did the tx ring

        // Every possible cut-off point (a power cut part way through a write) is refused and changes nothing.
        for (size_t cut = 0; cut < img.size(); cut += 7) {
            AccountTable c;
            c.create("keepme", "1234", SALT, T0);
            CHECK(!c.deserialize(img.data(), cut));
            CHECK(c.count() == 1 && c.find("keepme") != nullptr);
        }
        // One flipped bit anywhere is caught by the CRC.
        for (size_t i = 0; i < img.size(); i += 11) {
            std::vector<uint8_t> bad(img);
            bad[i] ^= 0x10;
            AccountTable c;
            CHECK(!c.deserialize(bad.data(), bad.size()));
        }
        // A valid CRC over nonsense (count too large / bad name) is refused too.
        std::vector<uint8_t> huge(img);
        huge[9] = 0xFF;
        huge[10] = 0xFF;
        AccountTable c;
        CHECK(!c.deserialize(huge.data(), huge.size()));
        // Saving again bumps the sequence number so the newer of two files can be told apart.
        uint32_t s1 = a.seq();
        save(a);
        CHECK(a.seq() == s1 + 1);
    }

    if (failures == 0) printf("accounts_test: all passed\n");
    return failures == 0 ? 0 : 1;
}
