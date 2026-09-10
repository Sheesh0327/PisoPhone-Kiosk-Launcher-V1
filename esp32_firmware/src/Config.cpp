#include "Config.h"
#include "HardwareManager.h"

// ============================================================================
// HARDWARE CONSTANTS & PIN DEFAULTS DEFINITION
// ============================================================================
const char* DEFAULT_SSID        = "AdminSetup";
const char* DEFAULT_PASS        = "Admin@123";
const char* DEFAULT_ADMIN_PW    = "admin";
const char* MASTER_CRYPTO_SECRET = "";

const int   DEFAULT_COIN_PIN           = 4;
const int   DEFAULT_UNIVERSAL_COIN_PIN = 3;
const int   DEFAULT_LED_PIN            = 8;
const bool  DEFAULT_LED_ACTIVE_LOW     = true;
const int   DEFAULT_RELAY_PIN          = 5;
const int   DEFAULT_PORT               = 8080;
const float DEFAULT_PRICE              = 5.0f;
const int   DEFAULT_MINUTES            = 30;
const int   DEFAULT_DEBOUNCE           = 25;
const int   HARDWARE_RESET_PIN         = 2;
const int   UDP_DISCOVERY_PORT         = 8888;

// ============================================================================
// GLOBAL VARIABLES DEFINITION
// ============================================================================
Preferences prefs;

int coinPin          = DEFAULT_COIN_PIN;
int universalCoinPin = DEFAULT_UNIVERSAL_COIN_PIN;
int ledPin           = DEFAULT_LED_PIN;
bool ledActiveLow    = DEFAULT_LED_ACTIVE_LOW;
int relayPin         = DEFAULT_RELAY_PIN;
bool relayActiveLow  = false;
int relayMode        = 1;

String wifiSsid      = DEFAULT_SSID;
String wifiPass      = DEFAULT_PASS;
String androidIps    = "";
String webPassword   = DEFAULT_ADMIN_PW;
String sharedSecret  = MASTER_CRYPTO_SECRET;
String macAddressStr = "";
bool is_licensed     = false;
int maxLicensedSlots = DEFAULT_MAX_SLOTS;

int targetPort        = DEFAULT_PORT;
float coinPrice       = DEFAULT_PRICE;
int minutesPerCoin    = DEFAULT_MINUTES;
int lockoutDebounceMs = DEFAULT_DEBOUNCE;

const unsigned long ARM_TTL = 15000;
const unsigned long MAX_SESSION_DURATION = 120000;
String armedIp = "";
unsigned long armedUntil = 0;
unsigned long sessionStartTime = 0;

String lastArmedDeviceId = "";
String lastArmedIp = "";
unsigned long lastArmedTimeMs = 0;
String pulseTrainDeviceId = "";
String pulseTrainDeviceIp = "";
bool pulseTrainWasArmed = false;
unsigned long pulseTrainStartTime = 0;
bool pendingWsGracefulClose = false;
unsigned long pendingWsGracefulCloseUntil = 0;

String p1Ip = "";
String p2Ip = "";
int matchMinutes = 15;
String matchStatusMsg = "";
String quickTimeStatusMsg = "";

// Credit Vault
int monthlyCredits = 0;
int annualCredits  = 0;
int testCredits    = 0;

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
    return 1772950000000ULL + (uint64_t)millis();
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
    String raw = "";
    for (int i = 0; i < maxLicensedSlots; i++) {
        if (i > 0) raw += ";";
        char expBuf[24];
        snprintf(expBuf, sizeof(expBuf), "%llu", (unsigned long long)licenseSlots[i].expiresAt);
        raw += String(licenseSlots[i].slotNum) + "|" +
               licenseSlots[i].deviceId + "|" +
               licenseSlots[i].ip + "|" +
               licenseSlots[i].name + "|" +
               String(expBuf) + "|" +
               (licenseSlots[i].active ? "1" : "0");
    }
    prefs.putString("slots_data", raw);
    syncAndroidIpsFromSlots();
    prefs.putString("ips", androidIps);
    prefs.end();
}

void loadSlotLicenses() {
    prefs.begin("kiosk_cfg", false);
    maxLicensedSlots = DEFAULT_MAX_SLOTS;

    for (int i = 0; i < MAX_SUPPORTED_SLOTS; i++) {
        licenseSlots[i].slotNum = i + 1;
        licenseSlots[i].deviceId = "";
        licenseSlots[i].ip = "";
        licenseSlots[i].name = "PisoPhone " + String(i + 1);
        licenseSlots[i].expiresAt = 0;
        licenseSlots[i].active = true;
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
                int p5 = (p4 != -1) ? item.indexOf('|', p4 + 1) : -1;

                if (p1 != -1 && p2 != -1 && p3 != -1 && p4 != -1) {
                    int sNum = item.substring(0, p1).toInt();
                    if (sNum >= 1 && sNum <= MAX_SUPPORTED_SLOTS) {
                        int idx = sNum - 1;
                        licenseSlots[idx].slotNum = sNum;
                        licenseSlots[idx].deviceId = item.substring(p1 + 1, p2);
                        licenseSlots[idx].ip = item.substring(p2 + 1, p3);
                        licenseSlots[idx].name = item.substring(p3 + 1, p4);
                        String expStr = (p5 != -1) ? item.substring(p4 + 1, p5) : item.substring(p4 + 1);
                        licenseSlots[idx].expiresAt = strtoull(expStr.c_str(), NULL, 10);
                        if (p5 != -1) {
                            licenseSlots[idx].active = (item.substring(p5 + 1) == "1");
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
        int startIdx = 0;
        int slotIdx = 0;
        while (startIdx < androidIps.length() && slotIdx < maxLicensedSlots) {
            int comma = androidIps.indexOf(',', startIdx);
            if (comma == -1) comma = androidIps.length();
            String entry = androidIps.substring(startIdx, comma);
            DeviceConfig cfg;
            if (parseDeviceEntry(entry, cfg)) {
                licenseSlots[slotIdx].deviceId = cfg.id;
                licenseSlots[slotIdx].ip = cfg.ip;
                licenseSlots[slotIdx].name = cfg.name.length() > 0 ? cfg.name : ("PisoPhone " + String(slotIdx + 1));
                licenseSlots[slotIdx].active = true;
                slotIdx++;
            }
            startIdx = comma + 1;
        }
    }
    prefs.end();
    syncAndroidIpsFromSlots();
}

void saveCreditVault() {
    prefs.begin("kiosk_cfg", false);
    prefs.putInt("cr_monthly", monthlyCredits);
    prefs.putInt("cr_annual", annualCredits);
    prefs.putInt("cr_test", testCredits);
    prefs.end();
}

void loadCreditVault() {
    prefs.begin("kiosk_cfg", false);
    monthlyCredits = prefs.getInt("cr_monthly", 0);
    annualCredits  = prefs.getInt("cr_annual", 0);
    testCredits    = prefs.getInt("cr_test", 0);
    prefs.end();
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
    coinPin = DEFAULT_COIN_PIN;
    universalCoinPin = DEFAULT_UNIVERSAL_COIN_PIN;
    ledPin = DEFAULT_LED_PIN;
    ledActiveLow = DEFAULT_LED_ACTIVE_LOW;
    relayPin = DEFAULT_RELAY_PIN;
    androidIps = "";
    targetPort = DEFAULT_PORT;
    webPassword = DEFAULT_ADMIN_PW;
    sharedSecret = MASTER_CRYPTO_SECRET;
    coinPrice = DEFAULT_PRICE;
    minutesPerCoin = DEFAULT_MINUTES;
    lockoutDebounceMs = DEFAULT_DEBOUNCE;
    p1Ip = "";
    p2Ip = "";
    matchMinutes = 15;
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
