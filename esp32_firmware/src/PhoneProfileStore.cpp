// Rental phone profiles stored on the box. The rules (names, rates, the text format) are in PhoneProfiles.h.

#include "PhoneProfileStore.h"

#include "Config.h"
#include "DeviceNetwork.h"
#include "DeviceManager.h"

using namespace phoneprofiles;

static const char* const NVS_KEY_PHONE_PROFILES = "ph_profiles";
static const char* const NVS_KEY_SLOT_PROFILES = "slot_prof";

static String profilesRaw;     // "id|name|minutes;..."
static String slotProfilesRaw; // "0,2,0,..."

void loadPhoneProfilesFromPrefs() {
    profilesRaw = prefs.getString(NVS_KEY_PHONE_PROFILES, "");
    slotProfilesRaw = prefs.getString(NVS_KEY_SLOT_PROFILES, "");
}

void resetPhoneProfiles() {
    profilesRaw = "";
    slotProfilesRaw = "";
}

int phoneProfileList(Profile* out, int maxOut) {
    return parseList(std::string(profilesRaw.c_str()), out, maxOut);
}

static void readAssign(int* ids) {
    parseAssign(std::string(slotProfilesRaw.c_str()), ids, MAX_SUPPORTED_SLOTS);
}

static bool writeNvs(const String& profiles, const String& assign) {
    if (!prefs.begin(NVS_NAMESPACE, false)) return false;
    prefs.putString(NVS_KEY_PHONE_PROFILES, profiles);
    prefs.putString(NVS_KEY_SLOT_PROFILES, assign);
    prefs.end();
    return true;
}

bool phoneProfilesSave(const Profile* list, int n) {
    if (n < 0 || n > MAX_PROFILES) return false;
    int ids[MAX_SUPPORTED_SLOTS];
    readAssign(ids);
    sanitizeAssign(ids, MAX_SUPPORTED_SLOTS, list, n);
    String newProfiles = String(serializeList(list, n).c_str());
    String newAssign = String(serializeAssign(ids, MAX_SUPPORTED_SLOTS).c_str());
    if (!writeNvs(newProfiles, newAssign)) return false;
    profilesRaw = newProfiles;
    slotProfilesRaw = newAssign;
    return true;
}

int phoneProfileIdForSlotIndex(int slotIdx) {
    if (slotIdx < 0 || slotIdx >= MAX_SUPPORTED_SLOTS) return 0;
    int ids[MAX_SUPPORTED_SLOTS];
    readAssign(ids);
    return ids[slotIdx];
}

bool phoneProfileAssign(int slotIdx, int profileId) {
    if (slotIdx < 0 || slotIdx >= MAX_SUPPORTED_SLOTS) return false;
    Profile list[MAX_PROFILES];
    int n = phoneProfileList(list, MAX_PROFILES);
    if (profileId != 0 && !hasId(list, n, profileId)) return false;
    int ids[MAX_SUPPORTED_SLOTS];
    readAssign(ids);
    ids[slotIdx] = profileId;
    String newAssign = String(serializeAssign(ids, MAX_SUPPORTED_SLOTS).c_str());
    if (!writeNvs(profilesRaw, newAssign)) return false;
    slotProfilesRaw = newAssign;
    return true;
}

int minutesPerCoinForSlotIndex(int slotIdx) {
    Profile list[MAX_PROFILES];
    int n = phoneProfileList(list, MAX_PROFILES);
    return rateFor(list, n, phoneProfileIdForSlotIndex(slotIdx), minutesPerCoin);
}

int minutesPerCoinForDevice(const String& devId, const String& ip) {
    int slotIdx = (devId.length() > 0 || ip.length() > 0) ? findSlotIndexForDevice(devId, ip) : -1;
    return slotIdx >= 0 ? minutesPerCoinForSlotIndex(slotIdx) : rateFor(nullptr, 0, 0, minutesPerCoin);
}

String phoneProfilesJson() {
    Profile list[MAX_PROFILES];
    int n = phoneProfileList(list, MAX_PROFILES);
    int ids[MAX_SUPPORTED_SLOTS];
    readAssign(ids);
    String json = "{\"standard\":" + String(rateFor(nullptr, 0, 0, minutesPerCoin)) +
                  ",\"max\":" + String(MAX_PROFILES) + ",\"profiles\":[";
    for (int i = 0; i < n; i++) {
        if (i > 0) json += ",";
        json += "{\"id\":" + String(list[i].id) + ",\"name\":\"" + String(list[i].name.c_str()) +
                "\",\"minutes\":" + String(list[i].minutes) + "}";
    }
    json += "],\"slots\":[";
    for (int i = 0; i < MAX_SUPPORTED_SLOTS; i++) {
        if (i > 0) json += ",";
        json += String(hasId(list, n, ids[i]) ? ids[i] : 0);
    }
    json += "]}";
    return json;
}

void pushRateToSlotIndex(int slotIdx) {
    if (slotIdx < 0 || slotIdx >= MAX_SUPPORTED_SLOTS) return;
    const PhoneSlot& slot = phoneSlots[slotIdx];
    if (slot.deviceId.length() == 0 || slot.ip.length() == 0 || slot.ip == "127.0.0.1") return;
    String params = "minutes=" + String(minutesPerCoinForSlotIndex(slotIdx)) + "&price=1.0";
    sendAuthenticated(slot.ip, targetPort, "/config", "/challenge", params, 1000);
}

void pushRatesToPhones() {
    for (int i = 0; i < MAX_SUPPORTED_SLOTS; i++)
        pushRateToSlotIndex(i);
}
