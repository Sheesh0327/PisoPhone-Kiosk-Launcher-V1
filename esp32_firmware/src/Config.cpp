#include "Config.h"
#include "DeviceManager.h"
#include "HardwareManager.h"
#include "PaymentQueueManager.h"
#include "SuperAdminManager.h"

// ============================================================================
// HARDWARE CONSTANTS & PIN DEFAULTS DEFINITION
// ============================================================================
const char* DEFAULT_SSID        = "AdminSetup";
const char* DEFAULT_PASS        = "Admin@123";
const char* DEFAULT_ADMIN_PW    = "admin";
const char* MASTER_CRYPTO_SECRET = "PISOPHONE_HMAC_MASTER_KEY";

const int   DEFAULT_UNIVERSAL_COIN_PIN = 3;
const int   DEFAULT_LED_PIN            = 8;
const bool  DEFAULT_LED_ACTIVE_LOW     = false;
const int   DEFAULT_RELAY_PIN          = 4;
const int   DEFAULT_PORT               = 8080;
const int   HARDWARE_RESET_PIN         = 2;
const int   DEFAULT_MINUTES_PER_COIN   = 6;

// ============================================================================
// GLOBAL VARIABLES DEFINITION
// ============================================================================
Preferences prefs;
static portMUX_TYPE s_nvsMux = portMUX_INITIALIZER_UNLOCKED;

void lockNvs() {
    portENTER_CRITICAL(&s_nvsMux);
}

void unlockNvs() {
    portEXIT_CRITICAL(&s_nvsMux);
}

int universalCoinPin = DEFAULT_UNIVERSAL_COIN_PIN;
int ledPin           = DEFAULT_LED_PIN;
bool ledActiveLow    = DEFAULT_LED_ACTIVE_LOW;
int relayPin         = DEFAULT_RELAY_PIN;
bool relayActiveLow  = false;

String wifiSsid      = DEFAULT_SSID;
String wifiPass      = DEFAULT_PASS;
String androidIps    = "";
String webPassword   = DEFAULT_ADMIN_PW;
String sharedSecret  = MASTER_CRYPTO_SECRET;
String macAddressStr = "";
bool is_licensed     = false;
int maxLicensedSlots = DEFAULT_MAX_SLOTS;

int targetPort        = DEFAULT_PORT;
int minutesPerCoin    = DEFAULT_MINUTES_PER_COIN;

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
uint32_t totalCoinsSession  = 0;
float totalEarningsLifetime = 0.0f;
float totalEarningsSession  = 0.0f;
uint32_t lastSavedTotalCoins = 0;
float lastSavedTotalEarnings = 0.0f;
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

void updateMasterTime(uint64_t ts) {
    if (ts > lastMasterTimestamp) {
        lastMasterTimestamp = ts;
        lastMasterMillis = millis();
    }
}

bool areDefaultCredentialsActive() {
    return (webPassword == DEFAULT_ADMIN_PW || wifiPass == DEFAULT_PASS);
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
        p1.trim(); p2.trim(); p3.trim();

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
        p1.trim(); p2.trim();

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

const char* const NVS_NAMESPACE       = "kiosk_cfg";
const char* const NVS_KEY_MAX_SLOTS   = "max_slots";
const char* const NVS_KEY_LICENSED    = "licensed";
const char* const NVS_KEY_SLOTS_DATA  = "slots_data";
const char* const NVS_KEY_IPS         = "ips";

const char* const NVS_KEY_WIFI_SSID        = "wifi_ssid";
const char* const NVS_KEY_WIFI_PASS        = "wifi_pass";
const char* const NVS_KEY_U_COIN_PIN       = "u_coin_pin";
const char* const NVS_KEY_LED_PIN          = "led_pin";
const char* const NVS_KEY_LED_ACTIVE_LOW   = "led_act_low";
const char* const NVS_KEY_RELAY_PIN        = "relay_pin";
const char* const NVS_KEY_RELAY_ACTIVE_LOW = "relay_act_low";
const char* const NVS_KEY_PORT             = "target_port";
const char* const NVS_KEY_MINS_PER_COIN    = "mins_per_coin";
const char* const NVS_KEY_ADMIN_PW         = "admin_pw";
const char* const NVS_KEY_SHARED_SECRET    = "shared_secret";
const char* const NVS_KEY_P1               = "p1_ip";
const char* const NVS_KEY_P2               = "p2_ip";
const char* const NVS_KEY_MATCH            = "match_minutes";
const char* const NVS_KEY_TOTAL_COINS      = "total_coins";
const char* const NVS_KEY_TOTAL_EARNINGS   = "total_earnings";

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
    lockNvs();
    prefs.begin(NVS_NAMESPACE, false);
    prefs.putInt(NVS_KEY_MAX_SLOTS, maxLicensedSlots);
    prefs.putBool(NVS_KEY_LICENSED, is_licensed);
    String raw = "";
    raw.reserve(maxLicensedSlots * 64); // Pre-allocate approx 64 bytes per slot
    for (int i = 0; i < maxLicensedSlots; i++) {
        if (i > 0) raw += ";";
        raw += String(licenseSlots[i].slotNum) + "|" +
               licenseSlots[i].deviceId + "|" +
               licenseSlots[i].ip + "|" +
               licenseSlots[i].name + "|" +
               (licenseSlots[i].active ? "1" : "0");
    }
    prefs.putString(NVS_KEY_SLOTS_DATA, raw);
    syncAndroidIpsFromSlots();
    prefs.putString(NVS_KEY_IPS, androidIps);
    prefs.end();
    unlockNvs();
}

void loadSlotLicenses() {
    lockNvs();
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
    } else {
        // Fallback: If slots_data is empty, check legacy "ips" key so existing devices aren't lost
        String legacyIps = prefs.getString(NVS_KEY_IPS, "");
        if (legacyIps.length() > 0) {
            int startIdx = 0;
            int sIdx = 0;
            while (startIdx < legacyIps.length() && sIdx < maxLicensedSlots) {
                int comma = legacyIps.indexOf(',', startIdx);
                if (comma == -1) comma = legacyIps.length();
                String entry = legacyIps.substring(startIdx, comma);
                entry.trim();
                if (entry.length() > 0) {
                    DeviceConfig cfg;
                    if (parseDeviceEntry(entry, cfg)) {
                        licenseSlots[sIdx].deviceId = cfg.id;
                        licenseSlots[sIdx].ip = cfg.ip;
                        licenseSlots[sIdx].name = cfg.name;
                        licenseSlots[sIdx].active = true;
                        sIdx++;
                    }
                }
                startIdx = comma + 1;
            }
        }
    }
    prefs.end();
    unlockNvs();
    syncAndroidIpsFromSlots();
}

void loadAllConfig() {
    // 1. Load slot licenses and terminal allocations safely
    loadSlotLicenses();
    loadSuperAdminConfig();

    // 2. Open NVS for all kiosk configuration & lifetime vault revenue counters
    lockNvs();
    prefs.begin(NVS_NAMESPACE, false);
    is_licensed       = prefs.getBool(NVS_KEY_LICENSED, (maxLicensedSlots > 1));
    wifiSsid          = prefs.getString(NVS_KEY_WIFI_SSID, wifiSsid);
    wifiPass          = prefs.getString(NVS_KEY_WIFI_PASS, wifiPass);
    universalCoinPin  = prefs.getInt(NVS_KEY_U_COIN_PIN, universalCoinPin);
    ledPin            = prefs.getInt(NVS_KEY_LED_PIN, ledPin);
    ledActiveLow      = prefs.getBool(NVS_KEY_LED_ACTIVE_LOW, DEFAULT_LED_ACTIVE_LOW);
    relayPin          = prefs.getInt(NVS_KEY_RELAY_PIN, relayPin);
    
    targetPort        = prefs.getInt(NVS_KEY_PORT, targetPort);
    if (targetPort <= 0) targetPort = 8080;
    minutesPerCoin    = prefs.getInt(NVS_KEY_MINS_PER_COIN, DEFAULT_MINUTES_PER_COIN);
    if (minutesPerCoin < 1) minutesPerCoin = 1;
    
    webPassword       = prefs.getString(NVS_KEY_ADMIN_PW, webPassword);
    relayActiveLow    = prefs.getBool(NVS_KEY_RELAY_ACTIVE_LOW, false);
    sharedSecret      = prefs.getString(NVS_KEY_SHARED_SECRET, sharedSecret);
    p1Ip              = prefs.getString(NVS_KEY_P1, p1Ip);
    p2Ip              = prefs.getString(NVS_KEY_P2, p2Ip);
    matchMinutes      = prefs.getInt(NVS_KEY_MATCH, matchMinutes);

    // Lifetime vault revenue counters
    totalCoinsLifetime = prefs.getULong(NVS_KEY_TOTAL_COINS, 0);
    totalEarningsLifetime = prefs.getFloat(NVS_KEY_TOTAL_EARNINGS, 0.0f);

    lastSavedTotalCoins = totalCoinsLifetime;
    lastSavedTotalEarnings = totalEarningsLifetime;
    totalCoinsSession = 0;
    totalEarningsSession = 0.0f;
    revenueDirty = false;

    prefs.end();
    unlockNvs();

    Serial.printf("[💾 CONFIG] Loaded NVS Config: SSID='%s', Port=%d, AdminPW='%s', RelayPin=%d, TotalCoins=%u, TotalEarnings=₱%.2f\n",
        wifiSsid.c_str(), targetPort, webPassword.c_str(), relayPin, totalCoinsLifetime, totalEarningsLifetime);
}

void processRevenuePersistence() {
    if (revenueDirty && (millis() - lastCoinChangeTime >= REVENUE_SAVE_DELAY_MS)) {
        lockNvs();
        prefs.begin(NVS_NAMESPACE, false);
        prefs.putULong(NVS_KEY_TOTAL_COINS, totalCoinsLifetime);
        prefs.putFloat(NVS_KEY_TOTAL_EARNINGS, totalEarningsLifetime);
        prefs.end();
        unlockNvs();
        lastSavedTotalCoins = totalCoinsLifetime;
        lastSavedTotalEarnings = totalEarningsLifetime;
        revenueDirty = false;
        Serial.println("[💰 VAULT] Revenue counters flushed to NVS flash (debounced idle save).");
    }
}

void factoryResetDefaults() {
    Serial.println("\n=======================================================");
    Serial.println("[⚠️ FACTORY RESET] Restoring config settings to defaults...");
    Serial.println("=======================================================");

    lockNvs();
    Preferences storage;
    if (storage.begin(NVS_NAMESPACE, false)) {
        storage.clear(); // Clears ONLY "pisophone" config namespace
        storage.end();
    }
    unlockNvs();

    wifiSsid = DEFAULT_SSID;
    wifiPass = DEFAULT_PASS;
    universalCoinPin = DEFAULT_UNIVERSAL_COIN_PIN;
    ledPin = DEFAULT_LED_PIN;
    ledActiveLow = DEFAULT_LED_ACTIVE_LOW;
    relayPin = DEFAULT_RELAY_PIN;
    targetPort = DEFAULT_PORT;
    minutesPerCoin = DEFAULT_MINUTES_PER_COIN;
    webPassword = DEFAULT_ADMIN_PW;
    p1Ip = "";
    p2Ip = "";
    matchMinutes = 15;

    if (hasPendingPayments()) {
        Serial.println("[⚠️ FACTORY RESET] Unresolved payment records exist! Preserving license slots, registered devices, and crypto key for delivery.");
    } else {
        androidIps = "";
        sharedSecret = MASTER_CRYPTO_SECRET;
        maxLicensedSlots = DEFAULT_MAX_SLOTS;
        for (int i = 0; i < MAX_SUPPORTED_SLOTS; i++) {
            licenseSlots[i].slotNum = i + 1;
            licenseSlots[i].deviceId = "";
            licenseSlots[i].ip = "";
            licenseSlots[i].name = "PisoPhone " + String(i + 1);
            licenseSlots[i].active = (i < DEFAULT_MAX_SLOTS);
        }
        saveSlotLicenses();
    }

    totalCoinsLifetime = 0;
    totalCoinsSession = 0;
    totalEarningsLifetime = 0.0f;
    totalEarningsSession = 0.0f;
    lastSavedTotalCoins = 0;
    lastSavedTotalEarnings = 0.0f;

    for (int i = 0; i < 10; i++) {
        setLedHardware(true);
        delay(60);
        setLedHardware(false);
        delay(60);
    }

    Serial.println("[✅ FACTORY RESET COMPLETE]");
    Serial.println(" -> Wi-Fi SSID : AdminSetup");
    Serial.println(" -> Wi-Fi Pass : Admin@123");
    Serial.println(" -> Admin Pass : admin");
    Serial.println(" -> Port       : 8080");
    Serial.println("=======================================================\n");
}
