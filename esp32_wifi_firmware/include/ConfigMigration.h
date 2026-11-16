#ifndef CONFIG_MIGRATION_H
#define CONFIG_MIGRATION_H

// Pure (no Arduino types) versioned schema initialization/migration for "pisowifi_cfg".

#include "SetupGate.h"

#include <cstdint>
#include <string>

namespace cfgmig {

static const uint32_t CURRENT_VERSION = 1;

static const char* const K_CFG_VER = "cfg_ver";
static const char* const K_ADMIN_PW = "admin_pw";
static const char* const K_ADMIN_PW_CHANGED = "pw_chg";

template <class Store> void migrateV1Credentials(Store& s) {
    if (!s.isKey(K_ADMIN_PW)) {
        s.putString(K_ADMIN_PW, setupgate::DEFAULT_PASSWORD);
        s.putBool(K_ADMIN_PW_CHANGED, false);
    } else if (!s.isKey(K_ADMIN_PW_CHANGED)) {
        s.putBool(K_ADMIN_PW_CHANGED, s.getString(K_ADMIN_PW, "") != "admin");
    }
}

struct Result {
    uint32_t from = 0;
    uint32_t to = 0;
    bool ranAny = false;
    bool storeIsNewer = false;
};

template <class Store> Result runAll(Store& s) {
    Result r;
    r.from = s.getUInt(K_CFG_VER, 0);
    r.to = r.from;
    if (r.from > CURRENT_VERSION) {
        r.storeIsNewer = true;
        return r;
    }
    if (r.from < 1) migrateV1Credentials(s);
    if (r.from != CURRENT_VERSION) {
        s.putUInt(K_CFG_VER, CURRENT_VERSION);
        r.ranAny = true;
    }
    r.to = CURRENT_VERSION;
    return r;
}

} // namespace cfgmig

#endif // CONFIG_MIGRATION_H
