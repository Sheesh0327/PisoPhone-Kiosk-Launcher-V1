#ifndef OWNER_DATA_H
#define OWNER_DATA_H

// What an operator's factory reset (dashboard button or the physical reset pin) must NOT erase:
// the lifetime revenue counters, the vendor revenue split and the super-admin
// credentials. Only the owner's signed super-admin request wipes those too (/api/superadmin/factory_reset).
// Pure C++ (no Arduino), tested on the host.
//
// `Store` is any class with: isKey, getInt, putInt, getUInt, putUInt, getString, putString.

#include <stdint.h>
#include <string>

namespace ownerdata {

static const char* const K_TOTAL_COINS = "total_coins";
static const char* const K_TOTAL_CENTAVOS = "earn_c";
static const char* const K_VENDOR_SPLIT = "vendor_split";
static const char* const K_SA_VER = "sa_ver";
static const char* const K_SA_ITER = "sa_iter";
static const char* const K_SA_SALT = "sa_salt";
static const char* const K_SA_HASH = "sa_hash";

struct Snapshot {
    bool hasCoins = false, hasCentavos = false, hasSplit = false, hasSuperAdmin = false;
    int32_t split = 0;
    uint32_t coins = 0, centavos = 0, saVer = 0, saIter = 0;
    std::string saSalt, saHash;
};

template <class Store> Snapshot capture(Store& s) {
    Snapshot o;
    if ((o.hasCoins = s.isKey(K_TOTAL_COINS))) o.coins = s.getUInt(K_TOTAL_COINS, 0);
    if ((o.hasCentavos = s.isKey(K_TOTAL_CENTAVOS))) o.centavos = s.getUInt(K_TOTAL_CENTAVOS, 0);
    if ((o.hasSplit = s.isKey(K_VENDOR_SPLIT))) o.split = s.getInt(K_VENDOR_SPLIT, 0);
    if (s.isKey(K_SA_VER) && s.isKey(K_SA_HASH) && s.isKey(K_SA_SALT)) {
        o.hasSuperAdmin = true;
        o.saVer = s.getUInt(K_SA_VER, 0);
        o.saIter = s.getUInt(K_SA_ITER, 0);
        o.saSalt = s.getString(K_SA_SALT, "");
        o.saHash = s.getString(K_SA_HASH, "");
    }
    return o;
}

template <class Store> void restore(Store& s, const Snapshot& o) {
    if (o.hasCoins) s.putUInt(K_TOTAL_COINS, o.coins);
    if (o.hasCentavos) s.putUInt(K_TOTAL_CENTAVOS, o.centavos);
    if (o.hasSplit) s.putInt(K_VENDOR_SPLIT, o.split);
    if (o.hasSuperAdmin) {
        s.putString(K_SA_SALT, o.saSalt);
        s.putString(K_SA_HASH, o.saHash);
        s.putUInt(K_SA_ITER, o.saIter);
        s.putUInt(K_SA_VER, o.saVer); // last: marks the set as complete, like SuperAdminCreds does
    }
}

} // namespace ownerdata

#endif
