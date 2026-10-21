#ifndef OWNER_DATA_H
#define OWNER_DATA_H

// What an operator's factory reset (dashboard button or the physical reset pin) must NOT erase:
// the lifetime revenue counters, the vendor revenue split and the used slot-license keys with the grandfathered slot floor (SlotLicense.h). Only the owner, signed in with the admin QR code, wipes those
// too (/api/superadmin/factory_reset).
// Pure C++ (no Arduino), tested on the host.
//
// `Store` is any class with: isKey, getInt, putInt, getUInt, putUInt, getString, putString.

#include <stdint.h>
#include <string>
#include "Product.h"

namespace ownerdata {

static const char* const K_TOTAL_COINS = "total_coins";
static const char* const K_WIFI_COINS = "tot_wifi_coins";
static const char* const K_PHONE_COINS = "tot_phone_coins";
static const char* const K_TOTAL_CENTAVOS = "earn_c";
static const char* const K_VENDOR_SPLIT = "vendor_split";
#if PISO_HAS_PHONES
static const char* const K_LICENSES = "lic_ids";
static const char* const K_LIC_FLOOR = "lic_floor";
#endif

struct Snapshot {
    bool hasCoins = false, hasWifiCoins = false, hasPhoneCoins = false, hasCentavos = false, hasSplit = false;
    int32_t split = 0;
#if PISO_HAS_PHONES
    bool hasLicenses = false, hasFloor = false;
    std::string licenses;
    int32_t floor = 0;
#endif
    uint32_t coins = 0, wifiCoins = 0, phoneCoins = 0, centavos = 0;
};

template <class Store> Snapshot capture(Store& s) {
    Snapshot o;
    if ((o.hasCoins = s.isKey(K_TOTAL_COINS))) o.coins = s.getUInt(K_TOTAL_COINS, 0);
    if ((o.hasWifiCoins = s.isKey(K_WIFI_COINS))) o.wifiCoins = s.getUInt(K_WIFI_COINS, 0);
    if ((o.hasPhoneCoins = s.isKey(K_PHONE_COINS))) o.phoneCoins = s.getUInt(K_PHONE_COINS, 0);
    if ((o.hasCentavos = s.isKey(K_TOTAL_CENTAVOS))) o.centavos = s.getUInt(K_TOTAL_CENTAVOS, 0);
    if ((o.hasSplit = s.isKey(K_VENDOR_SPLIT))) o.split = s.getInt(K_VENDOR_SPLIT, 0);
#if PISO_HAS_PHONES
    if ((o.hasLicenses = s.isKey(K_LICENSES))) o.licenses = s.getString(K_LICENSES, "");
    if ((o.hasFloor = s.isKey(K_LIC_FLOOR))) o.floor = s.getInt(K_LIC_FLOOR, 0);
#endif
    return o;
}

template <class Store> void restore(Store& s, const Snapshot& o) {
    if (o.hasCoins) s.putUInt(K_TOTAL_COINS, o.coins);
    if (o.hasWifiCoins) s.putUInt(K_WIFI_COINS, o.wifiCoins);
    if (o.hasPhoneCoins) s.putUInt(K_PHONE_COINS, o.phoneCoins);
    if (o.hasCentavos) s.putUInt(K_TOTAL_CENTAVOS, o.centavos);
    if (o.hasSplit) s.putInt(K_VENDOR_SPLIT, o.split);
#if PISO_HAS_PHONES
    if (o.hasLicenses) s.putString(K_LICENSES, o.licenses);
    if (o.hasFloor) s.putInt(K_LIC_FLOOR, o.floor);
#endif
}

} // namespace ownerdata

#endif
