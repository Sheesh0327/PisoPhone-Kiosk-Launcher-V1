#ifndef CONFIG_H
#define CONFIG_H

#include <Arduino.h>
#include "SlotLicense.h"
#include <Preferences.h>
#include <functional>

// ============================================================================
// HARDWARE CONSTANTS & PIN DEFAULTS
// ============================================================================

extern const int DEFAULT_UNIVERSAL_COIN_PIN;
extern const int DEFAULT_LED_PIN;
extern const bool DEFAULT_LED_ACTIVE_LOW;
extern const int DEFAULT_RELAY_PIN;
extern const int DEFAULT_PORT;
extern const int HARDWARE_RESET_PIN;
extern const int UDP_DISCOVERY_PORT;
extern const int DEFAULT_MINUTES_PER_COIN;

// The most phones any box can pair (SlotLicense.h: 2 slots are free, each of the others needs a license key). Room is also kept
// for the phones that have only said hello (identify, a heartbeat before pairing): MAX_TRACKED_DEVICES.
#define MAX_SUPPORTED_SLOTS 12
static_assert(MAX_SUPPORTED_SLOTS == slotlicense::MAX_SLOTS, "the slot limit and the license limit must agree");
#define MAX_TRACKED_DEVICES 24

// ============================================================================
// NVS KEYS
// ============================================================================
extern const char* const NVS_NAMESPACE;
extern const char* const NVS_KEY_SLOTS_DATA;
extern const char* const NVS_KEY_IPS;

extern const char* const NVS_KEY_WIFI_SSID;
extern const char* const NVS_KEY_WIFI_PASS;
extern const char* const NVS_KEY_KIOSK_WIFI_PASS;
extern const char* const NVS_KEY_KIOSK_WIFI_SSID;
extern const char* const NVS_KEY_U_COIN_PIN;
extern const char* const NVS_KEY_LED_PIN;
extern const char* const NVS_KEY_LED_ACTIVE_LOW;
extern const char* const NVS_KEY_RELAY_PIN;
extern const char* const NVS_KEY_RELAY_ACTIVE_LOW;
extern const char* const NVS_KEY_PULSE_GUARD;
extern const char* const NVS_KEY_PULSE_MIN_MS;
extern const char* const NVS_KEY_PORT;
extern const char* const NVS_KEY_MINS_PER_COIN;
extern const char* const NVS_KEY_ADMIN_PW;
extern const char* const NVS_KEY_ADMIN_PW_CHANGED;
extern const char* const NVS_KEY_SETUP_AP_PASS;
extern const char* const NVS_KEY_SHARED_SECRET;
extern const char* const NVS_KEY_LEGACY_KEY;
extern const char* const NVS_KEY_P1;
extern const char* const NVS_KEY_P2;
extern const char* const NVS_KEY_MATCH;
extern const char* const NVS_KEY_TOTAL_COINS;
extern const char* const NVS_KEY_TOTAL_CENTAVOS;

// ============================================================================
// DATA STRUCTURES
// ============================================================================
struct DeviceConfig {
    String id;
    String ip;
    String name;
};

struct PhoneSlot {
    int slotNum;     // 1 to MAX_SUPPORTED_SLOTS
    String deviceId; // Canonical hardware ID (e.g., "HW-A1B2C3D4")
    String ip;       // Terminal local DHCP IP (e.g., "192.168.1.50")
    String name;     // Display label (e.g., "PisoPhone 1")
    bool active;     // Always true: every slot is available
};

struct DeviceTelemetry {
    String deviceId;
    String lastKnownIp;
    String deviceName;
    int timeRemainingSeconds;
    int state;
    int batteryLevel;
    bool isCharging;
    unsigned long lastSeenMs;
    unsigned long timeReportedMs; // when timeRemainingSeconds was last reported by the phone
    unsigned long long lastNonceTs;
    int restarts24h; // how often the phone's app restarted after a crash in the last 24 hours (reported by the phone; 0 if unknown)
    bool
        isApp; // True ONLY if request comes from the PisoPhone app (filters out external script coinslot access requests)
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
// Fake-pulse protection: count a coin pulse only when the line stayed low for at least coinPulseMinMs (off by default; see PulseGuard.h)
extern bool coinPulseGuard;
extern int coinPulseMinMs;

extern String wifiSsid;
extern String wifiPass;
extern String
    kioskWifiPass; // the rental phones' Wi-Fi password, given by the router (carried by the "Set up a phone" link)
extern String kioskWifiSsid; // and the name of that network (each router has its own; empty: the shared name PisoKiosk)
extern String androidIps;
extern String webPassword;
extern bool adminPwChanged;                // false until the default admin password is replaced
void runConfigMigrations();                // brings saved settings to this firmware's format (ConfigMigration.h)
void loadCredentials();                    // reads the admin and setup-AP passwords (the migrations make them)
String getSharedSecret();                  // the key box<->phone traffic uses right now
void setSharedSecret(const String& value); // sets this box's own secret (see SecretMode.h)
String getBoxSecret();                     // this box's own secret, whatever mode the box is in
String getLegacySharedSecret();            // old shared key (legacy mode); a new box secret may never equal it
bool isLegacyKeyMode();                    // true until the operator switches the box to its own key
void switchToOwnKey();                     // leaves legacy mode for good
void loadSecretMode();                     // reads the box secret and legacy-mode flag
// Slot licenses (SlotLicense.h): how many phones this box may pair. Opened only by a key the owner signed for THIS box.
int getDeviceCap();       // 2 free slots plus one for each license key used (never fewer than the grandfathered floor)
int getLicenseKeyCount(); // license keys used on this box
int getLicenseFloor();    // the slots this box keeps from before licensing (0 on a new box)
String getLicenseIds();   // the numbers of those keys, "id,id,..."
bool addLicenseKey(const String& id); // saves one more used key (callers have verified it: SlotLicense.h)
int pairedDeviceCount();              // phones paired right now
void loadLicenses();                  // reads the saved keys and the floor (a box with none has the 2 free slots)
void settleLicenseFloor(); // first start with licensing: records how many phones are paired now, once (after loadSlots)
extern String macAddressStr;

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
extern uint32_t totalCentavosLifetime;
extern uint32_t totalCentavosSession;
extern uint32_t lastSavedTotalCoins;
extern uint32_t lastSavedTotalCentavos;
extern bool revenueDirty;
extern unsigned long lastCoinChangeTime;
extern const unsigned long REVENUE_SAVE_DELAY_MS;
extern const unsigned long REVENUE_SAVE_MAX_DEFER_MS;
extern unsigned long revenueDirtySinceMs;

// Device & Slot Arrays
extern PhoneSlot phoneSlots[MAX_SUPPORTED_SLOTS];
extern DeviceTelemetry trackedDevices[MAX_TRACKED_DEVICES];
extern int trackedDeviceCount;

bool isSlotActive(int slotIdx);
int findSlotIndexForDevice(String devId, String ip);

// ============================================================================
// CONFIGURATION & TIME FUNCTIONS
// ============================================================================
void loadAllConfig();
void loadSlots();
void saveSlots();
void syncAndroidIpsFromSlots();

void processRevenuePersistence();
void flushRevenueNow();

// Operator reset (default): keeps lifetime revenue and the vendor split (OwnerData.h).
// ownerWipe = true (super-admin request only) erases those as well.
void factoryResetDefaults(bool ownerWipe = false);
void updateMasterTime(uint64_t ts, const String& sourceId = "");
uint64_t getCurrentMasterTimeMs();
String boxTimeJsonField(); // ,"server_time_ms":<ms> (the box's clock for phones to sign with), or "" while it has none
String generateTxId(const char* prefix = "tx-");

bool parseDeviceEntry(const String& entry, DeviceConfig& out);
void forEachConfiguredDevice(std::function<bool(const DeviceConfig&)> callback);

#endif // CONFIG_H
