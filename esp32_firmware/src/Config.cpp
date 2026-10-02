// Persistent configuration (NVS): defaults, loading and saving of pins, Wi-Fi, passwords,
// slots and revenue counters, plus the locked accessor for the shared secret.

#include "Config.h"
#include "OwnerData.h"
#include "DeviceManager.h"
#include "HardwareManager.h"
#include "SuperAdminManager.h"
#include "PaymentQueueManager.h"
#include "Diagnostics.h"
#include "Money.h"
#include "CredGen.h"
#include "SecretMode.h"
#include "TxId.h"
#include "ConfigMigration.h"
#include <esp_random.h>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>

// ============================================================================
// HARDWARE CONSTANTS & PIN DEFAULTS DEFINITION
// ============================================================================
// DEPRECATED shared key of firmware before per-box secrets. Only used while a box is in legacy mode and
// to check old-style license keys. Remove with the legacy path once every phone is re-provisioned.
static const char* LEGACY_CRYPTO_SECRET = "PISOPHONE_HMAC_MASTER_KEY";

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
const int UDP_DISCOVERY_PORT = 8888;
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

String wifiSsid = ""; // empty until the operator sets Wi-Fi: the box then opens its setup access point
String wifiPass = "";
String androidIps = "";
String webPassword = "";
String setupApPass = "";
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

String getLegacyLicenseSecret() {
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
int maxLicensedSlots = DEFAULT_MAX_SLOTS;

int targetPort = DEFAULT_PORT;
int minutesPerCoin = DEFAULT_MINUTES_PER_COIN;

const unsigned long ARM_TTL = 20000;
const unsigned long MAX_SESSION_DURATION = 120000;

String p1Ip = "";
String p2Ip = "";
int matchMinutes = 15;
bool matchActive = false;
String matchStatusMsg = "";
String quickTimeStatusMsg = "";

// Credit Vault
// Revenue & Audit
uint32_t totalCoinsLifetime = 0;
uint32_t totalCoinsSession = 0;
uint32_t totalCentavosLifetime = 0;
uint32_t totalCentavosSession = 0;
uint32_t lastSavedTotalCoins = 0;
uint32_t lastSavedTotalCentavos = 0;
bool revenueDirty = false;
unsigned long lastCoinChangeTime = 0;
const unsigned long REVENUE_SAVE_DELAY_MS = 5000;

// Device & Slot Arrays
LicenseSlot licenseSlots[MAX_SUPPORTED_SLOTS];
DeviceTelemetry trackedDevices[MAX_TRACKED_DEVICES];
int trackedDeviceCount = 0;

// Monotonic Master Clock derived from synchronized Android telemetry timestamps
static uint64_t lastMasterTimestamp = 0;
static unsigned long lastMasterMillis = 0;

uint64_t getCurrentMasterTimeMs() {
    if (lastMasterTimestamp > 0) {
        return lastMasterTimestamp + (uint64_t)(millis() - lastMasterMillis);
    }
    return 0;
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

const char* const NVS_NAMESPACE = "kiosk_cfg";
const char* const NVS_KEY_MAX_SLOTS = "max_slots";
const char* const NVS_KEY_SLOTS_DATA = "slots_data";
const char* const NVS_KEY_IPS = "ips";

const char* const NVS_KEY_WIFI_SSID = "wifi_ssid";
const char* const NVS_KEY_WIFI_PASS = "wifi_pass";
const char* const NVS_KEY_U_COIN_PIN = "u_coin_pin";
const char* const NVS_KEY_LED_PIN = "led_pin";
const char* const NVS_KEY_LED_ACTIVE_LOW = "led_act_low";
const char* const NVS_KEY_RELAY_PIN = "relay_pin";
const char* const NVS_KEY_RELAY_ACTIVE_LOW = "relay_act_low";
const char* const NVS_KEY_PORT = "target_port";
const char* const NVS_KEY_MINS_PER_COIN = "mins_per_coin";
const char* const NVS_KEY_ADMIN_PW = cfgmig::K_ADMIN_PW;
const char* const NVS_KEY_ADMIN_PW_CHANGED = cfgmig::K_ADMIN_PW_CHANGED;
const char* const NVS_KEY_SETUP_AP_PASS = cfgmig::K_SETUP_AP_PASS;
const char* const NVS_KEY_SHARED_SECRET = cfgmig::K_SHARED_SECRET;
const char* const NVS_KEY_LEGACY_KEY = cfgmig::K_LEGACY_KEY;
const char* const NVS_KEY_P1 = "p1_ip";
const char* const NVS_KEY_P2 = "p2_ip";
const char* const NVS_KEY_MATCH = "match_minutes";
const char* const NVS_KEY_TOTAL_COINS = "total_coins";
const char* const NVS_KEY_TOTAL_CENTAVOS = cfgmig::K_TOTAL_CENTAVOS;

void syncAndroidIpsFromSlots() {
    String newIps = "";
    newIps.reserve(maxLicensedSlots * 48); // Pre-allocate to prevent heap fragmentation
    for (int i = 0; i < maxLicensedSlots; i++) {
        if (licenseSlots[i].deviceId.length() > 0 && licenseSlots[i].ip.length() > 0) {
            if (newIps.length() > 0) newIps += ",";
            newIps += licenseSlots[i].deviceId + "|" + licenseSlots[i].ip + "|" + licenseSlots[i].name;
        }
    }
    androidIps = newIps;
}

void saveSlotLicenses() {
    prefs.begin(NVS_NAMESPACE, false);
    prefs.putInt(NVS_KEY_MAX_SLOTS, maxLicensedSlots);
    String raw = "";
    raw.reserve(maxLicensedSlots * 64); // Pre-allocate approx 64 bytes per slot
    for (int i = 0; i < maxLicensedSlots; i++) {
        if (i > 0) raw += ";";
        raw += String(licenseSlots[i].slotNum) + "|" + licenseSlots[i].deviceId + "|" + licenseSlots[i].ip + "|" +
               licenseSlots[i].name + "|" + (licenseSlots[i].active ? "1" : "0");
    }
    prefs.putString(NVS_KEY_SLOTS_DATA, raw);
    syncAndroidIpsFromSlots();
    prefs.putString(NVS_KEY_IPS, androidIps);
    prefs.end();
}

void loadSlotLicenses() {
    prefs.begin(NVS_NAMESPACE, false);
    maxLicensedSlots = prefs.getInt(NVS_KEY_MAX_SLOTS, DEFAULT_MAX_SLOTS);
    if (maxLicensedSlots < 1) maxLicensedSlots = DEFAULT_MAX_SLOTS;
    if (maxLicensedSlots > MAX_SUPPORTED_SLOTS) maxLicensedSlots = MAX_SUPPORTED_SLOTS;

    for (int i = 0; i < MAX_SUPPORTED_SLOTS; i++) {
        licenseSlots[i].slotNum = i + 1;
        licenseSlots[i].deviceId = "";
        licenseSlots[i].ip = "";
        licenseSlots[i].name = "PisoPhone " + String(i + 1);
        licenseSlots[i].active = (i < maxLicensedSlots);
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
                        licenseSlots[idx].slotNum = sNum;
                        licenseSlots[idx].deviceId = item.substring(p1 + 1, p2);
                        licenseSlots[idx].ip = item.substring(p2 + 1, p3);
                        licenseSlots[idx].name = item.substring(p3 + 1, (p4 != -1) ? p4 : item.length());

                        if (p4 != -1) {
                            licenseSlots[idx].active = (idx < maxLicensedSlots) && (item.substring(p4 + 1) == "1");
                        } else {
                            licenseSlots[idx].active = (idx < maxLicensedSlots);
                        }
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

static void fillRandomBytes(uint8_t* buf, size_t len) {
    esp_fill_random(buf, len);
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

void switchToOwnKey() {
    prefs.begin(NVS_NAMESPACE, false);
    prefs.putBool(NVS_KEY_LEGACY_KEY, false);
    prefs.end();
    xSemaphoreTake(sharedSecretMutex, portMAX_DELAY);
    legacyKeyMode = false;
    xSemaphoreGive(sharedSecretMutex);
    diagLog("[🔐 KEY] Switched to this box's own key. Re-provision each phone with the new secret.");
}

// Reads the admin and setup-AP passwords. Every box has its own, made by the migrations the first time it starts
// (and after a factory reset); boxes already in the field keep the password they have.
void loadCredentials() {
    prefs.begin(NVS_NAMESPACE, false);
    webPassword = prefs.getString(NVS_KEY_ADMIN_PW, "");
    adminPwChanged = prefs.getBool(NVS_KEY_ADMIN_PW_CHANGED, false);
    setupApPass = prefs.getString(NVS_KEY_SETUP_AP_PASS, "");
    prefs.end();

    if (!adminPwChanged) {
        // Shown only until the operator changes the admin password. Needed to reach a box that has never been set up.
        Serial.println("\n================ FIRST-TIME SETUP CREDENTIALS ================");
        Serial.printf(" Setup Wi-Fi   : PisoPhone-Setup-xxxx   password: %s\n", setupApPass.c_str());
        Serial.printf(" Admin login   : admin / %s\n", webPassword.c_str());
        Serial.println(" Change the admin password in Settings; coins stay blocked until you do.");
        Serial.println("==============================================================\n");
    }
}

void loadAllConfig() {
    // 1. Load slot licenses and terminal allocations safely
    runConfigMigrations(); // first: brings stored settings to this firmware's format
    loadSecretMode();
    initTxIdBootCounter();
    loadSlotLicenses();
    loadSuperAdminConfig();
    loadCredentials();

    // 2. Open NVS for all kiosk configuration & lifetime vault revenue counters
    prefs.begin(NVS_NAMESPACE, false);
    wifiSsid = prefs.getString(NVS_KEY_WIFI_SSID, wifiSsid);
    wifiPass = prefs.getString(NVS_KEY_WIFI_PASS, wifiPass);
    universalCoinPin = prefs.getInt(NVS_KEY_U_COIN_PIN, universalCoinPin);
    ledPin = prefs.getInt(NVS_KEY_LED_PIN, ledPin);
    ledActiveLow = prefs.getBool(NVS_KEY_LED_ACTIVE_LOW, DEFAULT_LED_ACTIVE_LOW);
    relayPin = prefs.getInt(NVS_KEY_RELAY_PIN, relayPin);

    targetPort = prefs.getInt(NVS_KEY_PORT, targetPort);
    if (targetPort <= 0) targetPort = 8080;
    minutesPerCoin = prefs.getInt(NVS_KEY_MINS_PER_COIN, DEFAULT_MINUTES_PER_COIN);
    if (minutesPerCoin < 1) minutesPerCoin = 1;

    webPassword = prefs.getString(NVS_KEY_ADMIN_PW, webPassword);
    relayActiveLow = prefs.getBool(NVS_KEY_RELAY_ACTIVE_LOW, false);
    p1Ip = prefs.getString(NVS_KEY_P1, p1Ip);
    p2Ip = prefs.getString(NVS_KEY_P2, p2Ip);
    matchMinutes = prefs.getInt(NVS_KEY_MATCH, matchMinutes);

    // Lifetime vault revenue counters
    totalCoinsLifetime = prefs.getULong(NVS_KEY_TOTAL_COINS, 0);
    totalCentavosLifetime = prefs.getULong(NVS_KEY_TOTAL_CENTAVOS, 0);

    lastSavedTotalCoins = totalCoinsLifetime;
    lastSavedTotalCentavos = totalCentavosLifetime;
    totalCoinsSession = 0;
    totalCentavosSession = 0;
    revenueDirty = false;

    prefs.end();

    // Sanitize and purge any corrupted legacy entries
    String bootCleanIps = "";
    bootCleanIps.reserve(androidIps.length());
    forEachConfiguredDevice([&](const DeviceConfig& cfg) {
        if (bootCleanIps.length() > 0) bootCleanIps += ",";
        bootCleanIps += cfg.id + "|" + cfg.ip + "|" + cfg.name;
        return true;
    });
    androidIps = bootCleanIps;

    char earnings[24];
    money::formatPesos(totalCentavosLifetime, earnings, sizeof(earnings));
    Serial.printf("[💾 CONFIG] Loaded NVS Config: SSID='%s', Port=%d, RelayPin=%d, TotalCoins=%u, TotalEarnings=₱%s\n",
                  wifiSsid.c_str(), targetPort, relayPin, totalCoinsLifetime, earnings);
}

void flushRevenueNow() {
    if (!revenueDirty && totalCoinsLifetime == lastSavedTotalCoins && totalCentavosLifetime == lastSavedTotalCentavos) {
        return;
    }
    prefs.begin(NVS_NAMESPACE, false);
    prefs.putULong(NVS_KEY_TOTAL_COINS, totalCoinsLifetime);
    prefs.putULong(NVS_KEY_TOTAL_CENTAVOS, totalCentavosLifetime);
    prefs.end();
    lastSavedTotalCoins = totalCoinsLifetime;
    lastSavedTotalCentavos = totalCentavosLifetime;
    revenueDirty = false;
    Serial.println("[💰 VAULT] Revenue counters flushed to NVS flash.");
}

void processRevenuePersistence() {
    if (revenueDirty && (millis() - lastCoinChangeTime >= REVENUE_SAVE_DELAY_MS)) {
        flushRevenueNow();
    }
}

void factoryResetDefaults(bool ownerWipe) {
    Serial.println("\n=======================================================");
    diagLog(ownerWipe ? "[⚠️ FACTORY RESET] Owner wipe: erasing everything including the license and revenue..."
                      : "[⚠️ FACTORY RESET] Restoring operator settings to defaults (license and revenue are kept)...");
    Serial.println("=======================================================");

    prefs.begin(NVS_NAMESPACE, false);
    PrefsStore keepStore;
    ownerdata::Snapshot owner = ownerdata::capture(keepStore);
    prefs.clear();
    if (!ownerWipe) ownerdata::restore(keepStore, owner); // an operator can never reset the license or the revenue
    prefs.end();
    if (ownerWipe) clearPaymentQueue(); // an operator reset keeps unacknowledged payments: that money was already collected
    runConfigMigrations(); // a cleared box gets a fresh secret and new passwords (printed on the serial console)
    loadSecretMode();
    loadCredentials();

    wifiSsid = "";
    wifiPass = "";
    universalCoinPin = DEFAULT_UNIVERSAL_COIN_PIN;
    ledPin = DEFAULT_LED_PIN;
    ledActiveLow = DEFAULT_LED_ACTIVE_LOW;
    relayPin = DEFAULT_RELAY_PIN;
    androidIps = "";
    targetPort = DEFAULT_PORT;
    minutesPerCoin = DEFAULT_MINUTES_PER_COIN;
    p1Ip = "";
    p2Ip = "";
    matchMinutes = 15;
    const bool keepOwner = !ownerWipe;
    maxLicensedSlots = (keepOwner && owner.hasMaxSlots) ? (int)owner.maxSlots : DEFAULT_MAX_SLOTS;
    for (int i = 0; i < MAX_SUPPORTED_SLOTS; i++) {
        licenseSlots[i].slotNum = i + 1;
        licenseSlots[i].deviceId = "";
        licenseSlots[i].ip = "";
        licenseSlots[i].name = "PisoPhone " + String(i + 1);
        licenseSlots[i].active = (i < maxLicensedSlots);
    }
    saveSlotLicenses();

    totalCoinsLifetime = (keepOwner && owner.hasCoins) ? owner.coins : 0;
    totalCoinsSession = 0;
    totalCentavosLifetime = (keepOwner && owner.hasCentavos) ? owner.centavos : 0;
    totalCentavosSession = 0;
    lastSavedTotalCoins = totalCoinsLifetime;
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
