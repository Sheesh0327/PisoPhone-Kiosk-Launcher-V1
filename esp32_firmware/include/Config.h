#ifndef CONFIG_H
#define CONFIG_H

#include <Arduino.h>
#include <Preferences.h>

// ============================================================================
// HARDWARE CONSTANTS & PIN DEFAULTS
// ============================================================================
extern const char* DEFAULT_SSID;
extern const char* DEFAULT_PASS;
extern const char* DEFAULT_ADMIN_PW;
extern const char* MASTER_CRYPTO_SECRET;

extern const int DEFAULT_UNIVERSAL_COIN_PIN;
extern const int DEFAULT_LED_PIN;
extern const bool DEFAULT_LED_ACTIVE_LOW;
extern const int DEFAULT_RELAY_PIN;
extern const int DEFAULT_PORT;
extern const int HARDWARE_RESET_PIN;
extern const int UDP_DISCOVERY_PORT;
extern const int DEFAULT_MINUTES_PER_COIN;

#define MAX_SUPPORTED_SLOTS 6
#define DEFAULT_MAX_SLOTS 1
#define MAX_TRACKED_DEVICES 12

// ============================================================================
// NVS KEYS
// ============================================================================
extern const char* const NVS_NAMESPACE;
extern const char* const NVS_KEY_MAX_SLOTS;
extern const char* const NVS_KEY_LICENSED;
extern const char* const NVS_KEY_SLOTS_DATA;
extern const char* const NVS_KEY_IPS;

extern const char* const NVS_KEY_WIFI_SSID;
extern const char* const NVS_KEY_WIFI_PASS;
extern const char* const NVS_KEY_U_COIN_PIN;
extern const char* const NVS_KEY_LED_PIN;
extern const char* const NVS_KEY_LED_ACTIVE_LOW;
extern const char* const NVS_KEY_RELAY_PIN;
extern const char* const NVS_KEY_RELAY_ACTIVE_LOW;
extern const char* const NVS_KEY_PORT;
extern const char* const NVS_KEY_MINS_PER_COIN;
extern const char* const NVS_KEY_ADMIN_PW;
extern const char* const NVS_KEY_SHARED_SECRET;
extern const char* const NVS_KEY_P1;
extern const char* const NVS_KEY_P2;
extern const char* const NVS_KEY_MATCH;
extern const char* const NVS_KEY_TOTAL_COINS;
extern const char* const NVS_KEY_TOTAL_EARNINGS;

// ============================================================================
// DATA STRUCTURES
// ============================================================================
struct DeviceConfig {
    String id;
    String ip;
    String name;
};

struct LicenseSlot {
    int slotNum;        // 1 to 12
    String deviceId;    // Canonical hardware ID (e.g., "HW-A1B2C3D4")
    String ip;          // Terminal local DHCP IP (e.g., "192.168.1.50")
    String name;        // Display label (e.g., "PisoPhone 1")
    bool active;        // Whether slot is valid/licensed
};

struct DeviceTelemetry {
    String deviceId;
    String lastKnownIp;
    int timeRemainingSeconds;
    int state;
    int batteryLevel;
    bool isCharging;
    unsigned long lastSeenMs;
    unsigned long long lastNonceTs;
    bool isApp; // True ONLY if request comes from the PisoPhone app (filters out external script coinslot access requests)
};

struct AuthRequest {
    char ip[24];
    int port;
    char actionPath[48];
    char challengePath[48];
    char params[256];
    int timeoutMs;
};

// ============================================================================
// GLOBAL CONFIGURATION & STATE DECLARATIONS
// ============================================================================
extern Preferences prefs;

extern int universalCoinPin;
extern int ledPin;
extern bool ledActiveLow;
extern int relayPin;
extern bool relayActiveLow;

extern String wifiSsid;
extern String wifiPass;
extern String androidIps;
extern String webPassword;
extern String sharedSecret;
extern String macAddressStr;
extern bool is_licensed;
extern int maxLicensedSlots;

extern int targetPort;
extern int minutesPerCoin;

extern const unsigned long ARM_TTL;
extern const unsigned long MAX_SESSION_DURATION;

extern String p1Ip;
extern String p2Ip;
extern int matchMinutes;
extern bool matchActive;
extern String matchStatusMsg;
extern String quickTimeStatusMsg;

// Revenue & Audit
extern uint32_t totalCoinsLifetime;
extern uint32_t totalCoinsSession;
extern float totalEarningsLifetime;
extern float totalEarningsSession;
extern uint32_t lastSavedTotalCoins;
extern float lastSavedTotalEarnings;
extern bool revenueDirty;
extern unsigned long lastCoinChangeTime;
extern const unsigned long REVENUE_SAVE_DELAY_MS;

// Device & Slot Arrays
extern LicenseSlot licenseSlots[MAX_SUPPORTED_SLOTS];
extern DeviceTelemetry trackedDevices[MAX_TRACKED_DEVICES];
extern int trackedDeviceCount;

bool isSlotActive(int slotIdx);
int findSlotIndexForDevice(String devId, String ip);

// ============================================================================
// CONFIGURATION & TIME FUNCTIONS
// ============================================================================
void loadAllConfig();
void loadSlotLicenses();
void saveSlotLicenses();
void syncAndroidIpsFromSlots();

void processRevenuePersistence();

void factoryResetDefaults();
void updateMasterTime(uint64_t ts);
uint64_t getCurrentMasterTimeMs();

bool parseDeviceEntry(const String& entry, DeviceConfig& out);
bool areDefaultCredentialsActive();

#endif // CONFIG_H
