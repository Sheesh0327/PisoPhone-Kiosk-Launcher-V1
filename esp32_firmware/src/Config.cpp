#include "Config.h"
#include "HardwareManager.h"
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
const int   UDP_DISCOVERY_PORT         = 8888;
const int   DEFAULT_MINUTES_PER_COIN   = 6;

// ============================================================================
// GLOBAL VARIABLES DEFINITION
// ============================================================================
Preferences prefs;

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

const unsigned long ARM_TTL = 15000;
const unsigned long MAX_SESSION_DURATION = 120000;

String p1Ip = "";
String p2Ip = "";
int matchMinutes = 15;
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

bool parseDeviceEntry(String entry, DeviceConfig& out) {
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

void syncAndroidIpsFromSlots() {
    String newIps = "";
    for (int i = 0; i < maxLicensedSlots; i++) {
        if (licenseSlots[i].deviceId.length() > 0 && licenseSlots[i].ip.length() > 0) {
            if (newIps.length() > 0) newIps += ",";
            newIps += licenseSlots[i].deviceId + "|" + licenseSlots[i].ip + "|" + licenseSlots[i].name;
        }
    }
    androidIps = newIps;
}

void saveSlotLicenses() {
    prefs.begin("kiosk_cfg", false);
    prefs.putInt("max_slots", maxLicensedSlots);
    prefs.putBool("licensed", is_licensed);
    String raw = "";
    for (int i = 0; i < maxLicensedSlots; i++) {
        if (i > 0) raw += ";";
        raw += String(licenseSlots[i].slotNum) + "|" +
               licenseSlots[i].deviceId + "|" +
               licenseSlots[i].ip + "|" +
               licenseSlots[i].name + "|" +
               (licenseSlots[i].active ? "1" : "0");
    }
    prefs.putString("slots_data", raw);
    syncAndroidIpsFromSlots();
    prefs.putString("ips", androidIps);
    prefs.end();
}

void loadSlotLicenses() {
    prefs.begin("kiosk_cfg", false);
    maxLicensedSlots = prefs.getInt("max_slots", DEFAULT_MAX_SLOTS);
    if (maxLicensedSlots < 1) maxLicensedSlots = DEFAULT_MAX_SLOTS;
    if (maxLicensedSlots > MAX_SUPPORTED_SLOTS) maxLicensedSlots = MAX_SUPPORTED_SLOTS;

    for (int i = 0; i < MAX_SUPPORTED_SLOTS; i++) {
        licenseSlots[i].slotNum = i + 1;
        licenseSlots[i].deviceId = "";
        licenseSlots[i].ip = "";
        licenseSlots[i].name = "PisoPhone " + String(i + 1);
        licenseSlots[i].active = (i < maxLicensedSlots);
    }

    String raw = prefs.getString("slots_data", "");
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
        String legacyIps = prefs.getString("ips", "");
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
    syncAndroidIpsFromSlots();
}

void loadAllConfig() {
    // 1. Load slot licenses and terminal allocations safely
    loadSlotLicenses();
    loadSuperAdminConfig();

    // 2. Open NVS for all kiosk configuration & lifetime vault revenue counters
    prefs.begin("kiosk_cfg", false);
    is_licensed       = prefs.getBool("licensed", (maxLicensedSlots > 1));
    wifiSsid          = prefs.getString("wifi_ssid", wifiSsid);
    wifiPass          = prefs.getString("wifi_pass", wifiPass);
    universalCoinPin  = prefs.getInt("u_coin_pin", universalCoinPin);
    ledPin            = prefs.getInt("led_pin", ledPin);
    ledActiveLow      = prefs.getBool("led_active_low", DEFAULT_LED_ACTIVE_LOW);
    relayPin          = prefs.getInt("relay_pin", relayPin);
    
    targetPort        = prefs.getInt("port", targetPort);
    if (targetPort <= 0) targetPort = 8080;
    minutesPerCoin    = prefs.getInt("mins_per_coin", DEFAULT_MINUTES_PER_COIN);
    if (minutesPerCoin < 1) minutesPerCoin = 1;
    
    webPassword       = prefs.getString("admin_pw", webPassword);
    relayActiveLow    = prefs.getBool("relay_active_low", false);
    sharedSecret      = prefs.getString("shared_secret", sharedSecret);
    p1Ip              = prefs.getString("p1", p1Ip);
    p2Ip              = prefs.getString("p2", p2Ip);
    matchMinutes      = prefs.getInt("match", matchMinutes);

    // Lifetime vault revenue counters
    totalCoinsLifetime = prefs.getULong("total_coins", 0);
    totalEarningsLifetime = prefs.getFloat("total_earnings", 0.0f);

    lastSavedTotalCoins = totalCoinsLifetime;
    lastSavedTotalEarnings = totalEarningsLifetime;
    totalCoinsSession = 0;
    totalEarningsSession = 0.0f;
    revenueDirty = false;

    prefs.end();

    // Sanitize and purge any corrupted legacy entries
    String bootCleanIps = "";
    int bootIdx = 0;
    while (bootIdx < androidIps.length()) {
        int comma = androidIps.indexOf(',', bootIdx);
        if (comma == -1) comma = androidIps.length();
        String entry = androidIps.substring(bootIdx, comma);
        entry.trim();
        if (entry.length() > 0) {
            DeviceConfig cfg;
            if (parseDeviceEntry(entry, cfg)) {
                if (bootCleanIps.length() > 0) bootCleanIps += ",";
                bootCleanIps += cfg.id + "|" + cfg.ip + "|" + cfg.name;
            }
        }
        bootIdx = comma + 1;
    }
    androidIps = bootCleanIps;

    Serial.printf("[💾 CONFIG] Loaded NVS Config: SSID='%s', Port=%d, AdminPW='%s', RelayPin=%d, TotalCoins=%u, TotalEarnings=₱%.2f\n",
        wifiSsid.c_str(), targetPort, webPassword.c_str(), relayPin, totalCoinsLifetime, totalEarningsLifetime);
}

void processRevenuePersistence() {
    if (revenueDirty && (millis() - lastCoinChangeTime >= REVENUE_SAVE_DELAY_MS)) {
        prefs.begin("kiosk_cfg", false);
        prefs.putULong("total_coins", totalCoinsLifetime);
        prefs.putFloat("total_earnings", totalEarningsLifetime);
        prefs.end();
        lastSavedTotalCoins = totalCoinsLifetime;
        lastSavedTotalEarnings = totalEarningsLifetime;
        revenueDirty = false;
        Serial.println("[💰 VAULT] Revenue counters flushed to NVS flash (debounced idle save).");
    }
}

void factoryResetDefaults() {
    Serial.println("\n=======================================================");
    Serial.println("[⚠️ FACTORY RESET] Restoring all settings to defaults...");
    Serial.println("=======================================================");

    prefs.begin("kiosk_cfg", false);
    prefs.clear();
    prefs.end();

    wifiSsid = DEFAULT_SSID;
    wifiPass = DEFAULT_PASS;
    universalCoinPin = DEFAULT_UNIVERSAL_COIN_PIN;
    ledPin = DEFAULT_LED_PIN;
    ledActiveLow = DEFAULT_LED_ACTIVE_LOW;
    relayPin = DEFAULT_RELAY_PIN;
    androidIps = "";
    targetPort = DEFAULT_PORT;
    minutesPerCoin = DEFAULT_MINUTES_PER_COIN;
    webPassword = DEFAULT_ADMIN_PW;
    sharedSecret = MASTER_CRYPTO_SECRET;
    p1Ip = "";
    p2Ip = "";
    matchMinutes = 15;
    maxLicensedSlots = DEFAULT_MAX_SLOTS;
    for (int i = 0; i < MAX_SUPPORTED_SLOTS; i++) {
        licenseSlots[i].slotNum = i + 1;
        licenseSlots[i].deviceId = "";
        licenseSlots[i].ip = "";
        licenseSlots[i].name = "PisoPhone " + String(i + 1);
        licenseSlots[i].active = (i < DEFAULT_MAX_SLOTS);
    }
    saveSlotLicenses();

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
