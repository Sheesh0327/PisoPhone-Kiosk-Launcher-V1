#ifndef CONFIG_H
#ifndef CONFIG_H
#define CONFIG_H

#include <Arduino.h>
#include <Preferences.h>

#define UDP_DISCOVERY_PORT 8888
const unsigned long ARM_TTL = 30000;
const int MAX_SUPPORTED_SLOTS = 20;
const char* const MASTER_CRYPTO_SECRET = "PISO_MASTER_SEC_2026";

struct LicenseSlot {
    int slotNum;
    String deviceId;
    String ip;
    String name;
    bool active;
};

struct TrackedDevice {
    String deviceId;
    String lastKnownIp;
    unsigned long lastSeenMs;
    int batteryLevel;
    bool isCharging;
    int timeRemainingSeconds;
};

struct NonceRecord {
    String deviceId;
    unsigned long long timestamp;
};

extern String wifiSsid;
extern String wifiPass;
extern int universalCoinPin;
extern int ledPin;
extern bool ledActiveLow;
extern int relayPin;
extern bool relayActiveLow;
extern String androidIps;
extern String p1Ip;
extern String p2Ip;
extern int minutesPerCoin;
extern int targetPort;
extern String webPassword;
extern String sharedSecret;
extern int matchMinutes;
extern String macAddressStr;
extern uint32_t totalCoinsLifetime;
extern uint32_t totalCoinsSession;
extern float totalEarningsLifetime;
extern float totalEarningsSession;
extern uint32_t lastSavedTotalCoins;
extern float lastSavedTotalEarnings;
extern bool revenueDirty;

extern LicenseSlot licenseSlots[MAX_SUPPORTED_SLOTS];
extern int maxLicensedSlots;

extern TrackedDevice trackedDevices[MAX_SUPPORTED_SLOTS];
extern int trackedDeviceCount;

extern NonceRecord nonceHistory[50];
extern int nonceHistoryCount;

extern Preferences prefs;

void initConfig();
void saveSlotLicenses();
void factoryResetDefaults();
String urlEncode(const String& str);

#endif // CONFIG_H
