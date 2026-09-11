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

extern const int DEFAULT_COIN_PIN;
extern const int DEFAULT_UNIVERSAL_COIN_PIN;
extern const int DEFAULT_LED_PIN;
extern const bool DEFAULT_LED_ACTIVE_LOW;
extern const int DEFAULT_RELAY_PIN;
extern const int DEFAULT_PORT;
extern const float DEFAULT_PRICE;
extern const int DEFAULT_MINUTES;
extern const int DEFAULT_DEBOUNCE;
extern const int HARDWARE_RESET_PIN;
extern const int UDP_DISCOVERY_PORT;

#define MAX_SUPPORTED_SLOTS 5
#define DEFAULT_MAX_SLOTS 5
#define MAX_TRACKED_DEVICES 16

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
    String ip;          // Terminal local IP (e.g., "192.168.4.2")
    String name;        // Display label (e.g., "PisoPhone 1")
    uint64_t expiresAt; // Expiration timestamp in ms
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

extern int coinPin;
extern int universalCoinPin;
extern int ledPin;
extern bool ledActiveLow;
extern int relayPin;
extern bool relayActiveLow;
extern int relayMode;

extern String wifiSsid;
extern String wifiPass;
extern String androidIps;
extern String webPassword;
extern String sharedSecret;
extern String macAddressStr;
extern bool is_licensed;
extern int maxLicensedSlots;

extern int targetPort;
extern float coinPrice;
extern int minutesPerCoin;
extern int lockoutDebounceMs;

extern String armedIp;
extern unsigned long armedUntil;
extern unsigned long sessionStartTime;
extern const unsigned long ARM_TTL;
extern const unsigned long MAX_SESSION_DURATION;

extern String lastArmedDeviceId;
extern String lastArmedIp;
extern unsigned long lastArmedTimeMs;
extern String pulseTrainDeviceId;
extern String pulseTrainDeviceIp;
extern bool pulseTrainWasArmed;
extern unsigned long pulseTrainStartTime;
extern bool pendingWsGracefulClose;
extern unsigned long pendingWsGracefulCloseUntil;

extern String p1Ip;
extern String p2Ip;
extern int matchMinutes;
extern String matchStatusMsg;
extern String quickTimeStatusMsg;

// Credit Vault
extern int monthlyCredits;
extern int annualCredits;
extern int testCredits;

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

// ============================================================================
// CONFIGURATION & TIME FUNCTIONS
// ============================================================================
void loadSlotLicenses();
void saveSlotLicenses();
void syncAndroidIpsFromSlots();

void loadCreditVault();
void saveCreditVault();
void processRevenuePersistence();

void factoryResetDefaults();
void updateMasterTime(uint64_t ts);
uint64_t getCurrentMasterTimeMs();

bool parseDeviceEntry(String entry, DeviceConfig& out);
bool areDefaultCredentialsActive();

#endif // CONFIG_H
