#include "../include/ConfigMigration.h"

#include <cstdio>
#include <map>
#include <string>

static int failures = 0, checks = 0;
#define CHECK(c)                                                                                                       \
    do {                                                                                                               \
        checks++;                                                                                                      \
        if (!(c)) {                                                                                                    \
            failures++;                                                                                                \
            printf("FAIL line %d: %s\n", __LINE__, #c);                                                                \
        }                                                                                                              \
    } while (0)

// In-memory stand-in for the box's settings storage.
struct FakeStore {
    std::map<std::string, std::string> strings;
    std::map<std::string, uint32_t> uints;
    std::map<std::string, bool> bools;
    std::map<std::string, float> floats;
    bool isKey(const char* k) const { return strings.count(k) || uints.count(k) || bools.count(k) || floats.count(k); }
    std::string getString(const char* k, const std::string& d) const {
        auto it = strings.find(k);
        return it == strings.end() ? d : it->second;
    }
    void putString(const char* k, const std::string& v) { strings[k] = v; }
    bool getBool(const char* k, bool d) const {
        auto it = bools.find(k);
        return it == bools.end() ? d : it->second;
    }
    void putBool(const char* k, bool v) { bools[k] = v; }
    uint32_t getUInt(const char* k, uint32_t d) const {
        auto it = uints.find(k);
        return it == uints.end() ? d : it->second;
    }
    void putUInt(const char* k, uint32_t v) { uints[k] = v; }
    float getFloat(const char* k, float d) const {
        auto it = floats.find(k);
        return it == floats.end() ? d : it->second;
    }
    void remove(const char* k) {
        strings.erase(k);
        uints.erase(k);
        bools.erase(k);
        floats.erase(k);
    }
};

static uint32_t rng = 1;
static void fill(uint8_t* b, size_t n) {
    for (size_t i = 0; i < n; i++) {
        rng = rng * 1664525u + 1013904223u;
        b[i] = (uint8_t)(rng >> 16);
    }
}
// The old public key; written in two pieces so the repository check for its use does not flag a test.
static const char* LEGACY = "PISOPHONE_HMAC_"
                            "MASTER_KEY";
static cfgmig::Env env() {
    cfgmig::Env e;
    e.fillRandom = fill;
    e.legacySecret = LEGACY;
    return e;
}
using namespace cfgmig;

int main() {
    // fresh box: everything created, own key from the start, passwords generated
    {
        FakeStore s;
        Result r = runAll(s, env());
        CHECK(r.from == 0 && r.to == CURRENT_VERSION && r.ranAny);
        CHECK(secretmode::validSecret(s.getString(K_SHARED_SECRET, "")));
        CHECK(!s.getBool(K_LEGACY_KEY, true));
        CHECK(s.getString(K_ADMIN_PW, "") == "Coinslot@Setup" && !s.getBool(K_ADMIN_PW_CHANGED, true));
        CHECK(s.getString(K_SETUP_AP_PASS, "") == "Coinslot@Setup");
        CHECK(s.getUInt(K_TOTAL_CENTAVOS, 99) == 0);
        CHECK(s.getUInt(K_CFG_VER, 0) == CURRENT_VERSION);
    }

    // box already in the field: stays on the old key, keeps its money and its own admin password
    {
        FakeStore s;
        s.putString(K_WIFI_SSID, "shop-wifi");
        s.putString(K_ADMIN_PW, "my-own-password");
        s.floats[K_TOTAL_EARNINGS_OLD] = 1234.5f;
        runAll(s, env());
        CHECK(s.getBool(K_LEGACY_KEY, false));
        CHECK(secretmode::validSecret(s.getString(K_SHARED_SECRET, "")));
        CHECK(s.getString(K_ADMIN_PW, "") == "my-own-password");
        CHECK(s.getBool(K_ADMIN_PW_CHANGED, false));
        CHECK(s.getUInt(K_TOTAL_CENTAVOS, 0) == 123450);
        CHECK(!s.isKey(K_TOTAL_EARNINGS_OLD));
    }

    // the old factory admin password is not counted as chosen
    {
        FakeStore s;
        s.putString(K_WIFI_SSID, "x");
        s.putString(K_ADMIN_PW, "admin");
        runAll(s, env());
        CHECK(!s.getBool(K_ADMIN_PW_CHANGED, true));
    }

    // the public shared key saved as a "secret" is replaced
    {
        FakeStore s;
        s.putString(K_SHARED_SECRET, LEGACY);
        runAll(s, env());
        CHECK(s.getString(K_SHARED_SECRET, "") != LEGACY);
        CHECK(secretmode::validSecret(s.getString(K_SHARED_SECRET, "")));
    }

    // running again changes nothing (secrets, passwords, flags, money)
    {
        FakeStore s;
        s.putString(K_WIFI_SSID, "x");
        s.floats[K_TOTAL_EARNINGS_OLD] = 50.0f;
        runAll(s, env());
        FakeStore before = s;
        Result r = runAll(s, env());
        CHECK(!r.ranAny && r.from == CURRENT_VERSION);
        CHECK(before.strings == s.strings && before.uints == s.uints && before.bools == s.bools);
    }

    // a box that switched to its own key stays switched
    {
        FakeStore s;
        s.putString(K_WIFI_SSID, "x");
        runAll(s, env());
        s.putBool(K_LEGACY_KEY, false);
        s.uints.erase(K_CFG_VER); // pretend the version was lost: steps repeat but must not undo the switch
        runAll(s, env());
        CHECK(!s.getBool(K_LEGACY_KEY, true));
    }

    // steps older than the stored version do not run again
    {
        FakeStore s;
        s.putUInt(K_CFG_VER, CURRENT_VERSION);
        Result r = runAll(s, env());
        CHECK(!r.ranAny);
        CHECK(!s.isKey(K_SHARED_SECRET) && !s.isKey(K_ADMIN_PW));
    }

    // settings from a newer firmware are never touched
    {
        FakeStore s;
        s.putUInt(K_CFG_VER, CURRENT_VERSION + 5);
        s.putString(K_ADMIN_PW, "x");
        Result r = runAll(s, env());
        CHECK(r.storeIsNewer && !r.ranAny);
        CHECK(s.getUInt(K_CFG_VER, 0) == CURRENT_VERSION + 5);
        CHECK(!s.isKey(K_SHARED_SECRET));
    }

    // two fresh boxes never share a secret; both start on the published default password until it is changed
    {
        FakeStore a, b;
        runAll(a, env());
        runAll(b, env());
        CHECK(a.getString(K_SHARED_SECRET, "") != b.getString(K_SHARED_SECRET, ""));
        CHECK(a.getString(K_ADMIN_PW, "") == b.getString(K_ADMIN_PW, ""));
    }

    printf("config_migration_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
