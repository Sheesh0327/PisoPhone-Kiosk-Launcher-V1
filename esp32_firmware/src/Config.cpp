// Persistent configuration (NVS): defaults, loading and saving of pins, Wi-Fi, passwords,
// slots and revenue counters, plus the locked accessor for the shared secret.

#include "Config.h"
#if PISO_HAS_PHONES
#include "PhoneProfileStore.h"
#endif
#include "PulseGuard.h"
#include "PinRules.h"
#include "OwnerData.h"
#if PISO_HAS_PHONES
#include "DeviceManager.h"
#endif
#include "HardwareManager.h"
#include "SuperAdminManager.h"
#include "PaymentQueueManager.h"
#include "RevenueVault.h"
#include "OrphanPayments.h"
#include "Diagnostics.h"
#include "Money.h"
#include "CredGen.h"
#include "SecretMode.h"
#include "TxId.h"
#include "ConfigMigration.h"
#include <esp_random.h>
#include <bootloader_random.h>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>

// ============================================================================
// HARDWARE CONSTANTS & PIN DEFAULTS DEFINITION
// ============================================================================
// DEPRECATED shared key of firmware before per-box secrets. Only used while a box is in legacy mode and
// to refuse it as a new box secret. Remove with the legacy path once every phone is re-provisioned.
#if PISO_HAS_PHONES
static const char* LEGACY_CRYPTO_SECRET = "PISOPHONE_HMAC_MASTER_KEY";
#else
static const char* LEGACY_CRYPTO_SECRET = ""; // (no old shared key to refuse: this image never had one)
#endif

// Pin defaults are per board and come from -D flags in envs/*.ini (PISO_PIN_*). A wrong default
// on a classic ESP32 (GPIO 6-11 are the flash bus) crashes at boot, so there is no fallback:
// a new board environment must state its pins. Values saved in NVS still override these.
#if !defined(PISO_PIN_COIN) || !defined(PISO_PIN_LED) || !defined(PISO_PIN_RELAY) || !defined(PISO_PIN_RESET)
#error "Define PISO_PIN_COIN, PISO_PIN_LED, PISO_PIN_RELAY and PISO_PIN_RESET in the PlatformIO env build_flags."
#endif
const int DEFAULT_UNIVERSAL_COIN_PIN = PISO_PIN_COIN;
const int DEFAULT_LED_PIN = PISO_PIN_LED;
const bool DEFAULT_LED_ACTIVE_LOW = false;
const int DEFAULT_RELAY_PIN = PISO_PIN_RELAY;
const int DEFAULT_PORT = 8080;
const int HARDWARE_RESET_PIN = PISO_PIN_RESET;
#if PISO_HAS_PHONES
const int UDP_DISCOVERY_PORT = 8888;
#endif
const int DEFAULT_MINUTES_PER_COIN = 6;

// ============================================================================
// GLOBAL VARIABLES DEFINITION
// ============================================================================
Preferences prefs;

int universalCoinPin = DEFAULT_UNIVERSAL_COIN_PIN;
int ledPin = DEFAULT_LED_PIN;
bool ledActiveLow = DEFAULT_LED_ACTIVE_LOW;
int relayPin = DEFAULT_RELAY_PIN;
bool relayActiveLow = false;
bool coinPulseGuard = true;
int coinPulseMinMs = PULSE_MIN_MS_DEFAULT;

String wifiSsid = setupgate::DEFAULT_WIFI_SSID; // the hidden network the router setup script creates for the box
String wifiPass = setupgate::DEFAULT_WIFI_PASSWORD;
#if PISO_HAS_PHONES
String kioskWifiPass = "";
String kioskWifiSsid = ""; // this router's own name for it (empty: the phones use the shared name PisoKiosk)
String androidIps = "";
#endif
String webPassword = "";
bool adminPwChanged = false;
// The auth worker task reads the secret while the main loop can change it (settings save, factory
// reset). Every access goes through these accessors so a String is never reallocated mid-read.
static String sharedSecretValue = "";
static bool legacyKeyMode = false;
static StaticSemaphore_t sharedSecretMutexBuf;
static SemaphoreHandle_t sharedSecretMutex = xSemaphoreCreateMutexStatic(&sharedSecretMutexBuf);

String getSharedSecret() {
    xSemaphoreTake(sharedSecretMutex, portMAX_DELAY);
    String copy = legacyKeyMode ? String(LEGACY_CRYPTO_SECRET) : sharedSecretValue;
    xSemaphoreGive(sharedSecretMutex);
    return copy;
}

String getBoxSecret() {
    xSemaphoreTake(sharedSecretMutex, portMAX_DELAY);
    String copy = sharedSecretValue;
    xSemaphoreGive(sharedSecretMutex);
    return copy;
}

String getLegacySharedSecret() {
    return String(LEGACY_CRYPTO_SECRET);
}

bool isLegacyKeyMode() {
    xSemaphoreTake(sharedSecretMutex, portMAX_DELAY);
    bool legacy = legacyKeyMode;
    xSemaphoreGive(sharedSecretMutex);
    return legacy;
}

void setSharedSecret(const String& value) {
    xSemaphoreTake(sharedSecretMutex, portMAX_DELAY);
    sharedSecretValue = value;
    xSemaphoreGive(sharedSecretMutex);
}
String macAddressStr = "";

int targetPort = DEFAULT_PORT;
int minutesPerCoin = DEFAULT_MINUTES_PER_COIN;

const unsigned long ARM_TTL = 20000;
const unsigned long MAX_SESSION_DURATION = 120000;

#if PISO_HAS_PHONES
String p1Ip = "";
String p2Ip = "";
int matchMinutes = 15;
bool matchActive = false;
String matchStatusMsg = "";
String quickTimeStatusMsg = "";
#endif

// Credit Vault
// Revenue & Audit
uint32_t totalCoinsLifetime = 0;
uint32_t totalWifiCoinsLifetime = 0;
uint32_t totalPhoneCoinsLifetime = 0;
uint32_t totalCoinsSession = 0;
uint32_t totalCentavosLifetime = 0;
uint32_t totalCentavosSession = 0;
uint32_t lastSavedTotalCoins = 0;
uint32_t lastSavedTotalWifiCoins = 0;
uint32_t lastSavedTotalPhoneCoins = 0;
uint32_t lastSavedTotalCentavos = 0;
bool revenueDirty = false;
unsigned long lastCoinChangeTime = 0;
const unsigned long REVENUE_SAVE_DELAY_MS = 5000;
// ... but never later than this after the first unsaved coin, so a customer feeding coins one after another cannot keep the
// counters out of flash (a power cut would then lose them from the lifetime total, the vault and the router's reconciliation)
const unsigned long REVENUE_SAVE_MAX_DEFER_MS = 20000;
unsigned long revenueDirtySinceMs = 0;

#if PISO_HAS_PHONES
// Device & Slot Arrays
PhoneSlot phoneSlots[MAX_SUPPORTED_SLOTS];
DeviceTelemetry trackedDevices[MAX_TRACKED_DEVICES];
int trackedDeviceCount = 0;
#endif

// Monotonic Master Clock derived from synchronized Android telemetry timestamps
static uint64_t lastMasterTimestamp = 0;
static unsigned long lastMasterMillis = 0;

uint64_t getCurrentMasterTimeMs() {
    if (lastMasterTimestamp > 0) {
        return lastMasterTimestamp + (uint64_t)(millis() - lastMasterMillis);
    }
    return 0;
}

// The box's clock as a JSON field, so a phone whose own clock is wrong can sign with the box's time instead of being
// refused as stale. Empty until some phone has set the master clock.
String boxTimeJsonField() {
    uint64_t t = getCurrentMasterTimeMs();
    if (t == 0) return "";
    char buf[48];
    snprintf(buf, sizeof(buf), ",\"server_time_ms\":%llu", (unsigned long long)t);
    return String(buf);
}

// Ids come from a boot counter kept in flash, a per-boot sequence number and random salt (TxId.h), so they never
// repeat between reboots and do not depend on the phone clock having been synced.
static uint32_t txBootCount = 0;
static uint32_t txSeq = 0;

static void initTxIdBootCounter() {
    Preferences counter;
    if (!counter.begin("tx_ctr", false)) return;
    txBootCount = counter.getULong("boots", 0) + 1;
    counter.putULong("boots", txBootCount);
    counter.end();
}

String generateTxId(const char* prefix) {
    uint32_t seq = __atomic_add_fetch(&txSeq, 1, __ATOMIC_RELAXED);
    return String(txid::make(prefix, txBootCount, seq, esp_random()).c_str());
}

static const uint64_t MASTER_CLOCK_WINDOW_MS = 300000ULL;
static const uint64_t MASTER_CLOCK_AGREE_MS = 30000ULL;
static uint64_t outlierTimestamp = 0;
static unsigned long outlierMillis = 0;
static String outlierSource = "";

static uint64_t absDiff(uint64_t a, uint64_t b) {
    return a > b ? a - b : b - a;
}

void updateMasterTime(uint64_t ts, const String& sourceId) {
    if (ts == 0) return;
    unsigned long nowMs = millis();
    uint64_t current = getCurrentMasterTimeMs();

    if (current == 0 || absDiff(ts, current) <= MASTER_CLOCK_WINDOW_MS) {
        if (ts > current) {
            lastMasterTimestamp = ts;
            lastMasterMillis = nowMs;
        }
        return;
    }

    // A single device with a wrong clock must not drag the master clock away from everyone
    // else (which would make every other phone fail the replay window). Re-sync only when a
    // second, different device independently reports the same outlier time.
    if (outlierTimestamp > 0 && sourceId.length() > 0 && sourceId != outlierSource &&
        (uint64_t)(nowMs - outlierMillis) <= MASTER_CLOCK_WINDOW_MS) {
        uint64_t projected = outlierTimestamp + (uint64_t)(nowMs - outlierMillis);
        if (absDiff(ts, projected) <= MASTER_CLOCK_AGREE_MS) {
            diagLog("[CLOCK] Master clock re-synced (%llu -> %llu) after agreement from '%s' and '%s'.\n", current, ts,
                    outlierSource.c_str(), sourceId.c_str());
            lastMasterTimestamp = ts;
            lastMasterMillis = nowMs;
            outlierTimestamp = 0;
            outlierSource = "";
            return;
        }
    }
    outlierTimestamp = ts;
    outlierMillis = nowMs;
    outlierSource = sourceId;
}

#if PISO_HAS_PHONES
bool parseDeviceEntry(const String& rawEntry, DeviceConfig& out) {
    String entry = rawEntry;
    entry.trim();
    if (entry.length() == 0) return false;
    out.id = "";
    out.ip = "";
    out.name = "";

    int pipe1 = entry.indexOf('|');
    int pipe2 = (pipe1 != -1) ? entry.indexOf('|', pipe1 + 1) : -1;

    if (pipe1 != -1 && pipe2 != -1) {
        String p1 = entry.substring(0, pipe1);
        String p2 = entry.substring(pipe1 + 1, pipe2);
        String p3 = entry.substring(pipe2 + 1);
        p1.trim();
        p2.trim();
        p3.trim();

        if (p1.indexOf('.') != -1 && p2.indexOf('.') == -1) {
            out.ip = p1;
            out.id = p2;
            out.name = p3;
        } else {
            out.id = p1;
            out.ip = p2;
            out.name = p3;
        }
    } else if (pipe1 != -1) {
        String p1 = entry.substring(0, pipe1);
        String p2 = entry.substring(pipe1 + 1);
        p1.trim();
        p2.trim();

        if (p2.indexOf('.') != -1) {
            out.id = p1;
            out.ip = p2;
            out.name = "";
        } else if (p1.indexOf('.') != -1) {
            out.id = "";
            out.ip = p1;
            out.name = p2;
        } else {
            out.id = p1;
            out.ip = p2;
            out.name = "";
        }
    } else {
        if (entry.indexOf('.') != -1) {
            out.id = "";
            out.ip = entry;
            out.name = "";
        } else {
            out.id = entry;
            out.ip = "";
            out.name = "";
        }
    }

    out.id.trim();
    out.ip.trim();
    out.name.trim();

    if (out.ip.length() < 7 || out.ip.indexOf('.') == -1 || out.ip == "127.0.0.1" || out.ip == "0.0.0.0") {
        return false;
    }
    return true;
}

void forEachConfiguredDevice(std::function<bool(const DeviceConfig&)> callback) {
    if (!callback) return;
    int startIdx = 0;
    while (startIdx < androidIps.length()) {
        int comma = androidIps.indexOf(',', startIdx);
        if (comma == -1) comma = androidIps.length();
        String entry = androidIps.substring(startIdx, comma);
        entry.trim();
        if (entry.length() > 0) {
            DeviceConfig cfg;
            if (parseDeviceEntry(entry, cfg)) {
                if (!callback(cfg)) break;
            }
        }
        startIdx = comma + 1;
    }
}
#endif

const char* const NVS_NAMESPACE = "kiosk_cfg";
#if PISO_HAS_PHONES
const char* const NVS_KEY_SLOTS_DATA = "slots_data";
const char* const NVS_KEY_IPS = "ips";
#endif

const char* const NVS_KEY_WIFI_SSID = "wifi_ssid";
const char* const NVS_KEY_WIFI_PASS = "wifi_pass";
#if PISO_HAS_PHONES
const char* const NVS_KEY_KIOSK_WIFI_PASS = "kiosk_wifi";
const char* const NVS_KEY_KIOSK_WIFI_SSID = "kiosk_ssid";
#endif
const char* const NVS_KEY_U_COIN_PIN = "u_coin_pin";
const char* const NVS_KEY_LED_PIN = "led_pin";
const char* const NVS_KEY_LED_ACTIVE_LOW = "led_act_low";
const char* const NVS_KEY_RELAY_PIN = "relay_pin";
const char* const NVS_KEY_RELAY_ACTIVE_LOW = "relay_act_low";
const char* const NVS_KEY_PULSE_GUARD = "pulse_guard";
const char* const NVS_KEY_PULSE_MIN_MS = "pulse_min_ms";
const char* const NVS_KEY_PORT = "target_port";
const char* const NVS_KEY_MINS_PER_COIN = "mins_per_coin";
const char* const NVS_KEY_ADMIN_PW = cfgmig::K_ADMIN_PW;
const char* const NVS_KEY_ADMIN_PW_CHANGED = cfgmig::K_ADMIN_PW_CHANGED;
const char* const NVS_KEY_SETUP_AP_PASS = cfgmig::K_SETUP_AP_PASS;
const char* const NVS_KEY_SHARED_SECRET = cfgmig::K_SHARED_SECRET;
const char* const NVS_KEY_LEGACY_KEY = cfgmig::K_LEGACY_KEY;
#if PISO_HAS_PHONES
const char* const NVS_KEY_LICENSES = "lic_ids";    // ownerdata::K_LICENSES: survives an operator's factory reset
const char* const NVS_KEY_LIC_FLOOR = "lic_floor"; // ownerdata::K_LIC_FLOOR: the phones paired when licensing arrived
const char* const NVS_KEY_P1 = "p1_ip";
const char* const NVS_KEY_P2 = "p2_ip";
const char* const NVS_KEY_MATCH = "match_minutes";
#endif
const char* const NVS_KEY_TOTAL_COINS = "total_coins";
const char* const NVS_KEY_TOTAL_WIFI_COINS = "tot_wifi_coins";
const char* const NVS_KEY_TOTAL_PHONE_COINS = "tot_phone_coins";
const char* const NVS_KEY_TOTAL_CENTAVOS = cfgmig::K_TOTAL_CENTAVOS;

#if PISO_HAS_PHONES
void syncAndroidIpsFromSlots() {
    String newIps = "";
    newIps.reserve(MAX_SUPPORTED_SLOTS * 48); // Pre-allocate to prevent heap fragmentation
    for (int i = 0; i < MAX_SUPPORTED_SLOTS; i++) {
        if (phoneSlots[i].deviceId.length() > 0 && phoneSlots[i].ip.length() > 0) {
            if (newIps.length() > 0) newIps += ",";
            newIps += phoneSlots[i].deviceId + "|" + phoneSlots[i].ip + "|" + phoneSlots[i].name;
        }
    }
    androidIps = newIps;
}

void saveSlots() {
    prefs.begin(NVS_NAMESPACE, false);
    String raw = "";
    raw.reserve(MAX_SUPPORTED_SLOTS * 64); // Pre-allocate approx 64 bytes per slot
    for (int i = 0; i < MAX_SUPPORTED_SLOTS; i++) {
        if (i > 0) raw += ";";
        raw += String(phoneSlots[i].slotNum) + "|" + phoneSlots[i].deviceId + "|" + phoneSlots[i].ip + "|" +
               phoneSlots[i].name + "|" + (phoneSlots[i].active ? "1" : "0");
    }
    prefs.putString(NVS_KEY_SLOTS_DATA, raw);
    syncAndroidIpsFromSlots();
    prefs.putString(NVS_KEY_IPS, androidIps);
    prefs.end();
}

void loadSlots() {
    prefs.begin(NVS_NAMESPACE, false);
    for (int i = 0; i < MAX_SUPPORTED_SLOTS; i++) {
        phoneSlots[i].slotNum = i + 1;
        phoneSlots[i].deviceId = "";
        phoneSlots[i].ip = "";
        phoneSlots[i].name = "PisoPhone " + String(i + 1);
        phoneSlots[i].active = true;
    }

    String raw = prefs.getString(NVS_KEY_SLOTS_DATA, "");
    if (raw.length() > 0) {
        int startIdx = 0;
        int slotIdx = 0;
        while (startIdx < raw.length() && slotIdx < MAX_SUPPORTED_SLOTS) {
            int semi = raw.indexOf(';', startIdx);
            if (semi == -1) semi = raw.length();
            String item = raw.substring(startIdx, semi);
            item.trim();
            if (item.length() > 0) {
                int p1 = item.indexOf('|');
                int p2 = (p1 != -1) ? item.indexOf('|', p1 + 1) : -1;
                int p3 = (p2 != -1) ? item.indexOf('|', p2 + 1) : -1;
                int p4 = (p3 != -1) ? item.indexOf('|', p3 + 1) : -1;

                if (p1 != -1 && p2 != -1 && p3 != -1) {
                    int sNum = item.substring(0, p1).toInt();
                    if (sNum >= 1 && sNum <= MAX_SUPPORTED_SLOTS) {
                        int idx = sNum - 1;
                        phoneSlots[idx].slotNum = sNum;
                        phoneSlots[idx].deviceId = item.substring(p1 + 1, p2);
                        phoneSlots[idx].ip = item.substring(p2 + 1, p3);
                        phoneSlots[idx].name = item.substring(p3 + 1, (p4 != -1) ? p4 : item.length());

                        phoneSlots[idx].active = true; // saved flag is ignored: every slot is available
                    }
                }
            }
            startIdx = semi + 1;
            slotIdx++;
        }
    }
    prefs.end();
    syncAndroidIpsFromSlots();
}
#endif

// Secrets and passwords are made before Wi-Fi starts, when esp_fill_random is only pseudo-random (Espressif's documentation):
// switch the chip's own entropy source on while they are made.
static void fillRandomBytes(uint8_t* buf, size_t len) {
    bootloader_random_enable();
    esp_fill_random(buf, len);
    bootloader_random_disable();
}

// Settings storage for the migrations: a thin wrapper over an open Preferences namespace.
struct PrefsStore {
    bool isKey(const char* k) { return prefs.isKey(k); }
    std::string getString(const char* k, const std::string& d) { return prefs.getString(k, d.c_str()).c_str(); }
    void putString(const char* k, const std::string& v) { prefs.putString(k, v.c_str()); }
    bool getBool(const char* k, bool d) { return prefs.getBool(k, d); }
    void putBool(const char* k, bool v) { prefs.putBool(k, v); }
    uint32_t getUInt(const char* k, uint32_t d) { return prefs.getULong(k, d); }
    void putUInt(const char* k, uint32_t v) { prefs.putULong(k, v); }
    int32_t getInt(const char* k, int32_t d) { return prefs.getInt(k, d); }
    void putInt(const char* k, int32_t v) { prefs.putInt(k, v); }
    float getFloat(const char* k, float d) { return prefs.getFloat(k, d); }
    void remove(const char* k) { prefs.remove(k); }
};

// Brings the saved settings up to this firmware's format (ConfigMigration.h). Runs before anything reads them.
void runConfigMigrations() {
    cfgmig::Env env;
    env.fillRandom = fillRandomBytes;
    env.legacySecret = LEGACY_CRYPTO_SECRET;
    prefs.begin(NVS_NAMESPACE, false);
    PrefsStore store;
    cfgmig::Result r = cfgmig::runAll(store, env);
    prefs.end();
    if (r.storeIsNewer) {
        Serial.printf("[💾 CONFIG] Settings are from a newer firmware (format %u > %u); leaving them untouched.\n",
                      (unsigned)r.from, (unsigned)cfgmig::CURRENT_VERSION);
    } else if (r.ranAny) {
        Serial.printf("[💾 CONFIG] Settings format %u -> %u\n", (unsigned)r.from, (unsigned)r.to);
    }
}

// Reads the box's secret and whether it is still in legacy mode (the values exist after the migrations).
void loadSecretMode() {
    prefs.begin(NVS_NAMESPACE, false);
    String secret = prefs.getString(NVS_KEY_SHARED_SECRET, "");
    bool legacy = prefs.getBool(NVS_KEY_LEGACY_KEY, false);
    prefs.end();

    xSemaphoreTake(sharedSecretMutex, portMAX_DELAY);
    sharedSecretValue = secret;
    legacyKeyMode = legacy;
    xSemaphoreGive(sharedSecretMutex);
    if (legacy) {
        Serial.println(
            "[🔐 KEY] Legacy mode: still using the old shared key. Switch to this box's own key in the dashboard.");
    }
}

#if PISO_HAS_PHONES
static String licenseIds = ""; // the numbers of the license keys used on this box (SlotLicense.h), "id,id,..."
static int licenseFloor =
    0; // the phones this box had paired when licensing arrived: it never offers fewer slots than that

String getLicenseIds() {
    return licenseIds;
}

int getLicenseFloor() {
    return licenseFloor;
}

int getLicenseKeyCount() {
    return slotlicense::usedKeys(licenseIds.c_str());
}

int getDeviceCap() {
    return slotlicense::capWithFloor(getLicenseKeyCount(), licenseFloor);
}

int pairedDeviceCount() {
    int n = 0;
    for (int i = 0; i < MAX_SUPPORTED_SLOTS; i++) {
        if (phoneSlots[i].deviceId.length() > 0) n++;
    }
    return n;
}

void loadLicenses() {
    prefs.begin(NVS_NAMESPACE, false);
    String saved = prefs.getString(NVS_KEY_LICENSES, "");
    licenseFloor = prefs.getInt(NVS_KEY_LIC_FLOOR, 0);
    prefs.end();
    // (re-read through the parser: anything that is not a key number is dropped, and so are repeats)
    licenseIds = slotlicense::joinIds(slotlicense::splitIds(saved.c_str())).c_str();
    if (licenseFloor < 0 || licenseFloor > slotlicense::MAX_SLOTS) licenseFloor = 0;
}

// The first start with licensing: a box already in a shop keeps as many slots as it has phones paired, so the shop can still replace
// a phone. Done once (the number is kept, and survives an operator's factory reset: OwnerData.h); a new box records 0.
void settleLicenseFloor() {
    prefs.begin(NVS_NAMESPACE, false);
    bool known = prefs.isKey(NVS_KEY_LIC_FLOOR);
    if (!known) {
        int paired = pairedDeviceCount();
        if (paired < 0) paired = 0;
        if (paired > slotlicense::MAX_SLOTS) paired = slotlicense::MAX_SLOTS;
        if (prefs.putInt(NVS_KEY_LIC_FLOOR, paired) > 0) {
            licenseFloor = paired;
            diagLog("[LICENSE] Licensing starts: this box keeps %d slot(s), the phones it has paired now.\n", paired);
        }
    }
    prefs.end();
}

bool addLicenseKey(const String& id) {
    std::string have = licenseIds.c_str();
    std::string idNorm = slotlicense::normalizeId(id.c_str());
    if (idNorm.empty() || slotlicense::hasId(have, idNorm) || slotlicense::usedKeys(have) >= slotlicense::MAX_KEYS)
        return false;
    String next = licenseIds.length() > 0 ? licenseIds + "," + idNorm.c_str() : String(idNorm.c_str());
    prefs.begin(NVS_NAMESPACE, false);
    size_t written = prefs.putString(NVS_KEY_LICENSES, next);
    prefs.end();
    if (written == 0) return false;
    licenseIds = next;
    diagLog("[LICENSE] License key %s used: this box may now pair up to %d phones.\n", idNorm.c_str(), getDeviceCap());
    return true;
}
#endif

void switchToOwnKey() {
    prefs.begin(NVS_NAMESPACE, false);
    prefs.putBool(NVS_KEY_LEGACY_KEY, false);
    prefs.end();
    xSemaphoreTake(sharedSecretMutex, portMAX_DELAY);
    legacyKeyMode = false;
    xSemaphoreGive(sharedSecretMutex);
#if PISO_HAS_PHONES
    diagLog("[🔐 KEY] Switched to this box's own key. Re-provision each phone with the new secret.");
#else
    diagLog("[🔐 KEY] Switched to this box's own key.");
#endif
}

// Reads the admin and setup-AP passwords. Every box has its own, made by the migrations the first time it starts
// (and after a factory reset); boxes already in the field keep the password they have.
void loadCredentials() {
    prefs.begin(NVS_NAMESPACE, false);
    webPassword = prefs.getString(NVS_KEY_ADMIN_PW, "");
    adminPwChanged = prefs.getBool(NVS_KEY_ADMIN_PW_CHANGED, false);
    prefs.end();

    if (!adminPwChanged) {
        // The box starts on the published default password so it can be set up and reset without a serial monitor.
        Serial.println("\n================ FIRST-TIME SETUP ================");
        Serial.println(" This box is using its default password (see the setup guide).");
        Serial.println(" Change the admin password in Settings; coins stay blocked until you do.");
        Serial.println("==================================================\n");
    }
}

void loadAllConfig() {
    // 1. Load phone slots and terminal allocations safely
    runConfigMigrations(); // first: brings stored settings to this firmware's format
    loadSecretMode();
#if PISO_HAS_PHONES
    loadLicenses();
#endif
    initTxIdBootCounter();
#if PISO_HAS_PHONES
    loadSlots();
    settleLicenseFloor(); // (needs the phone slots: it records how many phones are paired when licensing starts)
#endif
    loadSuperAdminConfig();
    loadCredentials();

    // 2. Open NVS for all kiosk configuration & lifetime vault revenue counters
    prefs.begin(NVS_NAMESPACE, false);
    wifiSsid = prefs.getString(NVS_KEY_WIFI_SSID, wifiSsid);
    wifiPass = prefs.getString(NVS_KEY_WIFI_PASS, wifiPass);
#if PISO_HAS_PHONES
    kioskWifiPass = prefs.getString(NVS_KEY_KIOSK_WIFI_PASS, "");
    kioskWifiSsid = prefs.getString(NVS_KEY_KIOSK_WIFI_SSID, "");
#endif
    universalCoinPin = prefs.getInt(NVS_KEY_U_COIN_PIN, universalCoinPin);
    ledPin = prefs.getInt(NVS_KEY_LED_PIN, ledPin);
    ledActiveLow = prefs.getBool(NVS_KEY_LED_ACTIVE_LOW, DEFAULT_LED_ACTIVE_LOW);
    relayPin = prefs.getInt(NVS_KEY_RELAY_PIN, relayPin);
    if (pinrules::problem(pinrules::THIS_CHIP, universalCoinPin, relayPin, ledPin, HARDWARE_RESET_PIN)) {
        // A stored pin this board cannot use (saved by an older firmware) would crash the box at every start: use the defaults.
        Serial.printf("[CONFIG] Stored pins coin=%d relay=%d led=%d cannot be used: back to the defaults.\n",
                      universalCoinPin, relayPin, ledPin);
        universalCoinPin = DEFAULT_UNIVERSAL_COIN_PIN;
        relayPin = DEFAULT_RELAY_PIN;
        ledPin = DEFAULT_LED_PIN;
    }

    targetPort = prefs.getInt(NVS_KEY_PORT, targetPort);
    if (targetPort <= 0 || targetPort > 65535) targetPort = DEFAULT_PORT;
    minutesPerCoin = prefs.getInt(NVS_KEY_MINS_PER_COIN, DEFAULT_MINUTES_PER_COIN);
    if (minutesPerCoin < 1) minutesPerCoin = 1;
#if PISO_HAS_PHONES
    loadPhoneProfilesFromPrefs();
#endif

    webPassword = prefs.getString(NVS_KEY_ADMIN_PW, webPassword);
    relayActiveLow = prefs.getBool(NVS_KEY_RELAY_ACTIVE_LOW, false);
    coinPulseGuard = prefs.getBool(NVS_KEY_PULSE_GUARD, true);
    coinPulseMinMs = pulseMinMsClamp(prefs.getInt(NVS_KEY_PULSE_MIN_MS, PULSE_MIN_MS_DEFAULT));
#if PISO_HAS_PHONES
    p1Ip = prefs.getString(NVS_KEY_P1, p1Ip);
    p2Ip = prefs.getString(NVS_KEY_P2, p2Ip);
    matchMinutes = prefs.getInt(NVS_KEY_MATCH, matchMinutes);
#endif

    // Lifetime vault revenue counters
    totalCoinsLifetime = prefs.getULong(NVS_KEY_TOTAL_COINS, 0);
    totalWifiCoinsLifetime = prefs.getULong(NVS_KEY_TOTAL_WIFI_COINS, 0);
    totalPhoneCoinsLifetime = prefs.getULong(NVS_KEY_TOTAL_PHONE_COINS, 0);
    totalCentavosLifetime = prefs.getULong(NVS_KEY_TOTAL_CENTAVOS, 0);

    // If upgrading from older firmware where totalCoinsLifetime was stored without the split:
    if (totalCoinsLifetime > 0 && totalWifiCoinsLifetime == 0 && totalPhoneCoinsLifetime == 0) {
#if !PISO_HAS_PHONES
        totalWifiCoinsLifetime = totalCoinsLifetime;
#elif !PISO_HAS_GATEWAY
        totalPhoneCoinsLifetime = totalCoinsLifetime;
#else
        totalWifiCoinsLifetime = totalCoinsLifetime;
#endif
    }

    lastSavedTotalCoins = totalCoinsLifetime;
    lastSavedTotalWifiCoins = totalWifiCoinsLifetime;
    lastSavedTotalPhoneCoins = totalPhoneCoinsLifetime;
    lastSavedTotalCentavos = totalCentavosLifetime;
    totalCoinsSession = 0;
    totalCentavosSession = 0;
    revenueDirty = false;

    prefs.end();

#if PISO_HAS_PHONES
    // Sanitize and purge any corrupted legacy entries
    String bootCleanIps = "";
    bootCleanIps.reserve(androidIps.length());
    forEachConfiguredDevice([&](const DeviceConfig& cfg) {
        if (bootCleanIps.length() > 0) bootCleanIps += ",";
        bootCleanIps += cfg.id + "|" + cfg.ip + "|" + cfg.name;
        return true;
    });
    androidIps = bootCleanIps;
#endif

    char earnings[24];
    money::formatPesos(totalCentavosLifetime, earnings, sizeof(earnings));
    Serial.printf("[💾 CONFIG] Loaded NVS Config: SSID='%s', Port=%d, RelayPin=%d, TotalCoins=%u, TotalEarnings=₱%s\n",
                  wifiSsid.c_str(), targetPort, relayPin, totalCoinsLifetime, earnings);
}

void flushRevenueNow() {
    if (!revenueDirty && totalCoinsLifetime == lastSavedTotalCoins &&
        totalWifiCoinsLifetime == lastSavedTotalWifiCoins && totalPhoneCoinsLifetime == lastSavedTotalPhoneCoins &&
        totalCentavosLifetime == lastSavedTotalCentavos) {
        return;
    }
    prefs.begin(NVS_NAMESPACE, false);
    prefs.putULong(NVS_KEY_TOTAL_COINS, totalCoinsLifetime);
    prefs.putULong(NVS_KEY_TOTAL_WIFI_COINS, totalWifiCoinsLifetime);
    prefs.putULong(NVS_KEY_TOTAL_PHONE_COINS, totalPhoneCoinsLifetime);
    prefs.putULong(NVS_KEY_TOTAL_CENTAVOS, totalCentavosLifetime);
    prefs.end();
    lastSavedTotalCoins = totalCoinsLifetime;
    lastSavedTotalWifiCoins = totalWifiCoinsLifetime;
    lastSavedTotalPhoneCoins = totalPhoneCoinsLifetime;
    lastSavedTotalCentavos = totalCentavosLifetime;
    revenueDirty = false;
    Serial.println("[💰 VAULT] Revenue counters flushed to NVS flash.");
}

void processRevenuePersistence() {
    if (revenueDirty && (millis() - lastCoinChangeTime >= REVENUE_SAVE_DELAY_MS ||
                         millis() - revenueDirtySinceMs >= REVENUE_SAVE_MAX_DEFER_MS)) {
        flushRevenueNow();
    }
}

void factoryResetDefaults(bool ownerWipe) {
    Serial.println("\n=======================================================");
    diagLog(ownerWipe ? "[⚠️ FACTORY RESET] Owner wipe: erasing everything including the revenue..."
                      : "[⚠️ FACTORY RESET] Restoring operator settings to defaults (revenue is kept)...");
    Serial.println("=======================================================");

    flushRevenueNow(); // the totals in memory first: the last coins' revenue would otherwise be lost with the reset
    prefs.begin(NVS_NAMESPACE, false);
    PrefsStore keepStore;
    ownerdata::Snapshot owner = ownerdata::capture(keepStore);
    prefs.clear();
    if (!ownerWipe) ownerdata::restore(keepStore, owner); // an operator can never reset the revenue
    prefs.end();
    if (ownerWipe) {
        vaultOnOwnerWipe();   // the owner's full wipe also starts a new collection log
        orphansOnOwnerWipe(); // ... and a new list of orphaned payments
        // (an operator reset keeps unacknowledged payments: that money was already collected)
        clearPaymentQueue(false); // the ledger was just wiped as well, so nothing is written to it
    }
    runConfigMigrations(); // a cleared box gets a fresh secret and new passwords (printed on the serial console)
    loadSecretMode();
#if PISO_HAS_PHONES
    loadLicenses(); // (an operator's reset keeps the keys and the floor, OwnerData.h; an owner wipe starts again with the 2 free slots)
#endif
    loadCredentials();

    wifiSsid = setupgate::DEFAULT_WIFI_SSID;
    wifiPass = setupgate::DEFAULT_WIFI_PASSWORD;
#if PISO_HAS_PHONES
    kioskWifiPass = "";
    kioskWifiSsid = "";
#endif
    universalCoinPin = DEFAULT_UNIVERSAL_COIN_PIN;
    ledPin = DEFAULT_LED_PIN;
    ledActiveLow = DEFAULT_LED_ACTIVE_LOW;
    relayPin = DEFAULT_RELAY_PIN;
#if PISO_HAS_PHONES
    androidIps = "";
#endif
    targetPort = DEFAULT_PORT;
    minutesPerCoin = DEFAULT_MINUTES_PER_COIN;
#if PISO_HAS_PHONES
    resetPhoneProfiles();
    p1Ip = "";
    p2Ip = "";
    matchMinutes = 15;
#endif
    const bool keepOwner = !ownerWipe;
#if PISO_HAS_PHONES
    for (int i = 0; i < MAX_SUPPORTED_SLOTS; i++) {
        phoneSlots[i].slotNum = i + 1;
        phoneSlots[i].deviceId = "";
        phoneSlots[i].ip = "";
        phoneSlots[i].name = "PisoPhone " + String(i + 1);
        phoneSlots[i].active = true;
    }
    saveSlots();
#endif

    totalCoinsLifetime = (keepOwner && owner.hasCoins) ? owner.coins : 0;
    totalWifiCoinsLifetime = (keepOwner && owner.hasWifiCoins) ? owner.wifiCoins : 0;
    totalPhoneCoinsLifetime = (keepOwner && owner.hasPhoneCoins) ? owner.phoneCoins : 0;
    totalCoinsSession = 0;
    totalCentavosLifetime = (keepOwner && owner.hasCentavos) ? owner.centavos : 0;
    totalCentavosSession = 0;
    lastSavedTotalCoins = totalCoinsLifetime;
    lastSavedTotalWifiCoins = totalWifiCoinsLifetime;
    lastSavedTotalPhoneCoins = totalPhoneCoinsLifetime;
    lastSavedTotalCentavos = totalCentavosLifetime;
    if (ownerWipe) vendorRevenueSplitPercent = DEFAULT_VENDOR_SPLIT_PERCENT;

    for (int i = 0; i < 10; i++) {
        setLedHardware(true);
        delay(60);
        setLedHardware(false);
        delay(60);
    }

    Serial.println("[✅ FACTORY RESET COMPLETE]");
    Serial.println(" -> Wi-Fi      : not set (the box opens its setup access point)");
    Serial.println(" -> Port       : 8080");
    Serial.println("=======================================================\n");
}
