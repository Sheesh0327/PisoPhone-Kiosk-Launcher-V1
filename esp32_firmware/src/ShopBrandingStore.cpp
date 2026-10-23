// The shop's branding stored on the box. The rules (palette ids, caps, the push text) are in ShopBranding.h.

#include "ShopBrandingStore.h"

#include "Config.h"
#include "DeviceManager.h"
#include "DeviceNetwork.h"

using namespace shopbranding;

static const char* const NVS_KEY_BRAND_PALETTE = "brand_pal";
static const char* const NVS_KEY_SHOP_NAME = "shop_name";
static const char* const NVS_KEY_BRAND_TITLE = "brand_title";
static const char* const NVS_KEY_ANIM_DENSITY = "anim_dens";

static Branding current;

// A value read back is checked again: a corrupted entry becomes "not set", never something the phone is told.
static std::string cleanRead(const String& raw, bool (*valid)(const std::string&)) {
    std::string value(raw.c_str());
    return valid(value) ? value : std::string();
}

void loadShopBrandingFromPrefs() {
    current = Branding();
    // The four are always written together, so one key present means the owner saved branding at some point.
    current.configured = prefs.isKey(NVS_KEY_BRAND_PALETTE);
    if (!current.configured) return;
    current.palette = cleanRead(prefs.getString(NVS_KEY_BRAND_PALETTE, ""), validPalette);
    current.name = cleanRead(prefs.getString(NVS_KEY_SHOP_NAME, ""), validName);
    current.title = cleanRead(prefs.getString(NVS_KEY_BRAND_TITLE, ""), validTitle);
    current.density = cleanRead(prefs.getString(NVS_KEY_ANIM_DENSITY, ""), validDensity);
}

void resetShopBranding() {
    current = Branding();
}

Branding shopBrandingCurrent() {
    return current;
}

bool shopBrandingSave(const Branding& next) {
    if (!validPalette(next.palette) || !validName(next.name) || !validTitle(next.title) || !validDensity(next.density)) return false;
    if (!fitsInPush(next)) return false;
    if (!prefs.begin(NVS_NAMESPACE, false)) return false;
    prefs.putString(NVS_KEY_BRAND_PALETTE, next.palette.c_str());
    prefs.putString(NVS_KEY_SHOP_NAME, next.name.c_str());
    prefs.putString(NVS_KEY_BRAND_TITLE, next.title.c_str());
    prefs.putString(NVS_KEY_ANIM_DENSITY, next.density.c_str());
    prefs.end();
    current = next;
    current.configured = true;
    return true;
}

String shopBrandingJson() {
    String json = "{\"configured\":";
    json += current.configured ? "true" : "false";
    json += ",\"palette\":\"" + String(current.palette.c_str()) + "\",\"name\":\"" + String(current.name.c_str()) +
            "\",\"title\":\"" + String(current.title.c_str()) + "\",\"density\":\"" + String(current.density.c_str()) + "\"}";
    return json;
}

void pushBrandingToIp(const String& ip) {
    String params = String(pushBody(current).c_str());
    if (params.length() == 0 || ip.length() == 0 || ip == "127.0.0.1") return;
    sendAuthenticated(ip, targetPort, "/config", "/challenge", params, 1000);
}

void pushBrandingToPhones() {
    for (int i = 0; i < MAX_SUPPORTED_SLOTS; i++) {
        const PhoneSlot& slot = phoneSlots[i];
        if (slot.deviceId.length() == 0) continue;
        pushBrandingToIp(slot.ip);
    }
}
