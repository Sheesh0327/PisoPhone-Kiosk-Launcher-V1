#ifndef CONFIG_MIGRATION_H
#define CONFIG_MIGRATION_H

// Pure (no Arduino types) versioned migration of the box's saved settings, so every firmware upgrade brings
// stored data forward in a fixed order and each step runs once (host_tests/config_migration_test.cpp).
//
// The settings carry a schema version (`cfg_ver`). At boot runAll() runs every step newer than the stored
// version, then stores the current version. Steps are also safe to repeat. A box whose stored version is newer
// than this firmware (an older image was flashed after a newer one) is left untouched.
//
// To change the stored format: add a migrateVN() step, call it in runAll() under `v < N`, bump CURRENT_VERSION,
// and add a case to the test. Never edit an old step.
//
// `Store` is any class with: isKey, getString, putString, getBool, putBool, getUInt, putUInt, getFloat, remove
// (the firmware uses a thin wrapper over Preferences, the test a map).

#include "CredGen.h"
#include "Money.h"
#include "SecretMode.h"

#include <cstdint>
#include <string>

namespace cfgmig {

static const uint32_t CURRENT_VERSION = 3;

// Storage keys (the firmware's NVS_KEY_* constants are defined from these).
static const char* const K_CFG_VER = "cfg_ver";
static const char* const K_SHARED_SECRET = "shared_secret";
static const char* const K_LEGACY_KEY = "sec_legacy";
static const char* const K_WIFI_SSID = "wifi_ssid";
static const char* const K_ADMIN_PW = "admin_pw";
static const char* const K_ADMIN_PW_CHANGED = "pw_chg";
static const char* const K_SETUP_AP_PASS = "ap_pass";
static const char* const K_TOTAL_EARNINGS_OLD = "total_earnings"; // float pesos, before centavos
static const char* const K_TOTAL_CENTAVOS = "earn_c";

struct Env {
    credgen::FillRandom fillRandom = nullptr;
    const char* legacySecret = ""; // the old public shared key, never to be used as a box's own secret
};

// v1: the box's own secret, and whether an upgraded box stays on the old shared key (see SecretMode.h).
template <class Store> void migrateV1Secret(Store& s, const Env& env) {
    std::string secret = s.getString(K_SHARED_SECRET, "");
    if (!secretmode::validSecret(secret) || secret == env.legacySecret) {
        s.putString(K_SHARED_SECRET, credgen::password(32, env.fillRandom));
    }
    bool hasFlag = s.isKey(K_LEGACY_KEY);
    bool flagValue = hasFlag && s.getBool(K_LEGACY_KEY, false);
    bool legacy = secretmode::startInLegacyMode(hasFlag, flagValue, s.isKey(K_WIFI_SSID));
    if (!hasFlag) s.putBool(K_LEGACY_KEY, legacy);
}

// v2: unique setup-AP and admin passwords; a password other than the old factory "admin" counts as chosen.
template <class Store> void migrateV2Credentials(Store& s, const Env& env) {
    if (!s.isKey(K_ADMIN_PW)) {
        s.putString(K_ADMIN_PW, credgen::password(12, env.fillRandom));
        s.putBool(K_ADMIN_PW_CHANGED, false);
    } else if (!s.isKey(K_ADMIN_PW_CHANGED)) {
        s.putBool(K_ADMIN_PW_CHANGED, s.getString(K_ADMIN_PW, "") != "admin");
    }
    if (!s.isKey(K_SETUP_AP_PASS)) s.putString(K_SETUP_AP_PASS, credgen::password(10, env.fillRandom));
}

// v3: earnings as whole centavos instead of float pesos.
template <class Store> void migrateV3Earnings(Store& s) {
    if (!s.isKey(K_TOTAL_CENTAVOS)) {
        s.putUInt(K_TOTAL_CENTAVOS, money::centavosFromLegacyPesos(s.getFloat(K_TOTAL_EARNINGS_OLD, 0.0f)));
    }
    s.remove(K_TOTAL_EARNINGS_OLD);
}

struct Result {
    uint32_t from = 0;
    uint32_t to = 0;
    bool ranAny = false;
    bool storeIsNewer = false;
};

template <class Store> Result runAll(Store& s, const Env& env) {
    Result r;
    r.from = s.getUInt(K_CFG_VER, 0);
    r.to = r.from;
    if (r.from > CURRENT_VERSION) {
        r.storeIsNewer = true;
        return r;
    }
    if (r.from < 1) migrateV1Secret(s, env);
    if (r.from < 2) migrateV2Credentials(s, env);
    if (r.from < 3) migrateV3Earnings(s);
    if (r.from != CURRENT_VERSION) {
        s.putUInt(K_CFG_VER, CURRENT_VERSION);
        r.ranAny = true;
    }
    r.to = CURRENT_VERSION;
    return r;
}

} // namespace cfgmig

#endif // CONFIG_MIGRATION_H
