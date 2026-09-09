#include <Arduino.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <WiFiUdp.h>
#include <HTTPClient.h>
#include <WebServer.h>
#include <Preferences.h>
#include <ESPmDNS.h>
#include <Update.h>
#include "esp_wifi.h"
#include "mbedtls/md.h"
#include "mbedtls/sha1.h"
#include "mbedtls/base64.h"
#include "mbedtls/aes.h"

// ============================================================================
// HARDWARE-C3 Master Kiosk Firmware
// Master-Slave Architecture: HARDWARE is the Absolute Source of Truth
// Pure Client (Station) Mode - Connects to local router, never sets a local AP
// Port 80: HTTP Web Configuration Portal, REST API, mDNS ("kioskmanager.local")
// Port 81: RFC6455 Low-Latency WebSocket Server for Real-Time Time Push
// ============================================================================

// Default Wi-Fi Credentials & Factory Recovery Settings
const char* DEFAULT_SSID        = "AdminSetup";
const char* DEFAULT_PASS        = "Admin@123";
const char* DEFAULT_ADMIN_PW    = "admin";
const char* MASTER_CRYPTO_SECRET  = ""; // SET VIA CAPTIVE PORTAL
const int   DEFAULT_COIN_PIN           = 4;
const int   DEFAULT_UNIVERSAL_COIN_PIN = 3;
const int   DEFAULT_LED_PIN            = 8;
const bool  DEFAULT_LED_ACTIVE_LOW     = true; // Tenstar Robot & ESP32-C3 Super Mini onboard LED is Active LOW
const int   DEFAULT_RELAY_PIN          = 5;
const int   DEFAULT_PORT               = 8080;
const float DEFAULT_PRICE              = 5.0f;
const int   DEFAULT_MINUTES            = 30;
const int   DEFAULT_DEBOUNCE           = 25;

// Hardware Fallback Reset Pin (GPIO 2 -> GND for 5 seconds = Factory Reset)
const int HARDWARE_RESET_PIN = 2;
unsigned long resetPinLowStart = 0;

// Global Objects
Preferences prefs;
WebServer webServer(80); // Port 80: Web Portal & REST API
WiFiServer wsServer(81); // Port 81: Real-time RFC6455 WebSocket Server
const int UDP_DISCOVERY_PORT = 8888; // Port 8888: Auto-Discovery Broadcast & Probe
WiFiUDP udpServer;

// Dynamic Hardware Pin Configuration (Persisted in NVS)
int coinPin          = DEFAULT_COIN_PIN;           // Linear beam sensor pin (Default GPIO 4, Pull-Up)
int universalCoinPin = DEFAULT_UNIVERSAL_COIN_PIN; // Universal multi-coin pulse sensor pin (Default GPIO 3, Pull-Up)
int ledPin           = DEFAULT_LED_PIN;            // Status/Drop indicator LED (Default GPIO 8)
bool ledActiveLow    = DEFAULT_LED_ACTIVE_LOW;     // Active LOW logic for Tenstar Robot & Super Mini onboard blue LED
int relayPin         = DEFAULT_RELAY_PIN;          // Coin slot enable / power relay pin (Default GPIO 5)
bool relayActiveLow  = false;                      // False = Active HIGH (default), True = Active LOW (optocoupler relay modules)
int relayMode        = 1;                          // 0 = Always powered when online, 1 = Armed-Only (Powered only when Insert Coin is active)

// Configuration Variables (Persisted in NVS)
String wifiSsid       = DEFAULT_SSID;
String wifiPass       = DEFAULT_PASS;
String androidIps     = "";

// Hardware Licensing (Offline Crypto Activation)
bool is_licensed = false;
String macAddressStr = "";

struct DeviceConfig {
    String id;
    String ip;
    String name;
};

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

        // Disambiguate if p1 is IP and p2 is not
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
            // E.g. "deviceId|192.168.4.2"
            out.id = p1;
            out.ip = p2;
            out.name = "";
        } else if (p1.indexOf('.') != -1) {
            // E.g. "192.168.4.2|PisoPhone 1"
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

    // Must have a valid IPv4 address (must have dots, min length 7 e.g. 1.1.1.1, cannot be 127.0.0.1 or 0.0.0.0)
    if (out.ip.length() < 7 || out.ip.indexOf('.') == -1 || out.ip == "127.0.0.1" || out.ip == "0.0.0.0") {
        return false;
    }
    return true;
}

// ============================================================================
// FLEXIBLE SEAT & SLOT LICENSING SYSTEM (ESP32 HARDWARE-BOUND)
// ============================================================================
#define MAX_SUPPORTED_SLOTS 12
#define DEFAULT_MAX_SLOTS 12
#define MAX_SLOTS maxLicensedSlots

struct LicenseSlot {
    int slotNum;            // 1 to 12
    String deviceId;        // Canonical hardware ID (e.g., "HW-A1B2C3D4")
    String ip;              // Terminal local IP (e.g., "192.168.4.2")
    String name;            // Display label (e.g., "PisoPhone 1")
    uint64_t expiresAt;     // Expiration timestamp in ms
    bool active;            // Whether slot is valid/licensed
};

int maxLicensedSlots = DEFAULT_MAX_SLOTS;
LicenseSlot licenseSlots[MAX_SUPPORTED_SLOTS];

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
    maxLicensedSlots = 12; // Strictly enforce 12 maximum slots limit

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

bool pairDeviceToSlot(int slotNum, String devId, String ip, String name) {
    if (slotNum < 1 || slotNum > maxLicensedSlots) return false;
    int targetIdx = slotNum - 1;
    devId.trim();
    ip.trim();
    name.trim();

    for (int i = 0; i < maxLicensedSlots; i++) {
        if (i != targetIdx && licenseSlots[i].deviceId.length() > 0 && licenseSlots[i].deviceId == devId) {
            licenseSlots[i].deviceId = "";
            licenseSlots[i].ip = "";
            licenseSlots[i].expiresAt = 0;
        }
    }

    licenseSlots[targetIdx].deviceId = devId;
    if (ip.length() > 0) licenseSlots[targetIdx].ip = ip;
    if (name.length() > 0) licenseSlots[targetIdx].name = name;
    licenseSlots[targetIdx].active = true;
    // Note: Credit-based activation requires manual or voucher credit allocation to pair/arm

    saveSlotLicenses();
    Serial.printf("[+] Paired device %s (%s) to Slot #%d (requires credit allocation to arm)\n", devId.c_str(), ip.c_str(), slotNum);
    return true;
}

bool unpairSlot(int slotNum) {
    if (slotNum < 1 || slotNum > maxLicensedSlots) return false;
    int idx = slotNum - 1;
    Serial.printf("[+] Unpairing Slot #%d (was %s). Seat remains open.\n", slotNum, licenseSlots[idx].deviceId.c_str());
    licenseSlots[idx].deviceId = "";
    licenseSlots[idx].ip = "";
    licenseSlots[idx].expiresAt = 0;
    saveSlotLicenses();
    return true;
}

int findSlotIndexForDevice(String devId, String ip) {
    devId.trim();
    ip.trim();
    if (devId.length() > 0) {
        for (int i = 0; i < maxLicensedSlots; i++) {
            if (licenseSlots[i].deviceId.length() > 0 && licenseSlots[i].deviceId == devId) {
                return i;
            }
        }
    }
    if (ip.length() > 0 && ip != "127.0.0.1") {
        for (int i = 0; i < maxLicensedSlots; i++) {
            if (licenseSlots[i].ip.length() > 0 && licenseSlots[i].ip == ip) {
                return i;
            }
        }
    }
    return -1;
}

// ============================================================================
// CREDIT-BASED ACTIVATION & EXPIRATION VAULT (NVS-BACKED)
// ============================================================================
int monthlyCredits = 0; // 1 Credit = 30 Days (₱50)
int annualCredits  = 0; // 1 Credit = 365 Days (₱500)
int testCredits    = 0; // 1 Credit = 2 Minutes (For rapid expiration testing)

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

// Monotonic Master Clock derived from synchronized Android telemetry timestamps
uint64_t lastMasterTimestamp = 0;
unsigned long lastMasterMillis = 0;

uint64_t getCurrentMasterTimeMs() {
    if (lastMasterTimestamp > 0) {
        return lastMasterTimestamp + (uint64_t)(millis() - lastMasterMillis);
    }
    // Safe baseline if telemetry has not arrived yet
    return 1772950000000ULL + (uint64_t)millis();
}

void updateMasterTime(uint64_t ts) {
    if (ts > lastMasterTimestamp) {
        lastMasterTimestamp = ts;
        lastMasterMillis = millis();
    }
}

// Allocates 1 credit from vault to extend or activate a terminal slot with stacking
bool allocateCreditToSlot(int slotNum, String type, String& errorMsg) {
    if (slotNum < 1 || slotNum > maxLicensedSlots) {
        errorMsg = "Invalid slot number";
        return false;
    }
    int idx = slotNum - 1;
    type.toLowerCase();
    type.trim();

    uint64_t durationMs = 0;
    if (type == "month") {
        if (monthlyCredits < 1) {
            errorMsg = "No monthly credits available in vault. Please purchase credits on website.";
            return false;
        }
        monthlyCredits--;
        durationMs = 30ULL * 86400000ULL; // 30 days
    } else if (type == "year") {
        if (annualCredits < 1) {
            errorMsg = "No annual credits available in vault. Please purchase credits on website.";
            return false;
        }
        annualCredits--;
        durationMs = 365ULL * 86400000ULL; // 365 days
    } else if (type == "test") {
        if (testCredits > 0) testCredits--;
        durationMs = 120000ULL; // 2 minutes (120,000 ms)
    } else {
        errorMsg = "Unknown credit type. Must be 'month', 'year', or 'test'";
        return false;
    }

    uint64_t currentMs = getCurrentMasterTimeMs();
    // Stacking: If slot has not expired yet, stack onto remaining time! Otherwise, start from current master time.
    uint64_t baseTs = (licenseSlots[idx].expiresAt > currentMs) ? licenseSlots[idx].expiresAt : currentMs;
    licenseSlots[idx].expiresAt = baseTs + durationMs;
    licenseSlots[idx].active = true;

    saveCreditVault();
    saveSlotLicenses();
    Serial.printf("[+] Credit Allocated: Slot #%d +%s (New Expiry: %llu). Vault: M=%d, Y=%d, T=%d\n",
        slotNum, type.c_str(), (unsigned long long)licenseSlots[idx].expiresAt,
        monthlyCredits, annualCredits, testCredits);
    return true;
}

// Returns: 0 = Active, 1 = Warning (<= 7 days left, or <= 1 min if test), 2 = Expired / Inactive / Uncredited
int getSlotExpirationStatus(int slotIdx, uint64_t currentMs, int& outDaysLeft) {
    outDaysLeft = -1;
    if (slotIdx < 0 || slotIdx >= maxLicensedSlots) {
        return 2; // Expired / Not assigned
    }
    if (!licenseSlots[slotIdx].active) {
        return 2; // Inactive
    }
    if (licenseSlots[slotIdx].expiresAt == 0) {
        outDaysLeft = 0;
        return 2; // Uncredited slot: requires credit allocation to pair/arm
    }
    if (currentMs >= licenseSlots[slotIdx].expiresAt) {
        outDaysLeft = 0;
        return 2; // Expired!
    }
    uint64_t diff = licenseSlots[slotIdx].expiresAt - currentMs;
    uint64_t oneDayMs = 86400000ULL;
    outDaysLeft = (int)(diff / oneDayMs);
    // Warning if <= 7 days left, or if test credit under 60 seconds
    if (diff <= (7ULL * oneDayMs) || (diff <= 60000ULL)) {
        return 1; // Nearing expiration
    }
    return 0; // Active
}


int targetPort        = DEFAULT_PORT;
String sharedSecret   = MASTER_CRYPTO_SECRET;
String webPassword    = DEFAULT_ADMIN_PW;
float coinPrice       = DEFAULT_PRICE;
int minutesPerCoin    = DEFAULT_MINUTES;
int lockoutDebounceMs = DEFAULT_DEBOUNCE; // Refractory lockout window (25ms)
String p1Ip           = "";
String p2Ip           = "";
int matchMinutes      = 15;
String matchStatusMsg = "";
String quickTimeStatusMsg = "";

// Persistent Local Revenue Counter (NVS Coin & Earnings Audit)
uint32_t totalCoinsLifetime = 0;
uint32_t totalCoinsSession  = 0;
float totalEarningsLifetime = 0.0f;
float totalEarningsSession  = 0.0f;
uint32_t lastSavedTotalCoins = 0;
float lastSavedTotalEarnings = 0.0f;

// Debounced Revenue NVS Persistence State (Hardware-Conservative Wear-Leveling Protection)
bool revenueDirty = false;
unsigned long lastCoinChangeTime = 0;
const unsigned long REVENUE_SAVE_DELAY_MS = 5000; // 5 seconds idle debounce

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

// Wireless Update Validation State
bool otaUpdateSuccess = false;
bool otaFirstChunkReceived = false;
bool otaIsValidBinary = true;
String otaErrorMsg = "";

// ============================================================================
// INDUSTRY-STANDARD 3-STATE HARDWARE COIN DEBOUNCING ENGINE
// ============================================================================
enum CoinState {
    COIN_IDLE,
    COIN_DETECTING,
    COIN_LOCKOUT
};

CoinState currentCoinState = COIN_IDLE;
unsigned long pulseStartMs = 0;
unsigned long lockoutStartMs = 0;
const unsigned long MIN_PULSE_WIDTH_MS = 20; // 20ms continuous LOW qualification

// Non-Blocking LED Indicator State Machine
enum LedSystemState {
    LED_STATE_CONNECTING,   // Rapid Blink (100ms ON / 100ms OFF) - Actively connecting or seeking Wi-Fi
    LED_STATE_FAILED,       // Slow Blink (500ms ON / 500ms OFF)  - Lost connection / disconnected
    LED_STATE_CONNECTED     // Solid HIGH                         - Connected successfully
};

LedSystemState currentLedState = LED_STATE_CONNECTING;

unsigned long lastLedToggleTime = 0;
const unsigned long LED_RAPID_TOGGLE_MS = 100;
const unsigned long LED_SLOW_TOGGLE_MS  = 500;
const unsigned long LED_COIN_PULSE_MS   = 60;   // 60ms per pulse step for a quick snappy double blink
int ledBlinksRemaining = 0;
bool ledState = false;

// Universal LED Hardware Writer
// Correctly handles Active LOW (Tenstar Robot / ESP32-C3 Super Mini onboard blue LED) and Active HIGH (External LED)
void setLedHardware(bool on) {
    pinMode(ledPin, OUTPUT);
    digitalWrite(ledPin, (on ^ ledActiveLow) ? HIGH : LOW);
}

void triggerLedBlink(int blinkCount = 2) {
    // A quick double blink from solid ON state means:
    // OFF (0-60ms) -> ON (60-120ms) -> OFF (120-180ms) -> ON (returns to solid)
    ledBlinksRemaining = 3;
    ledState = false;
    setLedHardware(false);
    lastLedToggleTime = millis();
}

void processLedBlink() {
    unsigned long now = millis();

    // 1. Transient Coin Insertion Double Blink Override
    if (ledBlinksRemaining > 0) {
        if (now - lastLedToggleTime >= LED_COIN_PULSE_MS) {
            lastLedToggleTime = now;
            ledBlinksRemaining--;
            ledState = !ledState;
            setLedHardware(ledState);
            if (ledBlinksRemaining == 0) {
                // Ensure we return cleanly to solid ON if connected
                if (currentLedState == LED_STATE_CONNECTED) {
                    setLedHardware(true);
                    ledState = true;
                }
            }
        }
        return;
    }

    // 2. Wi-Fi Status LED Indicator Patterns
    switch (currentLedState) {
        case LED_STATE_CONNECTING:
            // Fast rapid blink (100ms ON / 100ms OFF) when searching for networks or connecting
            if (now - lastLedToggleTime >= LED_RAPID_TOGGLE_MS) {
                lastLedToggleTime = now;
                ledState = !ledState;
                setLedHardware(ledState);
            }
            break;

        case LED_STATE_FAILED:
            // Slow heartbeat blink (500ms ON / 500ms OFF) when disconnected or in retry cooldown
            if (now - lastLedToggleTime >= LED_SLOW_TOGGLE_MS) {
                lastLedToggleTime = now;
                ledState = !ledState;
                setLedHardware(ledState);
            }
            break;

        case LED_STATE_CONNECTED:
            if (!ledState) {
                setLedHardware(true);
                ledState = true;
            }
            break;
    }
}

// Runtime State & Non-Blocking Session Tracking
const unsigned long ARM_TTL = 15000;               // 15 seconds slot arming window
const unsigned long MAX_SESSION_DURATION = 120000; // 2 minutes maximum arm cap
String armedIp = "";
unsigned long armedUntil = 0;
unsigned long sessionStartTime = 0;

// Active WebSocket Client on Port 81
WiFiClient wsClient;
bool isWsConnected = false;

// Timing Supervisors
unsigned long lastWifiCheckTime = 0;
unsigned long lastOutgoingHeartbeatTime = 0;

// Forward Declarations
void triggerCoinEvent();
void triggerUniversalCoinEvent(int pulses);
void factoryResetDefaults();
int getDeviceTimeRemainingSeconds(String targetIp, String* errOut = nullptr);
String getIpFromDeviceId(String id);

// Helper function to check if default credentials are still active
bool areDefaultCredentialsActive() {
    return (webPassword == DEFAULT_ADMIN_PW || wifiPass == DEFAULT_PASS);
}

// Controls the physical relay state based on polarity (relayActiveLow)
void setRelayHardware(bool active) {
    if (active) {
        pinMode(relayPin, OUTPUT);
        bool pinLevel = relayActiveLow ? LOW : HIGH;
        digitalWrite(relayPin, pinLevel);
    } else {
        // Sensitive relays trigger on both +V and GND.
        // Set pin to INPUT (High Impedance / floating) so neither +V nor GND potential activates it when idle.
        pinMode(relayPin, INPUT);
    }
}

// Helper function to check if the coin slot is currently armed
bool isSlotArmed() {
    return (armedIp.length() > 0 && millis() < armedUntil);
}

// Controls the coin slot relay: Mode 0 = Always powered when online, Mode 1 = Powered only when armed
void processRelayState() {
    bool shouldBeOn = (relayMode == 0) ? (WiFi.status() == WL_CONNECTED || millis() > 4000) : isSlotArmed();
    static int lastAppliedRelayState = -1;
    int cur = shouldBeOn ? 1 : 0;
    if (cur != lastAppliedRelayState) {
        lastAppliedRelayState = cur;
        setRelayHardware(shouldBeOn);
        Serial.printf("[⚡ RELAY] Pin %d set to %s (ActiveLow=%s, Mode=%d, SlotArmed=%s)\n",
            relayPin, shouldBeOn ? "ON (POWERED)" : "OFF (STANDBY)",
            relayActiveLow ? "true" : "false", relayMode, isSlotArmed() ? "true" : "false");
    }
}


// ============================================================================
// HARDWARE DEBOUNCER STATE MACHINE - LINEAR BEAM SENSOR (GPIO 4)
// ============================================================================
void processCoinDetector() {
    unsigned long now = millis();
    // Ignore any power-on transients during the initial 3 seconds of bootup
    if (now < 3000) {
        currentCoinState = COIN_IDLE;
        return;
    }

    int pinVal = digitalRead(coinPin);

    switch (currentCoinState) {
        case COIN_IDLE:
            if (pinVal == LOW) {
                pulseStartMs = now;
                currentCoinState = COIN_DETECTING;
            }
            break;

        case COIN_DETECTING:
            if (pinVal == LOW) {
                if (now - pulseStartMs >= MIN_PULSE_WIDTH_MS) {
                    Serial.printf("[⚡ COIN BEAM] Pin %d pulse verified (%lu ms LOW)! Triggering coin event...\n", coinPin, now - pulseStartMs);
                    triggerCoinEvent();
                    lockoutStartMs = now;
                    currentCoinState = COIN_LOCKOUT;
                }
            } else {
                // Signal went HIGH before reaching the minimum pulse width.
                // This is a bounce or EMI glitch, abort detection.
                currentCoinState = COIN_IDLE;
            }
            break;

        case COIN_LOCKOUT:
            if (pinVal == HIGH) {
                // Must remain HIGH for the lockout duration before accepting new coins
                if (now - lockoutStartMs >= (unsigned long)lockoutDebounceMs) {
                    currentCoinState = COIN_IDLE;
                }
            } else {
                // If it bounces back to LOW during lockout, reset the lockout timer
                // to ensure we only exit after a continuous HIGH period.
                lockoutStartMs = now;
            }
            break;
    }
}

// ============================================================================
// HARDWARE PULSE ISR & DEBOUNCER - UNIVERSAL MULTI-COIN ACCEPTOR (GPIO 3)
// Denominations: 1 PHP = 1 pulse, 5 PHP = 5 pulses, 10 PHP = 10 pulses, 20 PHP = 20 pulses
// ============================================================================
volatile int isrUniversalPulseCount = 0;
volatile unsigned long isrLastPulseTimeMs = 0;
const unsigned long U_MIN_PULSE_DEBOUNCE_MS = 8;      // 8ms debounce filters electrical noise while capturing 10ms-100ms coin pulses
const unsigned long U_INTER_PULSE_TIMEOUT_MS = 280;   // 280ms quiet period marks end of coin insertion train

// Session and pulse train tracking for zero credit loss during rapid clicks or session transitions
String lastArmedDeviceId = "";
String lastArmedIp = "";
unsigned long lastArmedTimeMs = 0;
String pulseTrainDeviceId = "";
String pulseTrainDeviceIp = "";
bool pulseTrainWasArmed = false;
unsigned long pulseTrainStartTime = 0;
bool pendingWsGracefulClose = false;
unsigned long pendingWsGracefulCloseUntil = 0;

void IRAM_ATTR universalCoinIsr() {
    unsigned long now = millis();
    // Debounce to filter out high frequency contact bounces (< 8ms)
    if (now - isrLastPulseTimeMs >= U_MIN_PULSE_DEBOUNCE_MS) {
        isrUniversalPulseCount++;
        isrLastPulseTimeMs = now;
    }
}

void processUniversalCoinDetector() {
    unsigned long now = millis();
    if (now < 3000) {
        if (isrUniversalPulseCount > 0) {
            noInterrupts();
            isrUniversalPulseCount = 0;
            interrupts();
        }
        return;
    }

    noInterrupts();
    int count = isrUniversalPulseCount;
    unsigned long lastPulseTime = isrLastPulseTimeMs;
    interrupts();

    // Lock in the device origin and armed status as soon as the first pulse arrives
    if (count > 0 && pulseTrainStartTime == 0) {
        pulseTrainStartTime = lastPulseTime;
        pulseTrainDeviceId = (armedIp.length() > 0) ? armedIp : lastArmedDeviceId;
        pulseTrainDeviceIp = (armedIp.length() > 0) ? getIpFromDeviceId(armedIp) : lastArmedIp;
        pulseTrainWasArmed = isSlotArmed() || (millis() - lastArmedTimeMs < 10000);
    }

    // When pulses have arrived and no new pulses occurred for U_INTER_PULSE_TIMEOUT_MS, train is complete!
    if (count > 0 && (now - lastPulseTime >= U_INTER_PULSE_TIMEOUT_MS)) {
        noInterrupts();
        int finalPulses = isrUniversalPulseCount;
        isrUniversalPulseCount = 0;
        interrupts();

        pulseTrainStartTime = 0; // Reset for next coin train

        if (finalPulses > 0) {
            Serial.printf("[⚡ UNIVERSAL COIN] Detected %d pulse(s) on GPIO %d! Triggering coin event...\n", finalPulses, universalCoinPin);
            triggerUniversalCoinEvent(finalPulses);
        }

        // If client sent DONE while this coin was pulsing, finalize session closure now
        if (pendingWsGracefulClose) {
            pendingWsGracefulClose = false;
            if (isWsConnected && wsClient.connected()) {
                wsClient.stop();
            }
            isWsConnected = false;
            armedIp = "";
            armedUntil = 0;
            sessionStartTime = 0;
            Serial.println("[*] Graceful WS session close completed after delivering final coin pulses.");
        }
    }
}

// ============================================================================
// CRYPTOGRAPHY (HMAC-SHA256 & RFC6455 WebSocket Handshake)
// ============================================================================
String calculateHMAC(String challenge, String secret) {
    mbedtls_md_context_t ctx;
    mbedtls_md_type_t md_type = MBEDTLS_MD_SHA256;
    mbedtls_md_init(&ctx);
    mbedtls_md_setup(&ctx, mbedtls_md_info_from_type(md_type), 1);
    mbedtls_md_hmac_starts(&ctx, (const unsigned char*) secret.c_str(), secret.length());
    mbedtls_md_hmac_update(&ctx, (const unsigned char*) challenge.c_str(), challenge.length());
    unsigned char hmacResult[32];
    mbedtls_md_hmac_finish(&ctx, hmacResult);
    mbedtls_md_free(&ctx);
    
    String hex = "";
    for (int i = 0; i < 32; i++) {
        char buf[3];
        sprintf(buf, "%02x", hmacResult[i]);
        hex += buf;
    }
    return hex;
}

// SINGLE-AUTH-PATH Verification for Hardware License Slot Expansion
bool applySlotToken(String token) {
    token.trim();
    if (!token.startsWith("PISOSLOT.")) return false;

    int dot1 = token.indexOf('.');
    int dot2 = token.indexOf('.', dot1 + 1);
    int dot3 = token.indexOf('.', dot2 + 1);
    int dot4 = token.indexOf('.', dot3 + 1);

    if (dot1 == -1 || dot2 == -1 || dot3 == -1 || dot4 == -1) return false;

    String tokenMac = token.substring(dot1 + 1, dot2);
    String slotsStr = token.substring(dot2 + 1, dot3);
    String expStr   = token.substring(dot3 + 1, dot4);
    String sig      = token.substring(dot4 + 1);

    tokenMac.trim(); tokenMac.toUpperCase();
    slotsStr.trim();
    expStr.trim();
    sig.trim();

    String myMac = macAddressStr;
    myMac.trim(); myMac.toUpperCase();
    if (!tokenMac.equalsIgnoreCase(myMac)) {
        Serial.printf("[-] Slot token MAC mismatch: Token has %s, Box is %s\n", tokenMac.c_str(), myMac.c_str());
        return false;
    }

    String payload = "PISOSLOT:" + tokenMac + ":" + slotsStr + ":" + expStr;
    String expectedSig = calculateHMAC(payload, sharedSecret);
    if (!sig.equalsIgnoreCase(expectedSig)) {
        Serial.println("[-] Invalid slot token HMAC signature!");
        return false;
    }

    int newSlots = slotsStr.toInt();
    if (newSlots < 1) newSlots = DEFAULT_MAX_SLOTS;
    if (newSlots > MAX_SUPPORTED_SLOTS) newSlots = MAX_SUPPORTED_SLOTS;

    uint64_t newExp = strtoull(expStr.c_str(), NULL, 10);

    maxLicensedSlots = max(maxLicensedSlots, newSlots);
    for (int i = 0; i < maxLicensedSlots; i++) {
        licenseSlots[i].active = true;
        if (licenseSlots[i].expiresAt < newExp) {
            licenseSlots[i].expiresAt = newExp;
        }
    }

    is_licensed = true;
    saveSlotLicenses();
    Serial.printf("[+] Successfully applied Slot License Token: Capacity expanded to %d slots!\n", maxLicensedSlots);
    return true;
}

// Single Snapshot Reporting to Cloudflare Worker
void sendCloudSnapshot() {
    if (WiFi.status() != WL_CONNECTED) return;
    if (ESP.getFreeHeap() < 35000) {
        Serial.printf("[☁️ CLOUD] Skipping snapshot report, free heap low (%u bytes)\n", ESP.getFreeHeap());
        return;
    }
    
    WiFiClientSecure client;
    client.setInsecure();
    client.setTimeout(4);

    HTTPClient http;
    http.setTimeout(4000);
    if (!http.begin(client, "https://pisophone-api.pisophone-support.workers.dev/api/box/report-snapshot")) {
        return;
    }
    http.addHeader("Content-Type", "application/json");

    String json = "{";
    json += "\"mac\":\"" + macAddressStr + "\",";
    json += "\"tier\":" + String(maxLicensedSlots) + ",";
    json += "\"lifetimeCoins\":" + String(totalCoinsLifetime) + ",";
    json += "\"lifetimeEarnings\":" + String(totalEarningsLifetime, 2) + ",";
    json += "\"wifiSsid\":\"" + wifiSsid + "\",";
    json += "\"firmwareVersion\":\"2.4.0-SLOT-MANAGER\",";
    json += "\"slots\":[";
    for (int i = 0; i < maxLicensedSlots; i++) {
        if (i > 0) json += ",";
        char expBuf[24];
        snprintf(expBuf, sizeof(expBuf), "%llu", (unsigned long long)licenseSlots[i].expiresAt);
        json += "{";
        json += "\"slotNum\":" + String(licenseSlots[i].slotNum) + ",";
        json += "\"deviceId\":\"" + licenseSlots[i].deviceId + "\",";
        json += "\"ip\":\"" + licenseSlots[i].ip + "\",";
        json += "\"name\":\"" + licenseSlots[i].name + "\",";
        json += "\"expiresAt\":" + String(expBuf);
        json += "}";
    }
    json += "]}";

    int code = http.POST(json);
    if (code > 0) {
        Serial.printf("[☁️ CLOUD] Snapshot reported successfully (HTTP %d)\n", code);
    } else {
        Serial.printf("[☁️ CLOUD] Snapshot report failed: %s\n", http.errorToString(code).c_str());
    }
    http.end();
}

String aes_encrypt(String plaintext, String secret) {
    uint8_t aes_key[32];
    mbedtls_md_context_t sha_ctx;
    mbedtls_md_init(&sha_ctx);
    mbedtls_md_setup(&sha_ctx, mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), 0);
    mbedtls_md_starts(&sha_ctx);
    mbedtls_md_update(&sha_ctx, (const unsigned char*)secret.c_str(), secret.length());
    mbedtls_md_finish(&sha_ctx, aes_key);
    mbedtls_md_free(&sha_ctx);

    uint8_t iv[16];
    for (int i = 0; i < 16; i += 4) {
        uint32_t r = esp_random();
        memcpy(iv + i, &r, 4);
    }

    size_t plaintext_len = plaintext.length();
    size_t padding_len = 16 - (plaintext_len % 16);
    size_t padded_len = plaintext_len + padding_len;
    uint8_t* padded_input = (uint8_t*)malloc(padded_len);
    if (!padded_input) return "";
    memcpy(padded_input, plaintext.c_str(), plaintext_len);
    for (size_t i = plaintext_len; i < padded_len; i++) {
        padded_input[i] = (uint8_t)padding_len;
    }

    mbedtls_aes_context aes_ctx;
    mbedtls_aes_init(&aes_ctx);
    mbedtls_aes_setkey_enc(&aes_ctx, aes_key, 256);

    uint8_t* ciphertext = (uint8_t*)malloc(padded_len);
    if (!ciphertext) {
        free(padded_input);
        mbedtls_aes_free(&aes_ctx);
        return "";
    }
    uint8_t iv_tmp[16];
    memcpy(iv_tmp, iv, 16);

    mbedtls_aes_crypt_cbc(&aes_ctx, MBEDTLS_AES_ENCRYPT, padded_len, iv_tmp, padded_input, ciphertext);
    mbedtls_aes_free(&aes_ctx);
    free(padded_input);

    String hex_result = "";
    char hex_char[3];
    for (int i = 0; i < 16; i++) {
        sprintf(hex_char, "%02x", iv[i]);
        hex_result += hex_char;
    }
    for (size_t i = 0; i < padded_len; i++) {
        sprintf(hex_char, "%02x", ciphertext[i]);
        hex_result += hex_char;
    }
    free(ciphertext);
    return hex_result;
}

String aes_decrypt(String encryptedHex, String secret) {
    if (encryptedHex.length() < 32) return "";
    
    size_t total_bytes = encryptedHex.length() / 2;
    uint8_t* data = (uint8_t*)malloc(total_bytes);
    if (!data) return "";
    for (size_t i = 0; i < total_bytes; i++) {
        String part = encryptedHex.substring(i * 2, i * 2 + 2);
        data[i] = (uint8_t)strtol(part.c_str(), NULL, 16);
    }
    
    if (total_bytes < 17) {
        free(data);
        return "";
    }
    
    uint8_t iv[16];
    memcpy(iv, data, 16);
    
    size_t ciphertext_len = total_bytes - 16;
    uint8_t* ciphertext = data + 16;
    
    uint8_t aes_key[32];
    mbedtls_md_context_t sha_ctx;
    mbedtls_md_init(&sha_ctx);
    mbedtls_md_setup(&sha_ctx, mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), 0);
    mbedtls_md_starts(&sha_ctx);
    mbedtls_md_update(&sha_ctx, (const unsigned char*)secret.c_str(), secret.length());
    mbedtls_md_finish(&sha_ctx, aes_key);
    mbedtls_md_free(&sha_ctx);
    
    mbedtls_aes_context aes_ctx;
    mbedtls_aes_init(&aes_ctx);
    mbedtls_aes_setkey_dec(&aes_ctx, aes_key, 256);
    
    uint8_t* decrypted = (uint8_t*)malloc(ciphertext_len);
    if (!decrypted) {
        mbedtls_aes_free(&aes_ctx);
        free(data);
        return "";
    }
    uint8_t iv_tmp[16];
    memcpy(iv_tmp, iv, 16);
    
    mbedtls_aes_crypt_cbc(&aes_ctx, MBEDTLS_AES_DECRYPT, ciphertext_len, iv_tmp, ciphertext, decrypted);
    mbedtls_aes_free(&aes_ctx);
    free(data);
    
    uint8_t padding_len = decrypted[ciphertext_len - 1];
    if (padding_len > ciphertext_len || padding_len > 16 || padding_len == 0) {
        free(decrypted);
        return "";
    }
    
    size_t plaintext_len = ciphertext_len - padding_len;
    String plaintext = "";
    for (size_t i = 0; i < plaintext_len; i++) {
        plaintext += (char)decrypted[i];
    }
    
    free(decrypted);
    return plaintext;
}

String computeSecWebSocketAccept(String key) {
    key.trim();
    String concat = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    unsigned char sha1Result[20];
    mbedtls_sha1((const unsigned char*)concat.c_str(), concat.length(), sha1Result);
    
    unsigned char base64Result[36];
    size_t outLen = 0;
    mbedtls_base64_encode(base64Result, sizeof(base64Result), &outLen, sha1Result, 20);
    base64Result[outLen] = 0;
    return String((char*)base64Result);
}

// ============================================================================
// RFC6455 WEBSOCKET PROTOCOL ENCODING & DECODING (PORT 81)
// ============================================================================
void sendWsText(WiFiClient& client, String text) {
    if (!client.connected()) return;
    size_t len = text.length();
    uint8_t header[10];
    size_t headerLen = 0;
    
    header[0] = 0x81; // FIN + Text opcode (0x1)
    if (len < 126) {
        header[1] = (uint8_t)len;
        headerLen = 2;
    } else if (len <= 65535) {
        header[1] = 126;
        header[2] = (uint8_t)((len >> 8) & 0xFF);
        header[3] = (uint8_t)(len & 0xFF);
        headerLen = 4;
    } else {
        header[1] = 127;
        for (int i = 0; i < 8; i++) {
            header[2 + i] = (uint8_t)((len >> ((7 - i) * 8)) & 0xFF);
        }
        headerLen = 10;
    }
    client.write(header, headerLen);
    client.write((const uint8_t*)text.c_str(), len);
    client.flush();
}

String readWsText(WiFiClient& client) {
    if (!client.available()) return "";
    int b0 = client.read();
    if (b0 < 0) return "";
    int opcode = b0 & 0x0F;
    if (opcode == 0x08) {
        return "CLOSE";
    }
    int b1 = client.read();
    if (b1 < 0) return "";
    bool isMasked = (b1 & 0x80) != 0;
    uint64_t payloadLen = b1 & 0x7F;
    if (payloadLen == 126) {
        int l0 = client.read();
        int l1 = client.read();
        payloadLen = (l0 << 8) | l1;
    } else if (payloadLen == 127) {
        payloadLen = 0;
        for (int i = 0; i < 8; i++) {
            payloadLen = (payloadLen << 8) | client.read();
        }
    }
    
    uint8_t mask[4] = {0, 0, 0, 0};
    if (isMasked) {
        for (int i = 0; i < 4; i++) {
            mask[i] = client.read();
        }
    }
    
    String payload = "";
    if (payloadLen > 0 && payloadLen < 65536) {
        payload.reserve(payloadLen);
    }
    for (size_t i = 0; i < payloadLen && client.available(); i++) {
        uint8_t c = client.read();
        if (isMasked) {
            c ^= mask[i % 4];
        }
        payload += (char)c;
    }
    
    if (opcode == 0x09) { // Ping received, send Pong
        uint8_t header[2];
        header[0] = 0x8A; // FIN + Pong (0xA)
        header[1] = payloadLen & 0x7F;
        client.write(header, 2);
        if (payloadLen > 0) {
            client.write((const uint8_t*)payload.c_str(), payloadLen);
        }
        client.flush();
        return ""; // Ignore at application level
    } else if (opcode == 0x0A) { // Pong received
        return ""; // Ignore at application level
    }
    
    return payload;
}

String urlEncode(const String &str) {
    String encoded = "";
    char c;
    for (int i = 0; i < str.length(); i++) {
        c = str.charAt(i);
        if (isalnum(c) || c == '-' || c == '_' || c == '.' || c == '~') {
            encoded += c;
        } else if (c == ' ') {
            encoded += '+';
        } else {
            char code0 = (c >> 4) & 0xf;
            char code1 = c & 0xf;
            encoded += '%';
            encoded += (char)(code0 > 9 ? code0 - 10 + 'A' : code0 + '0');
            encoded += (char)(code1 > 9 ? code1 - 10 + 'A' : code1 + '0');
        }
    }
    return encoded;
}

// ============================================================================
// AUTHENTICATED REQUESTER (Worker Queue & FreeRTOS Supervisor Task)
// ============================================================================
struct AuthRequest {
    char ip[24];
    int port;
    char actionPath[48];
    char challengePath[48];
    char params[256];
    int timeoutMs;
};

QueueHandle_t authQueue = NULL;

void authWorkerTask(void *pvParameters) {
    AuthRequest req;
    while (true) {
        if (xQueueReceive(authQueue, &req, portMAX_DELAY) == pdTRUE) {
            if (WiFi.status() != WL_CONNECTED) {
                vTaskDelay(pdMS_TO_TICKS(100));
                continue;
            }

            HTTPClient http;
            http.setConnectTimeout(req.timeoutMs);
            http.setTimeout(req.timeoutMs);
            http.setReuse(false);

            String ip = String(req.ip);
            if (ip.length() == 0) continue;

            String actionUrl = "http://" + ip + ":" + String(req.port) + String(req.actionPath);
            
            String finalParams = String(req.params);
            if (finalParams.length() > 0) {
                finalParams += "&ts=" + String(millis());
            } else {
                finalParams = "ts=" + String(millis());
            }
            
            if (finalParams.indexOf("tx_id=") == -1 && finalParams.indexOf("nonce=") == -1) {
                String txId = String(millis()) + "-" + String(random(1000, 9999));
                finalParams += "&tx_id=" + txId;
            }

            String encryptedPayload = aes_encrypt(finalParams, sharedSecret);
            actionUrl += "?payload=" + encryptedPayload;

            if (http.begin(actionUrl)) {
                int code = http.GET();
                Serial.printf("[⚡ AUTH WORKER] GET %s -> Response %d\n", actionUrl.c_str(), code);
                http.end();
            }
            vTaskDelay(pdMS_TO_TICKS(40)); // Prevent socket/radio contention
        }
    }
}

void sendAuthenticated(String ip, int port, String actionPath, String challengePath = "/challenge", String params = "", int timeoutMs = 1500) {
    if (WiFi.status() != WL_CONNECTED || authQueue == NULL) return;
    
    AuthRequest req;
    memset(&req, 0, sizeof(AuthRequest));
    strncpy(req.ip, ip.c_str(), sizeof(req.ip) - 1);
    req.port = port;
    strncpy(req.actionPath, actionPath.c_str(), sizeof(req.actionPath) - 1);
    strncpy(req.challengePath, challengePath.c_str(), sizeof(req.challengePath) - 1);
    strncpy(req.params, params.c_str(), sizeof(req.params) - 1);
    req.timeoutMs = timeoutMs;
    
    // Non-blocking post to queue (drops if full rather than crashing)
    xQueueSend(authQueue, &req, 0);
}

// ============================================================================
// IP MANAGEMENT & REAL-TIME HEARTBEAT TELEMETRY TRACKING
// ============================================================================
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

#define MAX_TRACKED_DEVICES 16
DeviceTelemetry trackedDevices[MAX_TRACKED_DEVICES];
int trackedDeviceCount = 0;


void updateDynamicDeviceList(String deviceId, String ip) {
    deviceId.trim();
    ip.trim();
    if (ip.length() < 7 || ip.indexOf('.') == -1 || ip == "127.0.0.1" || ip == "0.0.0.0") return;
    String newIps = "";
    bool found = false;
    bool changed = false;
    
    int startIdx = 0;
    while (startIdx < androidIps.length()) {
        int comma = androidIps.indexOf(',', startIdx);
        if (comma == -1) comma = androidIps.length();
        String entry = androidIps.substring(startIdx, comma);
        entry.trim();
        if (entry.length() > 0) {
            DeviceConfig cfg;
            if (parseDeviceEntry(entry, cfg)) {
                bool isMatch = false;
                if (deviceId.length() > 0 && cfg.id.length() > 0 && cfg.id == deviceId) {
                    isMatch = true;
                } else if (cfg.ip == ip) {
                    isMatch = true;
                }

                if (isMatch) {
                    found = true;
                    if (cfg.id != deviceId || cfg.ip != ip) {
                        if (deviceId.length() > 0) cfg.id = deviceId;
                        cfg.ip = ip;
                        changed = true;
                    }
                }
                if (newIps.length() > 0) newIps += ",";
                newIps += cfg.id + "|" + cfg.ip + "|" + cfg.name;
            } else {
                // Purge invalid/corrupted entry
                changed = true;
            }
        }
        startIdx = comma + 1;
    }
    
    if (!found) {
        // Automatically assign incoming terminal to the first open seat slot (uncredited until activated)
        for (int i = 0; i < maxLicensedSlots; i++) {
            if (licenseSlots[i].deviceId.length() == 0) {
                licenseSlots[i].deviceId = deviceId;
                licenseSlots[i].ip = ip;
                if (licenseSlots[i].name.length() == 0) {
                    licenseSlots[i].name = "PisoPhone " + String(i + 1);
                }
                licenseSlots[i].expiresAt = 0;
                licenseSlots[i].active = true;
                found = true;
                changed = true;
                Serial.printf("[+] Auto-assigned incoming terminal %s (%s) to open Seat Slot #%d (Uncredited - Requires Activation)\n", deviceId.c_str(), ip.c_str(), i + 1);
                break;
            }
        }
    } else {
        // Update slot IP
        for (int i = 0; i < maxLicensedSlots; i++) {
            if (licenseSlots[i].deviceId == deviceId) {
                licenseSlots[i].ip = ip;
                break;
            }
        }
    }
    
    if (changed) {
        saveSlotLicenses();
    }
}

String getDeviceNameByIpOrId(String reqIp, String devId = "") {
    int startIdx = 0;
    int devNum = 1;
    while (startIdx < androidIps.length()) {
        int comma = androidIps.indexOf(',', startIdx);
        if (comma == -1) comma = androidIps.length();
        String entry = androidIps.substring(startIdx, comma);
        entry.trim();
        if (entry.length() > 0) {
            DeviceConfig cfg;
            if (parseDeviceEntry(entry, cfg)) {
                if ((devId.length() > 0 && cfg.id == devId) || (reqIp.length() > 0 && cfg.ip == reqIp)) {
                    return "PisoPhone " + String(devNum);
                }
            }
            devNum++;
        }
        startIdx = comma + 1;
    }
    return "PisoPhone 1";
}

bool checkReplayProtection(String deviceId, unsigned long long newTs) {
    for (int i = 0; i < trackedDeviceCount; i++) {
        if (trackedDevices[i].deviceId == deviceId) {
            // Allow sliding window or resync to prevent permanent lockout after phone reboot or NTP sync
            if (trackedDevices[i].lastNonceTs > 0 && newTs + 300000ULL < trackedDevices[i].lastNonceTs) {
                if (millis() - trackedDevices[i].lastSeenMs < 30000) {
                    return false;
                }
            }
            return true;
        }
    }
    return true;
}

bool verifyTelemetryAuth(String deviceId, String tsStr, String sig) {
    if (deviceId.length() == 0) return false;
    if (sharedSecret.length() == 0) {
        // Open/factory mode: shared secret has not yet been provisioned
        return true;
    }
    String expectedSig = calculateHMAC(deviceId + ":" + tsStr, sharedSecret);
    if (!sig.equalsIgnoreCase(expectedSig)) {
        return false;
    }
    unsigned long long ts = strtoull(tsStr.c_str(), NULL, 10);
    return checkReplayProtection(deviceId, ts);
}

void recordDeviceNonce(String deviceId, unsigned long long ts) {
    if (deviceId.length() == 0 || ts == 0) return;
    for (int i = 0; i < trackedDeviceCount; i++) {
        if (trackedDevices[i].deviceId == deviceId) {
            trackedDevices[i].lastNonceTs = ts;
            return;
        }
    }
    if (trackedDeviceCount < MAX_TRACKED_DEVICES) {
        trackedDevices[trackedDeviceCount].deviceId = deviceId;
        trackedDevices[trackedDeviceCount].lastKnownIp = "";
        trackedDevices[trackedDeviceCount].timeRemainingSeconds = 0;
        trackedDevices[trackedDeviceCount].state = 0;
        trackedDevices[trackedDeviceCount].batteryLevel = 100;
        trackedDevices[trackedDeviceCount].isCharging = false;
        trackedDevices[trackedDeviceCount].lastSeenMs = millis();
        trackedDevices[trackedDeviceCount].lastNonceTs = ts;
        trackedDeviceCount++;
    }
}

void updateDeviceTelemetry(String deviceId, String ip, int timeRemaining, int state, int battery = 100, bool charging = false, unsigned long long ts = 0) {
    ip.trim();
    if (ip == "127.0.0.1") ip = "";
    if (ip.length() > 0) {
        updateDynamicDeviceList(deviceId, ip);
    }
    
    int validBattery = (battery >= 0 && battery <= 100) ? battery : -1;
    
    for (int i = 0; i < trackedDeviceCount; i++) {
        bool match = false;
        if (deviceId.length() > 0 && trackedDevices[i].deviceId == deviceId) {
            match = true;
        } else if (ip.length() > 0 && trackedDevices[i].lastKnownIp == ip) {
            match = true;
        }
        
        if (match) {
            if (deviceId.length() > 0) trackedDevices[i].deviceId = deviceId;
            if (ip.length() > 0) trackedDevices[i].lastKnownIp = ip;
            trackedDevices[i].timeRemainingSeconds = timeRemaining;
            trackedDevices[i].state = state;
            if (validBattery >= 0) {
                trackedDevices[i].batteryLevel = validBattery;
            }
            trackedDevices[i].isCharging = charging;
            trackedDevices[i].lastSeenMs = millis();
            if (ts > 0) trackedDevices[i].lastNonceTs = ts;
            return;
        }
    }
    if (trackedDeviceCount < MAX_TRACKED_DEVICES) {
        trackedDevices[trackedDeviceCount].deviceId = deviceId;
        trackedDevices[trackedDeviceCount].lastKnownIp = ip;
        trackedDevices[trackedDeviceCount].timeRemainingSeconds = timeRemaining;
        trackedDevices[trackedDeviceCount].state = state;
        trackedDevices[trackedDeviceCount].batteryLevel = (validBattery >= 0) ? validBattery : 100;
        trackedDevices[trackedDeviceCount].isCharging = charging;
        trackedDevices[trackedDeviceCount].lastSeenMs = millis();
        trackedDevices[trackedDeviceCount].lastNonceTs = ts;
        trackedDeviceCount++;
    }
}

int getTrackedTimeRemaining(String ip, unsigned long maxAgeMs = 15000, String devId = "") {
    for (int i = 0; i < trackedDeviceCount; i++) {
        bool match = (trackedDevices[i].lastKnownIp == ip || trackedDevices[i].deviceId == ip);
        if (!match && devId.length() > 0 && trackedDevices[i].deviceId == devId) match = true;
        if (match) {
            if (millis() - trackedDevices[i].lastSeenMs <= maxAgeMs) {
                unsigned long elapsedSec = (millis() - trackedDevices[i].lastSeenMs) / 1000;
                int remaining = trackedDevices[i].timeRemainingSeconds - (int)elapsedSec;
                return (remaining > 0) ? remaining : 0;
            }
        }
    }
    return -1;
}

int getTrackedBatteryLevel(String ip, String devId = "") {
    for (int i = 0; i < trackedDeviceCount; i++) {
        bool match = (trackedDevices[i].lastKnownIp == ip || trackedDevices[i].deviceId == ip);
        if (!match && devId.length() > 0 && trackedDevices[i].deviceId == devId) match = true;
        if (match) {
            return trackedDevices[i].batteryLevel;
        }
    }
    return 100;
}

bool getTrackedChargingState(String ip, String devId = "") {
    for (int i = 0; i < trackedDeviceCount; i++) {
        bool match = (trackedDevices[i].lastKnownIp == ip || trackedDevices[i].deviceId == ip);
        if (!match && devId.length() > 0 && trackedDevices[i].deviceId == devId) match = true;
        if (match) {
            return trackedDevices[i].isCharging;
        }
    }
    return false;
}

String getFirstKnownIp() {
    int comma = androidIps.indexOf(',');
    String entry = (comma != -1) ? androidIps.substring(0, comma) : androidIps;
    DeviceConfig cfg;
    if (parseDeviceEntry(entry, cfg)) return cfg.ip;
    return entry;
}

String renderDeviceOptions(String selectedIp) {
    String opts = "";
    int startIdx = 0, devNum = 1;
    uint64_t currentMs = getCurrentMasterTimeMs();
    while (startIdx < androidIps.length()) {
        int comma = androidIps.indexOf(',', startIdx);
        if (comma == -1) comma = androidIps.length();
        String entry = androidIps.substring(startIdx, comma);
        entry.trim();
        if (entry.length() > 0) {
            DeviceConfig cfg;
            if (parseDeviceEntry(entry, cfg)) {
                String name = "PisoPhone " + String(devNum);
                String sel = (cfg.ip == selectedIp) ? " selected" : "";
                int slotIdx = findSlotIndexForDevice(cfg.id, cfg.ip);
                int daysLeft = -1;
                int expStatus = getSlotExpirationStatus(slotIdx, currentMs, daysLeft);
                bool isExpired = (expStatus == 2);
                String expAttr = isExpired ? " data-expired=\"true\"" : " data-expired=\"false\"";
                String badge = isExpired ? " [🔴 EXPIRED]" : "";
                opts += "<option value=\"" + cfg.ip + "\"" + sel + expAttr + ">" + name + " (" + cfg.ip + ")" + badge + "</option>";
                devNum++;
            }
        }
        startIdx = comma + 1;
    }
    return opts;
}


String renderDeviceIpInputs() {
    String html = "<div id=\"dev_ip_container\" style=\"background-color: var(--sub-bg); border: 1px solid var(--border); border-radius: 8px; padding: 12px; margin-top: 6px;\">";
    int startIdx = 0, devNum = 1;
    bool hasDevices = false;
    while (startIdx < androidIps.length()) {
        int comma = androidIps.indexOf(',', startIdx);
        if (comma == -1) comma = androidIps.length();
        String entry = androidIps.substring(startIdx, comma);
        entry.trim();
        if (entry.length() > 0) {
            DeviceConfig cfg;
            if (parseDeviceEntry(entry, cfg)) {
                hasDevices = true;
                html += "<div class=\"dev-ip-row\" style=\"display: block; padding: 10px; border-bottom: 1px solid var(--border);\">";
                html += "<div style=\"display: flex; align-items: center; gap: 8px;\">";
                html += "<span class=\"dev-label\" style=\"min-width: 95px; font-size: 13px; font-weight: 700;\">PisoPhone " + String(devNum) + ":</span>";
                html += "<input type=\"hidden\" class=\"dev-id-field\" value=\"" + cfg.id + "\">";
                html += "<input type=\"text\" class=\"dev-ip-field\" value=\"" + cfg.ip + "\" placeholder=\"192.168.1.X\" style=\"flex: 1; min-width: 140px;\" readonly title=\"IP dynamically bound to MAC/Device ID\">";
                html += "<button type=\"button\" class=\"remove-btn\" onclick=\"this.closest('.dev-ip-row').remove(); updateDeviceLabels();\" title=\"Remove Device\">&times;</button>";
                html += "</div></div>";
                devNum++;
            }
        }
        startIdx = comma + 1;
    }
    String noDevDisplay = hasDevices ? "none" : "block";
    html += "<div id=\"no_dev_msg\" style=\"display: " + noDevDisplay + "; color: var(--text-muted); font-size: 13px; text-align: center; padding: 14px 8px;\">No devices registered. Connected Android terminals will appear automatically, or you can add IP manually below.</div>";
    html += "</div>";
    html += "<div style=\"display: flex; gap: 8px; margin-top: 8px;\">";
    html += "<button type=\"button\" class=\"btn btn-outline\" style=\"font-size: 12px; padding: 6px 12px;\" onclick=\"addDeviceIpRow()\">+ Add IP Manually</button>";
    html += "<button type=\"button\" class=\"btn btn-outline\" style=\"font-size: 12px; padding: 6px 12px; color: var(--danger); border-color: var(--danger);\" onclick=\"clearAllDevices()\">🗑️ Clear All Devices</button>";
    html += "</div>";
    html += "<input type=\"hidden\" id=\"ips_hidden\" name=\"ips\" value=\"" + androidIps + "\">";
    html += "<script>";
    html += "window.updateDeviceLabels = function() {";
    html += "  const rows = document.querySelectorAll('.dev-ip-row');";
    html += "  rows.forEach((row, idx) => {";
    html += "    const label = row.querySelector('.dev-label'); if (label) label.textContent = 'PisoPhone ' + (idx + 1) + ':';";
    html += "  });";
    html += "  const msg = document.getElementById('no_dev_msg');";
    html += "  if (msg) msg.style.display = (rows.length === 0) ? 'block' : 'none';";
    html += "};";
    html += "window.clearAllDevices = function() {";
    html += "  if (confirm('Remove all registered devices? Click Save after clearing.')) {";
    html += "    document.querySelectorAll('.dev-ip-row').forEach(r => r.remove());";
    html += "    updateDeviceLabels();";
    html += "  }";
    html += "};";
    html += "window.addDeviceIpRow = function() {";
    html += "  const container = document.getElementById('dev_ip_container');";
    html += "  const devNum = container.querySelectorAll('.dev-ip-row').length + 1;";
    html += "  const div = document.createElement('div');";
    html += "  div.className = 'dev-ip-row'; div.style.display = 'block'; div.style.padding = '10px'; div.style.borderBottom = '1px solid var(--border)';";
    html += "  div.innerHTML = '<div style=\"display: flex; align-items: center; gap: 8px;\"><span class=\"dev-label\" style=\"min-width: 95px; font-size: 13px; font-weight: 700;\">PisoPhone ' + devNum + ':</span>' +";
    html += "                  '<input type=\"hidden\" class=\"dev-id-field\" value=\"\">' +";
    html += "                  '<input type=\"text\" class=\"dev-ip-field\" value=\"\" placeholder=\"192.168.1.X\" style=\"flex: 1; min-width: 140px;\">' +";
    html += "                  '<button type=\"button\" class=\"remove-btn\" onclick=\"this.closest(\\\'.dev-ip-row\\\').remove(); updateDeviceLabels();\" title=\"Remove Device\">&times;</button></div>';";
    html += "  container.appendChild(div);";
    html += "  updateDeviceLabels();";
    html += "};";
    html += "</script>";
    return html;
}

void sendAddTime(int minutes, String targetIp, String txId = "") {
    if (androidIps.length() == 0) return;
    int startIdx = 0;
    uint64_t currentMs = getCurrentMasterTimeMs();
    while (startIdx < androidIps.length()) {
        int comma = androidIps.indexOf(',', startIdx);
        if (comma == -1) comma = androidIps.length();
        String entry = androidIps.substring(startIdx, comma);
        entry.trim();
        if (entry.length() > 0) {
            DeviceConfig cfg;
            if (parseDeviceEntry(entry, cfg)) {
                if (targetIp == "ALL" || targetIp == cfg.ip) {
                    int slotIdx = findSlotIndexForDevice(cfg.id, cfg.ip);
                    int daysLeft = -1;
                    int expStatus = getSlotExpirationStatus(slotIdx, currentMs, daysLeft);
                    if (expStatus == 2) {
                        Serial.printf("[-] sendAddTime skipped for %s (Slot #%d): Device Expired / Uncredited\n",
                            cfg.ip.c_str(), (slotIdx >= 0) ? licenseSlots[slotIdx].slotNum : 0);
                    } else {
                        String params = "minutes=" + String(minutes);
                        if (txId.length() > 0) params += "&tx_id=" + txId;
                        sendAuthenticated(cfg.ip, targetPort, "/add_time", "/challenge", params, 1000);
                    }
                }
            }
        }
        startIdx = comma + 1;
    }
}

String renderLicenseSlotsHtml() {
    uint64_t currentMs = getCurrentMasterTimeMs();
    String myIp = WiFi.localIP().toString();
    if (myIp == "0.0.0.0" || myIp.length() == 0) myIp = "192.168.4.1";

    int installedCount = 0;
    for (int i = 0; i < maxLicensedSlots; i++) {
        if (licenseSlots[i].deviceId.length() > 0) {
            installedCount++;
        }
    }

    String html = "<div class=\"master-vault-card\" style=\"background: var(--card-bg); border: 1px solid var(--border); border-radius: var(--radius-lg); padding: 20px 24px; box-shadow: var(--card-shadow); margin-bottom: 16px;\">";
    
    // Top Row: Title, Subtitle & Professional Buy Button
    html += "<div style=\"display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 16px;\">";
    html += "<div style=\"display: flex; align-items: center; gap: 12px;\">";
    html += "<div style=\"background: rgba(16, 185, 129, 0.12); color: var(--primary); width: 42px; height: 42px; border-radius: 12px; display: flex; align-items: center; justify-content: center; font-size: 20px;\">💳</div>";
    html += "<div>";
    html += "<div style=\"font-size: 15px; font-weight: 800; color: var(--text-main); letter-spacing: 0.3px; text-transform: uppercase;\">Master Credit Vault</div>";
    html += "<div style=\"font-size: 11px; color: var(--text-muted); margin-top: 2px;\">1 Credit = 1 Paired Terminal Seat • Manual Operator Allocation</div>";
    html += "</div>";
    html += "</div>";

    html += "<a href=\"https://pisophone.pages.dev/purchase.html?esp32_ip=" + myIp + "\" target=\"_blank\" style=\"font-size: 12px; font-weight: 700; padding: 8px 16px; background: linear-gradient(135deg, #10b981 0%, #059669 100%); color: #ffffff; border-radius: 10px; text-decoration: none; display: inline-flex; align-items: center; gap: 6px; box-shadow: 0 4px 12px rgba(16, 185, 129, 0.25); transition: transform 0.15s ease;\">";
    html += "<span style=\"font-size: 14px;\">➕</span> Buy Terminal Credits</a>";
    html += "</div>";

    // Middle Row: Clearly Visible Displays of Total Credit Types
    html += "<div style=\"display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px; margin-top: 18px; padding-top: 16px; border-top: 1px solid var(--border);\">";
    
    html += "<div style=\"background: var(--input-bg); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 12px 14px;\">";
    html += "<div style=\"font-size: 10px; font-weight: 700; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.5px;\">📅 1-Month (₱50)</div>";
    html += "<div style=\"font-size: 18px; font-weight: 800; color: var(--primary); margin-top: 2px;\">" + String(monthlyCredits) + " <span style=\"font-size: 11px; font-weight: 600; color: var(--text-muted);\">Credits</span></div>";
    html += "</div>";

    html += "<div style=\"background: var(--input-bg); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 12px 14px;\">";
    html += "<div style=\"font-size: 10px; font-weight: 700; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.5px;\">👑 1-Year (₱500)</div>";
    html += "<div style=\"font-size: 18px; font-weight: 800; color: #3b82f6; margin-top: 2px;\">" + String(annualCredits) + " <span style=\"font-size: 11px; font-weight: 600; color: var(--text-muted);\">Credits</span></div>";
    html += "</div>";

    html += "<div style=\"background: var(--input-bg); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 12px 14px;\">";
    html += "<div style=\"font-size: 10px; font-weight: 700; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.5px;\">⚡ 2-Min Test</div>";
    html += "<div style=\"font-size: 18px; font-weight: 800; color: #f59e0b; margin-top: 2px;\">" + String(testCredits) + " <span style=\"font-size: 11px; font-weight: 600; color: var(--text-muted);\">Credits</span></div>";
    html += "</div>";

    html += "</div>";

    // Dropdown Trigger for ONLY Installed Devices
    html += "<div style=\"margin-top: 16px;\">";
    html += "<button type=\"button\" onclick=\"toggleInstalledDevicesDropdown()\" style=\"width: 100%; background: var(--input-bg); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 12px 16px; display: flex; align-items: center; justify-content: space-between; cursor: pointer; color: var(--text-main); font-family: inherit; font-size: 13px; font-weight: 700; transition: background 0.15s ease;\">";
    html += "<div style=\"display: flex; align-items: center; gap: 8px;\">";
    html += "<span>📱 Installed Devices Under This Hardware</span>";
    html += "<span style=\"font-size: 11px; font-weight: 800; background: " + String(installedCount > 0 ? "rgba(16, 185, 129, 0.15)" : "rgba(100, 116, 139, 0.15)") + "; color: " + String(installedCount > 0 ? "var(--primary)" : "var(--text-muted)") + "; padding: 2px 8px; border-radius: 12px;\">" + String(installedCount) + " Devices</span>";
    html += "</div>";
    html += "<span id=\"vault-dropdown-arrow\" style=\"font-size: 12px; color: var(--text-muted); transition: transform 0.2s ease;\">▼</span>";
    html += "</button>";

    // Dropdown Content (Contains ONLY Installed Devices)
    html += "<div id=\"installed-devices-dropdown-content\" style=\"display: none; margin-top: 12px; flex-direction: column; gap: 10px;\">";

    if (installedCount == 0) {
        html += "<div style=\"background: var(--input-bg); border: 1px dashed var(--border); border-radius: var(--radius-md); padding: 20px; text-align: center; color: var(--text-muted); font-size: 12px;\">";
        html += "<div style=\"font-size: 24px; margin-bottom: 6px;\">📱</div>";
        html += "<div style=\"font-weight: 700; color: var(--text-main);\">No Installed Devices</div>";
        html += "<div style=\"margin-top: 4px;\">No terminal devices are currently paired under this ESP32 hardware. Connect an Android terminal via WebADB or Wi-Fi to pair a slot seat.</div>";
        html += "</div>";
    } else {
        for (int i = 0; i < maxLicensedSlots; i++) {
            if (licenseSlots[i].deviceId.length() == 0) continue; // FILTER: ONLY INSTALLED DEVICES!

            int sNum = licenseSlots[i].slotNum;
            String devId = licenseSlots[i].deviceId;
            String ip = licenseSlots[i].ip;
            String name = licenseSlots[i].name.length() > 0 ? licenseSlots[i].name : ("PisoPhone Slot #" + String(sNum));
            int daysLeft = -1;
            int expStatus = getSlotExpirationStatus(i, currentMs, daysLeft);

            String borderCol = "var(--border)";
            String statusBadge = "";
            if (expStatus == 2) {
                borderCol = "var(--danger)";
                statusBadge = "<span style=\"font-size: 10px; font-weight: 800; background: rgba(239, 68, 68, 0.15); color: var(--danger); border: 1px solid rgba(239, 68, 68, 0.3); padding: 3px 8px; border-radius: 6px;\">🔴 EXPIRED / UNCREDITED</span>";
            } else if (expStatus == 1) {
                borderCol = "#f59e0b";
                statusBadge = "<span style=\"font-size: 10px; font-weight: 800; background: rgba(245, 158, 11, 0.15); color: #f59e0b; border: 1px solid rgba(245, 158, 11, 0.3); padding: 3px 8px; border-radius: 6px;\">⚠️ EXPIRING SOON</span>";
            } else {
                borderCol = "var(--primary)";
                statusBadge = "<span style=\"font-size: 10px; font-weight: 800; background: rgba(16, 185, 129, 0.15); color: var(--primary); border: 1px solid rgba(16, 185, 129, 0.3); padding: 3px 8px; border-radius: 6px;\">🟢 ACTIVE</span>";
            }

            String expInfo = "No Credit";
            if (licenseSlots[i].expiresAt > 0) {
                if (licenseSlots[i].expiresAt <= currentMs) {
                    expInfo = "Expired";
                } else {
                    uint64_t diffMs = licenseSlots[i].expiresAt - currentMs;
                    if (diffMs > 86400000ULL) {
                        expInfo = String((int)(diffMs / 86400000ULL)) + " day(s) left";
                    } else {
                        expInfo = String((int)(diffMs / 60000ULL)) + " min(s) left";
                    }
                }
            }

            html += "<div class=\"slot-row\" style=\"background: var(--input-bg); border: 1px solid var(--border); border-left: 4px solid " + borderCol + "; border-radius: var(--radius-md); padding: 14px 18px; display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap;\">";
            
            html += "<div style=\"display: flex; align-items: center; gap: 12px;\">";
            html += "<span style=\"font-size: 11px; font-weight: 800; background: rgba(16, 185, 129, 0.15); color: var(--primary); border: 1px solid rgba(16, 185, 129, 0.3); padding: 4px 8px; border-radius: 6px; font-family: monospace;\">Slot #" + String(sNum) + "</span>";
            html += "<div>";
            html += "<div style=\"font-size: 14px; font-weight: 700; color: var(--text-main); display: flex; align-items: center; gap: 8px;\">" + name + " " + statusBadge + "</div>";
            html += "<div style=\"font-size: 11px; color: var(--text-muted); font-family: monospace; margin-top: 2px;\">IP: " + (ip.length() > 0 ? ip : "Offline / Unbound") + " • HW: <b>" + devId + "</b> • Expires: <b>" + expInfo + "</b></div>";
            html += "</div>";
            html += "</div>";

            // Unified Credit Allocators
            html += "<div style=\"display: flex; gap: 8px; align-items: center; flex-wrap: wrap;\">";
            html += "<button type=\"button\" class=\"btn btn-outline btn-sm\" style=\"font-size: 11px; font-weight: 700; padding: 6px 10px; border-color: rgba(16, 185, 129, 0.4); color: var(--primary);\" onclick=\"allocateSlotCredit(" + String(sNum) + ", 'month')\">+30d (Month)</button>";
            html += "<button type=\"button\" class=\"btn btn-outline btn-sm\" style=\"font-size: 11px; font-weight: 700; padding: 6px 10px; border-color: rgba(59, 130, 246, 0.4); color: #3b82f6;\" onclick=\"allocateSlotCredit(" + String(sNum) + ", 'year')\">+1y (Year)</button>";
            html += "<button type=\"button\" class=\"btn btn-outline btn-sm\" style=\"font-size: 11px; font-weight: 700; padding: 6px 10px; border-color: rgba(245, 158, 11, 0.4); color: #f59e0b;\" onclick=\"allocateSlotCredit(" + String(sNum) + ", 'test')\">+2m (Test)</button>";
            html += "<button type=\"button\" class=\"btn btn-outline btn-sm\" style=\"font-size: 11px; font-weight: 700; padding: 6px 10px; border-color: rgba(239, 68, 68, 0.4); color: var(--danger);\" onclick=\"unpairSlot(" + String(sNum) + ")\">🔓 Unpair</button>";
            html += "</div>";

            html += "</div>";
        }
    }

    html += "</div>";
    html += "</div>";
    html += "</div>";
    return html;
}

String renderSlotOptions() {
    String html = "";
    for (int i = 0; i < maxLicensedSlots; i++) {
        int sNum = licenseSlots[i].slotNum;
        String devId = licenseSlots[i].deviceId;
        String name = licenseSlots[i].name.length() > 0 ? licenseSlots[i].name : ("Slot #" + String(sNum));
        String status = (devId.length() > 0) ? " (Armed: " + devId + ")" : " (Available / Empty)";
        html += "<option value=\"" + String(sNum) + "\">Slot #" + String(sNum) + ": " + name + status + "</option>";
    }
    return html;
}

const char PORTAL_HTML_TEMPLATE[] PROGMEM = R"HTML(
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>HARDWARE Admin Console</title>
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap" rel="stylesheet">
    <script src="https://cdnjs.cloudflare.com/ajax/libs/qrious/4.0.2/qrious.min.js"></script>
    <style>
        :root {
            --bg: #F1F5F9;
            --card-bg: #FFFFFF;
            --input-bg: #F8FAFC;
            --text-main: #0F172A;
            --text-muted: #64748B;
            --primary: #059669;
            --primary-hover: #047857;
            --primary-glow: rgba(5, 150, 105, 0.15);
            --border: #E2E8F0;
            --border-focus: #10B981;
            --danger: #EF4444;
            --danger-hover: #DC2626;
            --warning: #F59E0B;
            --warning-hover: #D97706;
            --success: #10B981;
            --card-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.05), 0 2px 4px -1px rgba(0, 0, 0, 0.03);
            --radius-lg: 16px;
            --radius-md: 12px;
            --radius-sm: 8px;
            
            --status-good: #10B981;
            --status-good-bg: rgba(16, 185, 129, 0.1);
            --status-good-border: rgba(16, 185, 129, 0.2);
            --status-warning: #F59E0B;
            --status-warning-bg: rgba(245, 158, 11, 0.1);
            --status-warning-border: rgba(245, 158, 11, 0.2);
            --status-critical: #EF4444;
            --status-critical-bg: rgba(239, 68, 68, 0.1);
            --status-critical-border: rgba(239, 68, 68, 0.2);
        }
        [data-theme="dark"] {
            --bg: #060B14;
            --card-bg: #0F172A;
            --input-bg: #0B0F19;
            --text-main: #F8FAFC;
            --text-muted: #94A3B8;
            --primary: #10B981;
            --primary-hover: #34D399;
            --primary-glow: rgba(16, 185, 129, 0.2);
            --border: rgba(255, 255, 255, 0.08);
            --border-focus: #10B981;
            --danger: #EF4444;
            --danger-hover: #F87171;
            --warning: #F59E0B;
            --warning-hover: #FBBF24;
            --success: #10B981;
            --card-shadow: 0 10px 30px -10px rgba(0, 0, 0, 0.5);
            
            --status-good: #34D399;
            --status-good-bg: rgba(52, 211, 153, 0.1);
            --status-good-border: rgba(52, 211, 153, 0.2);
            --status-warning: #FBBF24;
            --status-warning-bg: rgba(251, 191, 36, 0.1);
            --status-warning-border: rgba(251, 191, 36, 0.2);
            --status-critical: #F87171;
            --status-critical-bg: rgba(248, 113, 113, 0.1);
            --status-critical-border: rgba(248, 113, 113, 0.2);
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
            background: var(--bg);
            color: var(--text-main);
            padding: 24px 16px;
            line-height: 1.6;
            transition: background-color 0.3s ease, color 0.3s ease;
            -webkit-font-smoothing: antialiased;
        }
        .app-container {
            max-width: 960px;
            margin: 0 auto;
            display: flex;
            flex-direction: column;
            gap: 24px;
        }
        .header-bar {
            display: flex;
            justify-content: space-between;
            align-items: center;
            background: var(--card-bg);
            padding: 16px 24px;
            border-radius: var(--radius-lg);
            box-shadow: var(--card-shadow);
            border: 1px solid var(--border);
            flex-wrap: wrap;
            gap: 16px;
        }
        .logo-container {
            display: flex;
            align-items: center;
            gap: 10px;
        }
        .logo-icon {
            color: var(--primary);
            filter: drop-shadow(0 0 4px var(--primary-glow));
            animation: pulse-glow 2s infinite alternate;
        }
        @keyframes pulse-glow {
            0% { filter: drop-shadow(0 0 2px var(--primary-glow)); }
            100% { filter: drop-shadow(0 0 8px var(--primary)); }
        }
        .logo-text {
            font-size: 20px;
            font-weight: 800;
            letter-spacing: -0.5px;
            text-transform: uppercase;
            display: flex;
            align-items: center;
        }
        .logo-piso {
            color: var(--text-main);
        }
        .logo-phone {
            color: var(--primary);
        }
        .badge-pill {
            background: var(--primary-glow);
            color: var(--primary);
            border: 1px solid var(--border-focus);
            padding: 3px 10px;
            border-radius: 999px;
            font-size: 11px;
            font-weight: 700;
            letter-spacing: 0.5px;
            text-transform: uppercase;
        }
        .btn {
            background: var(--primary);
            color: #ffffff;
            border: none;
            padding: 12px 20px;
            border-radius: var(--radius-md);
            font-weight: 600;
            cursor: pointer;
            transition: all 0.2s ease;
            font-size: 14px;
            display: inline-flex;
            align-items: center;
            justify-content: center;
            gap: 8px;
            text-decoration: none;
            min-height: 48px;
            box-shadow: 0 4px 12px var(--primary-glow);
        }
        .btn:hover {
            background: var(--primary-hover);
            transform: translateY(-1px);
            box-shadow: 0 6px 16px var(--primary-glow);
        }
        .btn:active {
            transform: translateY(1px);
        }
        .btn-sm {
            padding: 8px 16px;
            min-height: 38px;
            font-size: 13px;
            border-radius: var(--radius-sm);
        }
        .btn-danger {
            background: var(--danger);
            box-shadow: 0 4px 12px rgba(239, 68, 68, 0.15);
        }
        .btn-danger:hover {
            background: var(--danger-hover);
            box-shadow: 0 6px 16px rgba(239, 68, 68, 0.25);
        }
        .btn-warning {
            background: var(--warning);
            color: #0F172A;
            box-shadow: 0 4px 12px rgba(245, 158, 11, 0.15);
        }
        .btn-warning:hover {
            background: var(--warning-hover);
            box-shadow: 0 6px 16px rgba(245, 158, 11, 0.25);
        }
        .btn-outline {
            background: transparent;
            border: 1.5px solid var(--border);
            color: var(--text-main);
            box-shadow: none;
        }
        .btn-outline:hover {
            background: var(--input-bg);
            border-color: var(--text-muted);
        }
        .tabs {
            display: flex;
            background: var(--card-bg);
            border: 1px solid var(--border);
            padding: 6px;
            border-radius: var(--radius-md);
            gap: 4px;
            overflow-x: auto;
            box-shadow: var(--card-shadow);
        }
        .tab {
            flex: 1;
            padding: 10px 16px;
            border-radius: var(--radius-sm);
            font-weight: 600;
            font-size: 14px;
            color: var(--text-muted);
            cursor: pointer;
            transition: all 0.2s ease;
            text-align: center;
            white-space: nowrap;
            display: flex;
            align-items: center;
            justify-content: center;
            gap: 6px;
        }
        .tab:hover:not(.active) {
            background: var(--input-bg);
            color: var(--text-main);
        }
        .tab.active {
            background: var(--primary);
            color: #ffffff;
            box-shadow: 0 4px 12px var(--primary-glow);
        }
        .tab-content {
            display: none;
            animation: scaleIn 0.25s cubic-bezier(0.16, 1, 0.3, 1);
        }
        .tab-content.active {
            display: block;
        }
        @keyframes scaleIn {
            from { opacity: 0; transform: scale(0.98) translateY(4px); }
            to { opacity: 1; transform: scale(1) translateY(0); }
        }
        .grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
            gap: 20px;
        }
        .grid-full {
            grid-column: 1 / -1;
        }
        .card {
            background: var(--card-bg);
            border: 1px solid var(--border);
            border-radius: var(--radius-lg);
            padding: 24px;
            box-shadow: var(--card-shadow);
            display: flex;
            flex-direction: column;
            gap: 20px;
        }
        .card-header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            border-bottom: 1px solid var(--border);
            padding-bottom: 12px;
            margin-bottom: 4px;
        }
        .card-title {
            margin: 0;
            font-size: 16px;
            font-weight: 700;
            color: var(--text-main);
            display: flex;
            align-items: center;
            gap: 8px;
        }
        .form-group {
            display: flex;
            flex-direction: column;
            gap: 6px;
        }
        label {
            font-weight: 700;
            font-size: 11px;
            color: var(--text-muted);
            text-transform: uppercase;
            letter-spacing: 0.75px;
        }
        input[type=text], input[type=password], input[type=number], select {
            width: 100%;
            height: 48px;
            padding: 0 16px;
            border: 1.5px solid var(--border);
            border-radius: var(--radius-md);
            font-size: 15px;
            background: var(--input-bg);
            transition: all 0.2s ease;
            color: var(--text-main);
            font-family: inherit;
        }
        input:focus, select:focus {
            outline: none;
            border-color: var(--border-focus);
            background: var(--card-bg);
            box-shadow: 0 0 0 3px var(--primary-glow);
        }
        .hint {
            font-size: 12px;
            color: var(--text-muted);
            line-height: 1.5;
        }
        .danger-hint {
            color: var(--danger);
            font-weight: 500;
        }
        .status-badge {
            background: var(--primary-glow);
            color: var(--primary);
            border: 1px solid var(--border-focus);
            padding: 6px 12px;
            border-radius: 20px;
            font-size: 11px;
            font-weight: 700;
            letter-spacing: 0.5px;
            text-transform: uppercase;
        }
        .status-badge.accent {
            background: rgba(126, 34, 206, 0.1);
            color: #A78BFA;
            border: 1px solid rgba(126, 34, 206, 0.3);
        }
        [data-theme="light"] .status-badge.accent {
            background: #F3E8FF;
            color: #7E22CE;
            border: 1px solid #E9D5FF;
        }
        .device-grid {
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
            gap: 16px;
        }
        .device-list {
            display: flex;
            flex-direction: column;
            gap: 12px;
        }
        .device-row {
            background: var(--card-bg);
            border: 1px solid var(--border);
            border-radius: var(--radius-md);
            padding: 14px 18px;
            display: flex;
            align-items: center;
            justify-content: space-between;
            gap: 16px;
            box-shadow: var(--card-shadow);
            transition: all 0.2s ease;
        }
        .device-row:hover {
            border-color: var(--primary);
            box-shadow: 0 4px 16px -2px rgba(16, 185, 129, 0.15);
        }
        .device-row.empty {
            border: 1.5px dashed var(--border);
            background: var(--input-bg);
        }
        .device-row.offline {
            opacity: 0.7;
        }
        .device-row-identity {
            display: flex;
            align-items: center;
            gap: 14px;
            min-width: 220px;
        }
        .device-slot-badge {
            background: rgba(16, 185, 129, 0.15);
            color: var(--primary);
            font-size: 11px;
            font-weight: 800;
            padding: 5px 10px;
            border-radius: 8px;
            border: 1px solid rgba(16, 185, 129, 0.25);
            white-space: nowrap;
        }
        .device-row-info {
            display: flex;
            flex-direction: column;
        }
        .device-row-name {
            font-size: 15px;
            font-weight: 700;
            color: var(--text-main);
        }
        .device-row-sub {
            font-size: 12px;
            font-family: monospace;
            color: var(--text-muted);
        }
        .device-row-metrics {
            display: flex;
            align-items: center;
            gap: 20px;
            flex-wrap: wrap;
        }
        .device-row-timer {
            display: flex;
            align-items: center;
            gap: 8px;
            font-size: 15px;
            font-weight: 800;
            color: var(--text-main);
            font-variant-numeric: tabular-nums;
        }
        .device-row-battery {
            display: flex;
            align-items: center;
            gap: 8px;
            padding: 5px 10px;
            border-radius: 8px;
            background: var(--input-bg);
            border: 1px solid var(--border);
        }
        .device-row-actions {
            display: flex;
            align-items: center;
            gap: 8px;
            flex-shrink: 0;
        }
        .device-card {
            background: var(--card-bg);
            border: 1px solid var(--border);
            border-radius: 16px;
            padding: 20px;
            display: flex;
            flex-direction: column;
            gap: 16px;
            box-shadow: var(--card-shadow);
            transition: all 0.3s ease;
            position: relative;
            overflow: hidden;
        }
        .device-card:hover {
            transform: translateY(-2px);
            border-color: var(--primary);
            box-shadow: 0 8px 24px -4px rgba(16, 185, 129, 0.12);
        }
        .device-card.offline {
            opacity: 0.65;
        }
        .device-card-header {
            display: flex;
            justify-content: space-between;
            align-items: flex-start;
        }
        .device-identity {
            display: flex;
            flex-direction: column;
        }
        .device-name {
            font-size: 16px;
            font-weight: 700;
            color: var(--text-main);
            margin-bottom: 2px;
        }
        .device-ip {
            font-size: 12px;
            font-family: monospace;
            color: var(--text-muted);
        }
        .device-stats {
            text-align: right;
        }
        .device-timer {
            font-size: 20px;
            font-weight: 800;
            color: var(--text-main);
            font-variant-numeric: tabular-nums;
            line-height: 1.2;
            margin-bottom: 4px;
        }
        .device-badge {
            display: inline-block;
            padding: 4px 8px;
            border-radius: 6px;
            font-size: 11px;
            font-weight: 700;
            text-transform: uppercase;
        }
        .device-badge.active {
            background: rgba(16, 185, 129, 0.15);
            color: #10B981;
            border: 1px solid rgba(16, 185, 129, 0.3);
        }
        .device-badge.standby {
            background: rgba(148, 163, 184, 0.15);
            color: var(--text-muted);
            border: 1px solid rgba(148, 163, 184, 0.3);
        }
        .device-badge.offline {
            background: rgba(239, 68, 68, 0.15);
            color: #EF4444;
            border: 1px solid rgba(239, 68, 68, 0.3);
        }
        .battery-section {
            padding: 12px;
            border-radius: 12px;
        }
        .battery-info {
            display: flex;
            justify-content: space-between;
            align-items: center;
            font-size: 12px;
            font-weight: 700;
            margin-bottom: 6px;
        }
        .battery-bar-bg {
            height: 8px;
            background: var(--border);
            border-radius: 999px;
            overflow: hidden;
        }
        .battery-bar-fill {
            height: 100%;
            border-radius: 999px;
            transition: width 0.4s ease-in-out, background-color 0.3s ease;
        }
        .battery-section.status-good {
            background: var(--status-good-bg);
            border: 1px solid var(--status-good-border);
        }
        .battery-section.status-good .battery-label,
        .battery-section.status-good .battery-status-tag {
            color: var(--status-good);
        }
        .battery-section.status-good .battery-bar-fill {
            background: var(--status-good);
        }
        .battery-section.status-warning {
            background: var(--status-warning-bg);
            border: 1px solid var(--status-warning-border);
        }
        .battery-section.status-warning .battery-label,
        .battery-section.status-warning .battery-status-tag {
            color: var(--status-warning);
        }
        .battery-section.status-warning .battery-bar-fill {
            background: var(--status-warning);
        }
        .battery-section.status-critical {
            background: var(--status-critical-bg);
            border: 1px solid var(--status-critical-border);
        }
        .battery-section.status-critical .battery-label,
        .battery-section.status-critical .battery-status-tag {
            color: var(--status-critical);
        }
        .battery-section.status-critical .battery-bar-fill {
            background: var(--status-critical);
        }
        .alert-box {
            padding: 16px;
            border-radius: var(--radius-md);
            font-size: 14px;
            line-height: 1.5;
            margin-top: 16px;
            display: none;
        }
        .alert-box.info {
            background: var(--input-bg);
            border: 1px solid var(--border);
            color: var(--text-main);
        }
        .alert-box.success {
            background: var(--status-good-bg);
            border: 1px solid var(--status-good-border);
            color: var(--status-good);
        }
        .alert-box.error {
            background: var(--status-critical-bg);
            border: 1px solid var(--status-critical-border);
            color: var(--status-critical);
        }
        .dev-ip-row {
            display: flex;
            align-items: center;
            gap: 12px;
            background: var(--input-bg);
            padding: 12px 16px;
            border: 1px solid var(--border);
            border-radius: var(--radius-md);
            margin-bottom: 12px;
            flex-wrap: wrap;
        }
        .dev-ip-row input {
            flex: 1;
            min-width: 140px;
        }
        .remove-btn {
            background: transparent;
            border: none;
            color: var(--danger);
            font-size: 22px;
            font-weight: bold;
            cursor: pointer;
            width: 44px;
            height: 44px;
            display: inline-flex;
            align-items: center;
            justify-content: center;
            border-radius: var(--radius-sm);
            transition: all 0.2s;
        }
        .remove-btn:hover {
            background: rgba(239, 68, 68, 0.1);
        }
        @media (max-width: 640px) {
            body {
                padding: 10px 8px;
            }
            .app-container {
                gap: 14px;
            }
            .header-bar {
                flex-direction: column;
                align-items: stretch;
                gap: 12px;
                padding: 14px 16px;
            }
            .header-right {
                justify-content: space-between;
                width: 100%;
            }
            .tabs {
                display: flex;
                overflow-x: auto;
                -webkit-overflow-scrolling: touch;
                scrollbar-width: none;
                gap: 4px;
                padding: 4px;
            }
            .tabs::-webkit-scrollbar {
                display: none;
            }
            .tab {
                flex: 0 0 auto;
                padding: 8px 14px;
                font-size: 13px;
                white-space: nowrap;
            }
            .grid {
                grid-template-columns: 1fr !important;
                gap: 14px;
            }
            .card {
                padding: 16px;
            }
            .device-row {
                flex-direction: column;
                align-items: stretch;
                gap: 12px;
                padding: 14px;
            }
            .device-row-identity {
                width: 100%;
                min-width: unset;
                justify-content: flex-start;
            }
            .device-row-metrics {
                width: 100%;
                justify-content: space-between;
                gap: 8px;
            }
            .device-row-actions {
                width: 100%;
                display: grid;
                grid-template-columns: 1fr 1fr;
                gap: 8px;
            }
            .device-row-actions .btn {
                width: 100%;
                text-align: center;
                justify-content: center;
            }
            input, select, textarea {
                font-size: 16px !important;
            }
        }
    </style>
    <script>
        // Apply saved theme immediately to prevent flashing
        (function() {
            const savedTheme = localStorage.getItem('kiosk_theme') || 'light';
            document.documentElement.setAttribute('data-theme', savedTheme);
        })();

        window.toggleTheme = function() {
            const cur = document.documentElement.getAttribute('data-theme') || 'light';
            const next = cur === 'dark' ? 'light' : 'dark';
            document.documentElement.setAttribute('data-theme', next);
            localStorage.setItem('kiosk_theme', next);
            updateThemeButtonText();
        };

        window.toggleInstalledDevicesDropdown = function() {
            const content = document.getElementById('installed-devices-dropdown-content');
            const arrow = document.getElementById('vault-dropdown-arrow');
            if (content) {
                if (content.style.display === 'none' || content.style.display === '') {
                    content.style.display = 'flex';
                    if (arrow) arrow.textContent = '▲';
                } else {
                    content.style.display = 'none';
                    if (arrow) arrow.textContent = '▼';
                }
            }
        };

        function updateThemeButtonText() {
            const btn = document.getElementById('theme_toggle_btn');
            if (btn) {
                const cur = document.documentElement.getAttribute('data-theme') || 'light';
                btn.innerHTML = cur === 'dark' ? '☀️ Light Mode' : '🌙 Dark Mode';
            }
        }

        window.switchTab = function(tabId) {
            document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
            document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
            const tabBtn = document.querySelector(`[onclick="switchTab('${tabId}')"]`);
            if (tabBtn) tabBtn.classList.add('active');
            const tabEl = document.getElementById(tabId);
            if (tabEl) tabEl.classList.add('active');
            localStorage.setItem('activeTab', tabId);
        }
        document.addEventListener('DOMContentLoaded', () => {
            let savedTab = localStorage.getItem('activeTab') || 'tab-dashboard';
            const path = window.location.pathname.toLowerCase();
            const search = window.location.search.toLowerCase();
            const hash = window.location.hash.toLowerCase();
            if (path.includes('install') || path.includes('provision') || search.includes('install') || search.includes('provision') || hash.includes('install') || hash.includes('provision')) {
                savedTab = 'tab-install';
            }
            if(document.getElementById(savedTab)) switchTab(savedTab);
            updateThemeButtonText();
        });
        window.triggerAction = function(ip, action, btn) {
            let origText = '';
            if (btn) {
                origText = btn.innerHTML;
                btn.disabled = true;
                btn.innerHTML = '⏳ Locating...';
            }
            fetch('/trigger_android?ip=' + encodeURIComponent(ip) + '&action=' + encodeURIComponent(action))
                .then(res => { 
                    if (res.ok) {
                        if (btn) {
                            btn.innerHTML = '🔔 Signal Sent!';
                            setTimeout(() => { btn.disabled = false; btn.innerHTML = origText; }, 2500);
                        } else {
                            alert('📍 Locate signal (Sound, Vibrate & Flash) sent to ' + ip);
                        }
                    } else {
                        if (btn) {
                            btn.innerHTML = '❌ Unreachable';
                            setTimeout(() => { btn.disabled = false; btn.innerHTML = origText; }, 2500);
                        } else {
                            alert('Failed to send trigger to ' + ip);
                        }
                    }
                })
                .catch(err => {
                    if (btn) {
                        btn.innerHTML = '❌ Error';
                        setTimeout(() => { btn.disabled = false; btn.innerHTML = origText; }, 2500);
                    } else {
                        alert('Error: ' + err);
                    }
                });
        }
        window.triggerCoin = function() {
            fetch('/insert_coin', { method: 'POST', credentials: 'include' })
                .then(res => { if(res.ok) alert('✅ Simple beam coin drop (GPIO 4) simulated successfully!'); else alert('❌ Auth failed or error!'); })
                .catch(err => alert('Error: ' + err));
        }
        window.triggerUniversalCoin = function(pulses) {
            fetch('/insert_ucoin?pulses=' + pulses, { method: 'POST', credentials: 'include' })
                .then(res => { if(res.ok) alert('✅ Universal ' + pulses + ' PHP coin drop (' + pulses + ' pulses) simulated successfully!'); else alert('❌ Auth failed or error!'); })
                .catch(err => alert('Error: ' + err));
        }
    </script>
</head>
<body>
    <div class="app-container">
        <!-- Brand Header Bar -->
        <div class="header-bar">
            <div class="logo-container">
                <svg class="logo-icon" width="28" height="28" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="2.5" />
                    <path d="M13 7L8 13H12L11 17L16 11H12L13 7Z" fill="currentColor" />
                </svg>
                <h1 class="logo-text">
                    <span class="logo-piso">Piso</span><span class="logo-phone">Phone</span>
                </h1>
                <span class="badge-pill">Kiosk Admin</span>
                <div id="wifi_quality_pill" class="badge-pill" style="background: rgba(16, 185, 129, 0.15); color: #10B981; border: 1px solid rgba(16, 185, 129, 0.3); display: inline-flex; align-items: center; gap: 6px; font-weight: 700; padding: 4px 10px; border-radius: 20px;" title="ESP32 Real-Time Wi-Fi RSSI Signal Quality">
                    <span id="wifi_icon">📶</span>
                    <span id="wifi_signal_text">Wi-Fi: {WIFI_RSSI} dBm ({WIFI_QUALITY}%)</span>
                </div>
            </div>
            <div style="display: flex; align-items: center; gap: 10px; flex-wrap: wrap;">
                <button type="button" id="theme_toggle_btn" onclick="toggleTheme()" class="btn btn-outline btn-sm">🌙 Dark Mode</button>
                <span class="status-badge">🟢 ONLINE</span>
                <a href="/logout" onclick="return confirm('Log out?');" class="btn btn-outline btn-sm">🚪 Logout</a>
            </div>
        </div>

        <!-- Navigation Tabs -->
        <div class="tabs">
            <div class="tab active" onclick="switchTab('tab-dashboard')">📊 Dashboard</div>
            <div class="tab" onclick="switchTab('tab-settings')">🛠️ Settings</div>
            <div class="tab" onclick="switchTab('tab-tools')">⚡ Advanced Tools</div>
        </div>

        <!-- TAB 1: DASHBOARD -->
        <div id="tab-dashboard" class="tab-content active">
            <div class="grid">
                <!-- Master Credit Vault & Seat Slots Manager -->
                <div class="grid-full">
                    {DEVICE_SLOTS_MANAGER}
                </div>

                <!-- Live Devices List (Horizontal) -->
                <div class="card grid-full">
                    <div class="card-header">
                        <div style="display: flex; align-items: center; gap: 10px;">
                            <h3 class="card-title">📡 Live Device Status</h3>
                            <span class="badge" style="background: rgba(16, 185, 129, 0.15); color: var(--primary); font-weight: 700; padding: 3px 10px; border-radius: 12px; font-size: 11px;">
                                {MAX_SLOTS} Seats
                            </span>
                        </div>
                        <div style="display: flex; align-items: center; gap: 8px;">
                            <span class="status-badge" style="background: var(--status-good-bg); color: var(--status-good); border-color: var(--status-good-border);">LIVE SYNC</span>
                            <button type="button" class="btn btn-outline btn-sm" onclick="fetchDeviceStatus()">🔄 Refresh</button>
                        </div>
                    </div>
                    <div id="live_devices_container" class="device-list">
                        <div style="padding: 24px; text-align: center; color: var(--text-muted);">Loading devices...</div>
                    </div>
                </div>

                <!-- Vault Stats -->
                <div class="card">
                    <div class="card-header">
                        <h3 class="card-title">💰 Revenue Vault</h3>
                    </div>
                    <div style="background: var(--input-bg); padding: 24px; border-radius: var(--radius-lg); text-align: center; border: 1px solid var(--border); margin-bottom: 4px;">
                        <div style="font-size: 11px; font-weight: 700; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.75px; margin-bottom: 6px;">Total Coins (PHP)</div>
                        <div style="font-size: 36px; font-weight: 800; color: var(--primary);">₱{TOTAL_COINS}</div>
                    </div>
                    <div style="font-size: 13px; text-align: center; color: var(--text-muted); background: var(--bg); padding: 12px; border-radius: var(--radius-md); border: 1px solid var(--border); font-weight: 500;">
                        Session Coins: <b style="color: var(--text-main); font-weight: 700;">₱{SESSION_COINS}</b>
                    </div>
                    <div style="margin-top: 8px; display: flex; flex-direction: column; gap: 10px;">
                        <button type="button" class="btn" onclick="triggerCoin()">🪙 Simulate Simple Beam Coin (₱{PRICE})</button>
                        <div style="display: grid; grid-template-columns: repeat(4, 1fr); gap: 6px;">
                            <button type="button" class="btn btn-outline btn-sm" onclick="triggerUniversalCoin(1)">₱1</button>
                            <button type="button" class="btn btn-outline btn-sm" onclick="triggerUniversalCoin(5)">₱5</button>
                            <button type="button" class="btn btn-outline btn-sm" onclick="triggerUniversalCoin(10)">₱10</button>
                            <button type="button" class="btn btn-outline btn-sm" onclick="triggerUniversalCoin(20)">₱20</button>
                        </div>
                    </div>
                </div>

                <!-- Quick Add Time -->
                <form id="quick_adjust_form" action="/add_time" method="POST" class="card" onsubmit="return validateQuickAdjust(event)">
                    <div class="card-header">
                        <h3 class="card-title">⏱️ Quick Adjust Time</h3>
                    </div>
                    {QUICK_TIME_ALERT}
                    <div class="form-group">
                        <label>Target Device</label>
                        <select id="quick_adjust_target" name="target_ip" onchange="checkQuickAdjustExpired()">
                            <option value="ALL">All Devices (Broadcast)</option>
                            {DEVICE_OPTIONS}
                        </select>
                        <div id="quick_adjust_warn" style="display: none; margin-top: 6px; padding: 6px 10px; background: rgba(239, 68, 68, 0.15); color: var(--danger); border: 1px solid rgba(239, 68, 68, 0.3); border-radius: 6px; font-size: 11px; font-weight: 700;">
                            🚫 Selected device is EXPIRED. Manual time adjustment is blocked until credits are allocated.
                        </div>
                    </div>
                    <div class="form-group">
                        <label>Minutes</label>
                        <input type="number" name="add_minutes" value="60" min="1">
                    </div>
                    <div style="display: flex; gap: 12px; margin-top: 12px;">
                        <button type="submit" name="action" value="add" class="btn btn-warning" style="flex: 1;" onclick="return validateQuickAdjust(event, 'add')">+ Add</button>
                        <button type="submit" name="action" value="subtract" class="btn btn-danger" style="flex: 1;" onclick="return validateQuickAdjust(event, 'subtract')">- Subtract</button>
                    </div>
                </form>
            </div>
        </div>

        <!-- TAB 2: SETTINGS -->
        <div id="tab-settings" class="tab-content">
            <form action="/save" method="POST" onsubmit="
                event.preventDefault();
                const formData = new FormData(this);
                fetch('/save', { method: 'POST', body: new URLSearchParams(formData) })
                    .then(res => { if (res.ok) alert('✅ Configuration saved & pushed live!'); else alert('❌ Failed to save configuration.'); })
                    .catch(err => alert('Error: ' + err));
            ">
                <div class="grid">
                    <!-- Network -->
                    <div class="card">
                        <h3 class="card-title">📡 Wi-Fi & Network</h3>
                        <div class="form-group" style="background: var(--input-bg); padding: 14px; border-radius: var(--radius-md); border: 1px solid var(--border); margin-bottom: 14px;">
                            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px;">
                                <span style="font-size: 11px; font-weight: 700; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.5px;">Real-Time Wi-Fi Quality (RSSI)</span>
                                <span id="wifi_status_badge" class="badge" style="background: var(--status-good-bg); color: var(--status-good); font-weight: 700; padding: 2px 8px; border-radius: 10px; font-size: 11px;">Live</span>
                            </div>
                            <div style="display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 6px;">
                                <span id="wifi_rssi_display" style="font-size: 20px; font-weight: 800; color: var(--text-main); font-family: monospace;">{WIFI_RSSI} dBm</span>
                                <span id="wifi_quality_pct" style="font-size: 13px; font-weight: 700; color: var(--primary);">{WIFI_QUALITY}% Quality</span>
                            </div>
                            <div style="background: var(--bg); border: 1px solid var(--border); border-radius: 10px; height: 8px; overflow: hidden; margin-bottom: 6px;">
                                <div id="wifi_meter_fill" style="background: var(--primary); height: 100%; width: {WIFI_QUALITY}%; transition: width 0.4s ease, background-color 0.4s ease;"></div>
                            </div>
                            <div style="display: flex; justify-content: space-between; font-size: 11px; color: var(--text-muted); margin-top: 4px;">
                                <span>SSID: <b id="wifi_ssid_display" style="color: var(--text-main);">{WIFI_SSID}</b></span>
                                <span>IP: <b id="wifi_ip_display" style="color: var(--text-main);">{IP_ADDRESS}</b></span>
                            </div>
                        </div>
                        <div class="form-group">
                            <label>SSID</label>
                            <input type="text" name="wifi_ssid" value="{WIFI_SSID}">
                        </div>
                        <div class="form-group">
                            <label>Password</label>
                            <input type="password" name="wifi_pass" value="{WIFI_PASS}">
                        </div>
                        <div class="form-group">
                            <label>Android App Port</label>
                            <input type="number" name="port" value="{PORT}">
                            <div class="hint">Default is 8080.</div>
                        </div>
                        <!-- MAC card removed -->
                    </div>

                    <!-- Pricing & Rules -->
                    <div class="card">
                        <div class="card-header" style="margin-bottom: 0;">
                            <h3 class="card-title">🪙 Simple Beam Pricing</h3>
                            <span class="status-badge" style="background: var(--status-warning-bg); color: var(--status-warning); border-color: var(--status-warning-border);">GPIO 4</span>
                        </div>
                        <div class="hint" style="background: var(--bg); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 12px; font-size: 12px; line-height: 1.4;">
                            ⚠️ These pricing settings apply <b>exclusively to the Simple optical sensor</b> (GPIO 4). The multi-coin acceptor dynamically calculates rate from coin pulses.
                        </div>
                        <div class="form-group">
                            <label>Coin Price (PHP)</label>
                            <input type="number" step="0.01" name="price" value="{PRICE}">
                        </div>
                        <div class="form-group">
                            <label>Minutes granted per Coin Drop</label>
                            <input type="number" name="minutes" value="{MINUTES}">
                        </div>
                        <div class="form-group">
                            <label>Debounce lock (ms)</label>
                            <input type="number" name="debounce" value="{DEBOUNCE}">
                        </div>
                    </div>

                    <!-- Advanced Security & Pins -->
                    <div class="card">
                        <h3 class="card-title">🔒 Security</h3>
                        <div class="form-group">
                            <label>Admin Web Password</label>
                            <input type="password" name="admin_pw" value="{ADMIN_PASSWORD}">
                        </div>
                    </div>

                    <div class="card">
                        <h3 class="card-title">🔌 Hardware GPIO Pins</h3>
                        <div class="form-group">
                            <label>Simple Beam Sensor GPIO</label>
                            <input type="number" name="coin_pin" value="{COIN_PIN}">
                            <div class="hint">GPIO 4 is typical.</div>
                        </div>
                        <div class="form-group">
                            <label>Multi-Coin Slot GPIO</label>
                            <input type="number" name="u_coin_pin" value="{U_COIN_PIN}">
                            <div class="hint">Pulse slot on GPIO 3.</div>
                        </div>
                        <div class="form-group">
                            <label>Indicator LED GPIO</label>
                            <input type="number" name="led_pin" value="{LED_PIN}">
                        </div>
                        <div class="form-group">
                            <label>LED Polarity Logic</label>
                            <select name="led_active_low">
                                <option value="1" {LED_ACTIVE_LOW_SELECTED}>Active LOW (Onboard blue LED)</option>
                                <option value="0" {LED_ACTIVE_HIGH_SELECTED}>Active HIGH (Standard External LED)</option>
                            </select>
                        </div>
                        <div class="form-group">
                            <label>Relay Power GPIO</label>
                            <input type="number" name="relay_pin" value="{RELAY_PIN}">
                            <div class="hint">Coin slot enable / power relay (GPIO 5).</div>
                        </div>
                        <div class="form-group">
                            <label>Relay Power Mode</label>
                            <select name="relay_mode">
                                <option value="0" {RELAY_MODE_ALWAYS_SELECTED}>Always Powered ON (Recommended)</option>
                                <option value="1" {RELAY_MODE_ARMED_SELECTED}>Armed-Only (Powered during Insert Coin)</option>
                            </select>
                            <div class="hint">Always Powered keeps coin acceptor energized 24/7.</div>
                        </div>
                        <div class="form-group">
                            <label>Relay Polarity</label>
                            <select name="relay_active_low">
                                <option value="0" {RELAY_HIGH_SELECTED}>Active HIGH (Direct 3.3V/5V drive)</option>
                                <option value="1" {RELAY_LOW_SELECTED}>Active LOW (Optocoupler relay boards)</option>
                            </select>
                            <div class="hint">Invert if relay is ON when it should be OFF.</div>
                        </div>
                    </div>

                    <div class="grid-full">
                        <button type="submit" class="btn" style="width: 100%; font-size: 16px;">💾 Save & Push Configuration Live</button>
                    </div>
                </div>
            </form>
        </div>

        <!-- TAB 3: TOOLS -->
        <div id="tab-tools" class="tab-content">
            <div class="grid">
                <!-- 1v1 Match -->
                <div class="card grid-full">
                    <div class="card-header">
                        <h3 class="card-title">⚔️ 1v1 Match Mode</h3>
                        <span class="status-badge accent">ESPORTS</span>
                    </div>
                    {MATCH_ALERT}
                    <form action="/one_vs_one" method="POST" style="display: flex; flex-direction: column; gap: 16px;">
                        <div class="form-group" style="max-width: 200px;">
                            <label>Stake Minutes</label>
                            <input type="number" id="match_mins_input" name="match_minutes" value="{MATCH_MINUTES}" min="1">
                        </div>
                        
                        <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 24px;">
                            <div style="background: var(--bg); padding: 16px; border-radius: var(--radius-md); border: 1px solid var(--border);">
                                <label style="color: var(--primary);">🎮 Player 1</label>
                                <select id="p1_select" name="p1_ip" style="margin-bottom: 12px;">{P1_OPTIONS}</select>
                                <button type="submit" name="winner" value="p1" class="btn btn-outline" style="width: 100%;">🏆 Award Win to P1</button>
                            </div>
                            <div style="background: var(--bg); padding: 16px; border-radius: var(--radius-md); border: 1px solid var(--border);">
                                <label style="color: var(--danger);">🎮 Player 2</label>
                                <select id="p2_select" name="p2_ip" style="margin-bottom: 12px;">{P2_OPTIONS}</select>
                                <button type="submit" name="winner" value="p2" class="btn btn-outline" style="width: 100%;">🏆 Award Win to P2</button>
                            </div>
                        </div>
                        
                        <button type="button" onclick="checkMatchQualification()" class="btn btn-outline" style="align-self: flex-start; border-color: var(--primary); color: var(--primary);">🔍 Verify Both Players' Balances</button>
                    </form>
                    <div id="match_qual_result" class="alert-box"></div>
                </div>

                <!-- OTA Update -->
                <div class="card">
                    <div class="card-header">
                        <h3 class="card-title">🚀 Firmware Upgrade</h3>
                    </div>
                    <p style="font-size: 13px; color: var(--text-muted);">Upload a compiled binary to upgrade your Kiosk controller wirelessly without USB cables.</p>
                    <a href="/update" class="btn btn-outline" style="width: 100%;">Upload Firmware (OTA) &rarr;</a>
                </div>

                <!-- System Recovery -->
                <div class="card">
                    <div class="card-header">
                        <h3 class="card-title">⚠️ System Recovery</h3>
                    </div>
                    
                    <form action="/reset_vault" method="POST" onsubmit="return confirm('Reset lifetime coin counts?');" style="margin-bottom: 12px;">
                        <label>Reset Vault Counters</label>
                        <div style="display: flex; gap: 8px; margin-top: 6px;">
                            <input type="password" name="reset_pw" placeholder="Admin password">
                            <button type="submit" class="btn btn-danger btn-sm" style="min-height:48px;">Reset</button>
                        </div>
                    </form>
                    <hr style="border: none; border-top: 1px solid var(--border); margin: 12px 0;">
                    <label>⚡ Relay Pin 5 Hardware Test</label>
                    <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 6px; margin-bottom: 12px;">
                        <button type="button" class="btn" style="background: #10b981;" onclick="fetch('/api/relay?state=1').then(r=>r.json()).then(d=>alert('Relay Pin ' + d.relay_pin + ' turned ON! State: ' + d.state))">⚡ Turn Relay ON</button>
                        <button type="button" class="btn btn-outline" onclick="fetch('/api/relay?state=0').then(r=>r.json()).then(d=>alert('Relay Pin ' + d.relay_pin + ' turned OFF! State: ' + d.state))">Turn Relay OFF</button>
                    </div>
                    <hr style="border: none; border-top: 1px solid var(--border); margin: 12px 0;">
                    <div style="display: flex; flex-direction: column; gap: 10px;">
                        <button type="button" class="btn" style="background: #0284c7; box-shadow: 0 4px 12px rgba(2, 132, 199, 0.2);" onclick="if(confirm('🔄 Reboot controller?')) { fetch('/reboot', {method: 'POST'}).then(() => { alert('Rebooting... returning in 5 seconds.'); setTimeout(() => window.location.reload(), 5000); }); }">
                            🔄 Reboot Controller
                        </button>
                        <button type="button" class="btn btn-danger" onclick="if(confirm('⚠️ Factory Reset? All settings will be wiped.')) { fetch('/factory_reset', {method: 'POST'}).then(() => { alert('Resetting...'); setTimeout(() => window.location.reload(), 6000); }); }">
                            Restore Factory Defaults
                        </button>
                    </div>
                </div>
            </div>
        </div>
    </div>

    <script>
    window.fetchDeviceStatus = function() {
        fetch('/api/status')
            .then(res => res.json())
            .then(data => {
                const devices = Array.isArray(data) ? data : (data.devices || []);
                const wifi = data.wifi || null;

                if (wifi) {
                    const rssi = typeof wifi.rssi === 'number' ? wifi.rssi : -100;
                    const quality = typeof wifi.quality === 'number' ? wifi.quality : 0;
                    const status = wifi.status || (quality >= 50 ? 'Good' : 'Weak');
                    const isConnected = !!wifi.connected;
                    
                    let wifiColor = 'var(--status-good)';
                    let wifiBg = 'var(--status-good-bg)';
                    let wifiBorder = 'var(--status-good-border)';
                    let icon = '📶';

                    if (!isConnected || quality <= 0) {
                        wifiColor = 'var(--status-critical)';
                        wifiBg = 'var(--status-critical-bg)';
                        wifiBorder = 'var(--status-critical-border)';
                        icon = '❌';
                    } else if (quality < 25) {
                        wifiColor = 'var(--status-critical)';
                        wifiBg = 'var(--status-critical-bg)';
                        wifiBorder = 'var(--status-critical-border)';
                        icon = '⚠️';
                    } else if (quality < 50) {
                        wifiColor = 'var(--status-warning)';
                        wifiBg = 'var(--status-warning-bg)';
                        wifiBorder = 'var(--status-warning-border)';
                        icon = '📶';
                    }

                    const pill = document.getElementById('wifi_quality_pill');
                    const sigText = document.getElementById('wifi_signal_text');
                    const iconEl = document.getElementById('wifi_icon');
                    if (pill && sigText) {
                        pill.style.background = wifiBg;
                        pill.style.color = wifiColor;
                        pill.style.borderColor = wifiBorder;
                        if (iconEl) iconEl.textContent = icon;
                        sigText.textContent = isConnected ? ('Wi-Fi: ' + rssi + ' dBm (' + quality + '%)') : 'Wi-Fi: Disconnected';
                    }

                    const rssiDisp = document.getElementById('wifi_rssi_display');
                    const qualPct = document.getElementById('wifi_quality_pct');
                    const fill = document.getElementById('wifi_meter_fill');
                    const badge = document.getElementById('wifi_status_badge');
                    const ssidDisp = document.getElementById('wifi_ssid_display');
                    const ipDisp = document.getElementById('wifi_ip_display');

                    if (rssiDisp) rssiDisp.textContent = isConnected ? (rssi + ' dBm') : 'Disconnected';
                    if (qualPct) {
                        qualPct.textContent = quality + '% ' + status;
                        qualPct.style.color = wifiColor;
                    }
                    if (fill) {
                        fill.style.width = quality + '%';
                        fill.style.backgroundColor = wifiColor;
                    }
                    if (badge) {
                        badge.textContent = status;
                        badge.style.background = wifiBg;
                        badge.style.color = wifiColor;
                    }
                    if (ssidDisp && wifi.ssid) ssidDisp.textContent = wifi.ssid;
                    if (ipDisp && wifi.ip) ipDisp.textContent = wifi.ip;
                }

                const container = document.getElementById('live_devices_container');
                if (!container) return;
                if (devices.length === 0) {
                    container.innerHTML = '<div style="padding: 24px; text-align: center; color: var(--text-muted); grid-column: 1/-1;">No PisoPhone devices registered.</div>';
                    return;
                }
                let html = '';
                devices.forEach((dev, idx) => {
                    const name = dev.name || ('PisoPhone ' + dev.slotNum);
                    const battery = (typeof dev.battery === 'number' && dev.battery >= 0) ? dev.battery : 100;
                    const isCharging = !!dev.charging;
                    
                    let batteryStatusClass = 'status-good';
                    let statusLabel = 'GOOD CHARGE';
                    
                    if (battery <= 15) {
                        batteryStatusClass = 'status-critical';
                        statusLabel = 'CRITICAL LOW';
                    } else if (battery <= 30) {
                        batteryStatusClass = 'status-warning';
                        statusLabel = 'LOW BATTERY';
                    }

                    if (!dev.isBound) {
                        html += '<div class="device-row empty">' +
                                    '<div class="device-row-identity">' +
                                        '<span class="device-slot-badge">Slot #' + dev.slotNum + '</span>' +
                                        '<div class="device-row-info">' +
                                            '<span class="device-row-name">Empty Slot #' + dev.slotNum + '</span>' +
                                            '<span class="device-row-sub">Seat open & ready for setup</span>' +
                                        '</div>' +
                                    '</div>' +
                                    '<div style="color: var(--text-muted); font-size: 13px; font-style: italic;">' +
                                        'No terminal bound' +
                                    '</div>' +
                                    '<div class="device-row-actions">' +
                                        '<button type="button" class="btn btn-primary btn-sm" onclick="occupySlot(' + dev.slotNum + ')" style="padding: 8px 16px; font-weight: 700;">' +
                                            '⚡ Occupy Slot & Install' +
                                        '</button>' +
                                    '</div>' +
                                '</div>';
                    } else if (dev.online) {
                        const mins = Math.floor(dev.time / 60);
                        const secs = dev.time % 60;
                        const timeStr = mins + 'm ' + secs + 's';
                        const active = dev.time > 0;
                        
                        const badgeHtml = active 
                            ? '<span class="device-badge active">ACTIVE</span>'
                            : '<span class="device-badge standby">STANDBY</span>';
                            
                        const batteryIcon = isCharging ? '⚡' : '🔋';
                        const batteryText = (isCharging ? '⚡ ' : '') + battery + '%';

                        html += '<div class="device-row">' +
                                    '<div class="device-row-identity">' +
                                        '<span class="device-slot-badge">Slot #' + dev.slotNum + '</span>' +
                                        '<div class="device-row-info">' +
                                            '<span class="device-row-name">' + name + '</span>' +
                                            '<span class="device-row-sub">' + dev.ip + (dev.deviceId ? ' • ' + dev.deviceId : '') + '</span>' +
                                        '</div>' +
                                    '</div>' +
                                    '<div class="device-row-metrics">' +
                                        '<div class="device-row-timer">' +
                                            '<span>⏱️ ' + timeStr + '</span>' +
                                            badgeHtml +
                                        '</div>' +
                                        '<div class="device-row-battery ' + batteryStatusClass + '">' +
                                            '<span style="font-size: 12px; font-weight: 700;">' + batteryIcon + ' ' + batteryText + '</span>' +
                                            '<div class="battery-bar-bg" style="width: 50px; height: 6px; display: inline-block; margin-left: 4px;">' +
                                                '<div class="battery-bar-fill" style="width: ' + battery + '%;"></div>' +
                                            '</div>' +
                                        '</div>' +
                                    '</div>' +
                                    '<div class="device-row-actions">' +
                                        '<button type="button" class="btn btn-outline btn-sm" onclick="triggerAction(\'' + dev.ip + '\', \'locate\', this)">' +
                                            '📍 Locate' +
                                        '</button>' +
                                        '<button type="button" class="btn btn-outline btn-sm" style="border-color: var(--danger); color: var(--danger);" onclick="unpairSlot(' + dev.slotNum + ')">' +
                                            '🔓 Unpair' +
                                        '</button>' +
                                    '</div>' +
                                '</div>';
                    } else {
                        html += '<div class="device-row offline">' +
                                    '<div class="device-row-identity">' +
                                        '<span class="device-slot-badge">Slot #' + dev.slotNum + '</span>' +
                                        '<div class="device-row-info">' +
                                            '<span class="device-row-name">' + name + '</span>' +
                                            '<span class="device-row-sub">' + dev.ip + '</span>' +
                                        '</div>' +
                                    '</div>' +
                                    '<div class="device-row-metrics">' +
                                        '<span class="device-badge offline">OFFLINE / DISCONNECTED</span>' +
                                    '</div>' +
                                    '<div class="device-row-actions">' +
                                        '<button type="button" class="btn btn-outline btn-sm" style="border-color: var(--danger); color: var(--danger);" onclick="unpairSlot(' + dev.slotNum + ')">' +
                                            '🔓 Unpair' +
                                        '</button>' +
                                    '</div>' +
                                '</div>';
                    }
                });
                container.innerHTML = html;
            })
            .catch(err => console.log('Status polling error', err));
    }
    setInterval(fetchDeviceStatus, 3000);
    document.addEventListener("DOMContentLoaded", fetchDeviceStatus);

    window.checkMatchQualification = function() {
        const p1 = document.getElementById('p1_select') ? document.getElementById('p1_select').value : '';
        const p2 = document.getElementById('p2_select') ? document.getElementById('p2_select').value : '';
        const mins = document.getElementById('match_mins_input') ? document.getElementById('match_mins_input').value : '15';
        const resDiv = document.getElementById('match_qual_result');
        if (!p1 || !p2) { alert('Select both players.'); return; }
        if (p1 === p2) { resDiv.style.display = 'block'; resDiv.style.background = '#fef2f2'; resDiv.style.border = '1px solid #fecaca'; resDiv.style.color = '#991b1b'; resDiv.innerHTML = '❌ <b>Error:</b> Players cannot be the same device.'; return; }
        resDiv.style.display = 'block'; resDiv.style.background = '#f8fafc'; resDiv.style.border = '1px solid #e2e8f0'; resDiv.style.color = '#334155'; resDiv.innerHTML = '⏳ Verifying balances...';
        fetch('/check_qualification?p1=' + encodeURIComponent(p1) + '&p2=' + encodeURIComponent(p2) + '&minutes=' + encodeURIComponent(mins))
            .then(res => res.json())
            .then(data => {
                if (data.success) {
                    if (data.qualified) {
                        resDiv.style.background = '#f0fdf4'; resDiv.style.border = '1px solid #bbf7d0'; resDiv.style.color = '#166534';
                        resDiv.innerHTML = '✅ <b>BOTH QUALIFIED FOR ' + data.stake_minutes + 'm MATCH!</b><br>• P1: ' + data.p1_formatted + '<br>• P2: ' + data.p2_formatted;
                    } else {
                        resDiv.style.background = '#fef2f2'; resDiv.style.border = '1px solid #fecaca'; resDiv.style.color = '#991b1b';
                        resDiv.innerHTML = '❌ <b>NOT QUALIFIED</b><br>• P1: ' + data.p1_formatted + '<br>• P2: ' + data.p2_formatted + '<br><i>' + data.message + '</i>';
                    }
                } else { resDiv.innerHTML = '❌ Error checking qualification.'; }
            }).catch(err => resDiv.innerHTML = '❌ Network error.');
    }
    </script>

    <!-- Token Upgrade Modal -->
    <div id="token_modal" class="modal-overlay" style="display: none; position: fixed; inset: 0; background: rgba(0,0,0,0.6); z-index: 10000; align-items: center; justify-content: center; padding: 16px;">
        <div style="background: var(--card-bg); border: 1px solid var(--border); border-radius: var(--radius-lg); padding: 24px; max-width: 500px; width: 100%; box-shadow: var(--shadow-lg);">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
                <h3 style="margin: 0; font-size: 18px; font-weight: 700;">🔑 Upgrade Hardware Capacity</h3>
                <button type="button" onclick="closeTokenModal()" style="background: none; border: none; font-size: 20px; cursor: pointer; color: var(--text-muted);">&times;</button>
            </div>
            <p style="font-size: 13px; color: var(--text-muted); margin-bottom: 16px;">Paste the signed Slot Token issued from your Pisophone Cloud Dashboard to expand terminal capacity on this box.</p>
            <div class="form-group">
                <label>PISOSLOT Token</label>
                <textarea id="token_input" rows="3" placeholder="PISOSLOT.AA:BB:CC:DD:EE:FF.5.1798761600000.abcd..." style="width: 100%; font-family: monospace; font-size: 12px; padding: 10px; border-radius: var(--radius-sm); border: 1px solid var(--border); background: var(--bg);"></textarea>
            </div>
            <div id="token_error" style="display: none; color: var(--danger); font-size: 12px; margin-bottom: 12px; font-weight: 600;"></div>
            <div style="display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px;">
                <button type="button" class="btn btn-outline" onclick="closeTokenModal()">Cancel</button>
                <button type="button" class="btn btn-primary" onclick="submitSlotToken()">Verify & Upgrade</button>
            </div>
        </div>
    </div>

    <!-- QR Code Handshake Pairing Modal -->
    <div id="qr_pair_modal" class="modal-overlay" style="display: none; position: fixed; inset: 0; background: rgba(0,0,0,0.7); z-index: 10000; align-items: center; justify-content: center; padding: 16px;">
        <div style="background: var(--card-bg); border: 1px solid var(--border); border-radius: var(--radius-lg); padding: 24px; max-width: 440px; width: 100%; box-shadow: var(--shadow-lg); text-align: center;">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;">
                <h3 style="margin: 0; font-size: 18px; font-weight: 700;">📱 Pair Phone to Slot #<span id="qr_slot_title">1</span></h3>
                <button type="button" onclick="closePairingQrModal()" style="background: none; border: none; font-size: 20px; cursor: pointer; color: var(--text-muted);">&times;</button>
            </div>
            <p style="font-size: 13px; color: var(--text-muted); margin-bottom: 16px;">
                Open the PisoPhone Kiosk App on your phone and scan this QR code to initialize pairing and exchange shared cryptographic keys.
            </p>
            
            <div style="background: white; padding: 16px; border-radius: 12px; display: inline-block; box-shadow: 0 4px 12px rgba(0,0,0,0.1); margin-bottom: 14px;">
                <canvas id="qr_canvas" width="260" height="260" style="display: block; margin: 0 auto;"></canvas>
            </div>
            
            <div id="qr_fallback_text" style="display: none; font-family: monospace; font-size: 11px; word-break: break-all; color: var(--primary); margin-bottom: 12px;"></div>

            <div style="background: var(--bg); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 10px; font-size: 12px; text-align: left; margin-bottom: 16px;">
                <div style="display: flex; justify-content: space-between;">
                    <span style="color: var(--text-muted);">Cabinet IP:</span>
                    <span id="qr_modal_ip" style="font-family: monospace; font-weight: 600;">{IP_ADDRESS}</span>
                </div>
            </div>

            <button type="button" class="btn btn-outline" style="width: 100%;" onclick="closePairingQrModal()">Done</button>
        </div>
    </div>

    <!-- WebUSB 1-Click Provisioning Modal -->
    <div id="provision_modal" class="modal-overlay" style="display: none; position: fixed; inset: 0; background: rgba(0,0,0,0.65); z-index: 10000; align-items: center; justify-content: center; padding: 16px;">
        <div style="background: var(--card-bg); border: 1px solid var(--border); border-radius: var(--radius-lg); padding: 24px; max-width: 480px; width: 100%; box-shadow: var(--shadow-lg);">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
                <h3 style="margin: 0; font-size: 18px; font-weight: 700;">📱 Start Sideload & Pair (Slot #<span id="prov_slot_num">1</span>)</h3>
                <button type="button" onclick="closeProvisionModal()" style="background: none; border: none; font-size: 20px; cursor: pointer; color: var(--text-muted);">&times;</button>
            </div>
            <p style="font-size: 13px; color: var(--text-muted); margin-bottom: 16px; line-height: 1.5;">
                To bypass browser USB security blocks and enjoy zero-flag setup, PisoPhone uses an HTTPS-secured cloud flasher to flash, authorize, and link your terminal.
            </p>
            
            <div style="background: var(--bg); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 12px; margin-bottom: 18px; font-size: 12px; display: flex; flex-direction: column; gap: 8px;">
                <div style="display: flex; align-items: center; gap: 8px; color: var(--success); font-weight: 600;">
                    <span>✔</span> Secure HTTPS WebUSB Tunnel
                </div>
                <div style="display: flex; align-items: center; gap: 8px; color: var(--success); font-weight: 600;">
                    <span>✔</span> Over-The-Air APK Cache Delivery
                </div>
                <div style="display: flex; align-items: center; gap: 8px; color: var(--success); font-weight: 600;">
                    <span>✔</span> 1-Click Redirect Slot Association
                </div>
            </div>

            <div style="display: flex; justify-content: flex-end; gap: 8px;">
                <button type="button" class="btn btn-outline" onclick="closeProvisionModal()">Close</button>
                <button type="button" class="btn btn-primary" onclick="launchHttpsFlasher()">⚡ Proceed to HTTPS Flasher</button>
            </div>
        </div>
    </div>

    <script>
    const ESP32_MAC = "{MAC_ADDRESS}";
    const ESP32_SECRET = "{SHARED_SECRET}";
    const ESP32_HOST = window.location.hostname;
    let activeSlotNum = 1;
    let localApkBytes = null;

    window.copyMacToClipboard = function(mac) {
        const val = (mac && mac !== '{MAC_ADDRESS}') ? mac : ESP32_MAC;
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(val).then(() => {
                alert('Copied MAC Address: ' + val);
            }).catch(() => {
                prompt('Copy MAC address:', val);
            });
        } else {
            prompt('Copy MAC address:', val);
        }
    };

    window.occupySlot = function(slot) {
        const s = slot || activeSlotNum || 1;
        const targetUrl = 'https://pisophone.pages.dev/installer/?mac=' + encodeURIComponent(ESP32_MAC) + 
                          '&ip=' + encodeURIComponent(ESP32_HOST) + 
                          '&slot=' + encodeURIComponent(s) + 
                          '&secret=' + encodeURIComponent(ESP32_SECRET) +
                          '&name=' + encodeURIComponent('PisoPhone ' + s);
        window.location.href = targetUrl;
    };

    window.showPairingQrModal = function(slotNum) {
        activeSlotNum = slotNum || 1;
        const modal = document.getElementById('qr_pair_modal');
        if (!modal) return;
        const slotTitle = document.getElementById('qr_slot_title');
        if (slotTitle) slotTitle.textContent = activeSlotNum;
        const ipElem = document.getElementById('qr_modal_ip');
        const hostIp = window.location.hostname || "192.168.4.1";
        if (ipElem) ipElem.textContent = hostIp;

        const payloadObj = {
            pisophone_pair: 1,
            ip: hostIp,
            port: 80,
            ws_port: 81,
            mac: ESP32_MAC,
            secret: ESP32_SECRET,
            slot: activeSlotNum,
            name: "Slot #" + activeSlotNum
        };
        const payloadStr = JSON.stringify(payloadObj);

        modal.style.display = 'flex';
        try {
            if (typeof QRious !== 'undefined') {
                new QRious({
                    element: document.getElementById('qr_canvas'),
                    value: payloadStr,
                    size: 260,
                    level: 'M'
                });
            } else {
                const fb = document.getElementById('qr_fallback_text');
                if (fb) {
                    fb.textContent = payloadStr;
                    fb.style.display = 'block';
                }
            }
        } catch (e) {
            console.error("QR render error:", e);
        }
    };

    window.closePairingQrModal = function() {
        const modal = document.getElementById('qr_pair_modal');
        if (modal) modal.style.display = 'none';
    };

    window.openSecureOriginModal = function() {
        const modal = document.getElementById('secure_origin_modal');
        if (modal) modal.style.display = 'flex';
    };
    window.closeSecureOriginModal = function() {
        const modal = document.getElementById('secure_origin_modal');
        if (modal) modal.style.display = 'none';
    };
    window.copyFlagUrl = function() {
        const input = document.getElementById('flag_url_input');
        if (input) {
            navigator.clipboard.writeText(input.value);
            alert('Copied flag URL to clipboard: ' + input.value + '\n\nPaste this into your browser address bar.');
        }
    };
    window.copyOriginsList = function() {
        const input = document.getElementById('origins_list_input');
        if (input) {
            navigator.clipboard.writeText(input.value);
            alert('Copied origins to clipboard: ' + input.value + '\n\nPaste this into the flag text box and click Relaunch.');
        }
    };

    function initSecureOriginDetection() {
        const originInput = document.getElementById('origins_list_input');
        if (originInput) {
            const host = window.location.hostname;
            const port = window.location.port ? (':' + window.location.port) : '';
            const currentOrigin = 'http://' + host + port;
            const origins = ['http://kioskmanager.local', 'http://192.168.4.1', currentOrigin];
            const uniqueOrigins = [...new Set(origins)].join(',');
            originInput.value = uniqueOrigins;
        }

        const isHttp = window.location.protocol === 'http:';
        const isLocalhost = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1';
        if (isHttp && !isLocalhost && !navigator.usb) {
            const banner = document.getElementById('secure_context_banner');
            if (banner) banner.style.display = 'block';
        }
    }
    document.addEventListener('DOMContentLoaded', initSecureOriginDetection);

    window.openTokenModal = function() {
        document.getElementById('token_modal').style.display = 'flex';
        document.getElementById('token_input').value = '';
        document.getElementById('token_error').style.display = 'none';
    };
    window.closeTokenModal = function() {
        document.getElementById('token_modal').style.display = 'none';
    };

    window.submitSlotToken = function() {
        const token = document.getElementById('token_input').value.trim();
        const errDiv = document.getElementById('token_error');
        if (!token) {
            errDiv.textContent = 'Please paste a token.';
            errDiv.style.display = 'block';
            return;
        }
        errDiv.style.display = 'none';
        fetch('/api/slots/apply_token?token=' + encodeURIComponent(token), { method: 'POST' })
            .then(res => res.json())
            .then(data => {
                if (data.success) {
                    alert('🎉 Capacity upgraded to ' + data.maxSlots + ' seats!');
                    location.reload();
                } else {
                    errDiv.textContent = data.error || 'Invalid token.';
                    errDiv.style.display = 'block';
                }
            })
            .catch(err => {
                errDiv.textContent = 'Network error: ' + err.message;
                errDiv.style.display = 'block';
            });
    };

    window.syncCloudSnapshot = function(btn) {
        btn.textContent = '⏳ Syncing...';
        btn.disabled = true;
        fetch('/api/slots/cloud_sync', { method: 'POST' })
            .then(res => res.json())
            .then(data => {
                alert('☁️ Cloud snapshot report sent successfully!');
                btn.textContent = '☁️ Sync to Cloud';
                btn.disabled = false;
            })
            .catch(err => {
                alert('Cloud sync failed: ' + err.message);
                btn.textContent = '☁️ Sync to Cloud';
                btn.disabled = false;
            });
    };

    window.unpairSlot = function(slot) {
        if (!confirm('Unpair Slot #' + slot + '? The seat will remain valid and open for a replacement terminal.')) return;
        fetch('/api/slots/unpair?slot=' + slot, { method: 'POST' })
            .then(res => res.json())
            .then(data => {
                if (data.success) {
                    location.reload();
                } else {
                    alert('Error: ' + data.error);
                }
            })
            .catch(err => alert('Network error: ' + err.message));
    };

    window.allocateSlotCredit = function(slot, type) {
        const desc = type === 'month' ? '30 Days (1 Month Credit)' : (type === 'year' ? '1 Year (1 Annual Credit)' : '2 Minutes (Test Credit)');
        if (!confirm('Allocate ' + desc + ' to Slot #' + slot + '?')) return;
        fetch('/api/credits/allocate?slot=' + slot + '&type=' + type, { method: 'POST' })
            .then(res => res.json())
            .then(data => {
                if (data.success) {
                    location.reload();
                } else {
                    alert('Credit Allocation Failed: ' + (data.error || 'Check vault credits balance'));
                }
            })
            .catch(err => alert('Network error: ' + err.message));
    };

    window.openProvisionModal = function(slot) {
        activeSlotNum = slot;
        document.getElementById('prov_slot_num').textContent = slot;
        document.getElementById('provision_modal').style.display = 'flex';
    };
    window.closeProvisionModal = function() {
        document.getElementById('provision_modal').style.display = 'none';
    };
    window.launchHttpsFlasher = function() {
        window.open('https://pisophone.pages.dev/installer/?mac=' + ESP32_MAC + '&ip=' + ESP32_HOST + '&slot=' + activeSlotNum, '_blank');
        closeProvisionModal();
    };
    window.launchHttpsFlasherMain = function() {
        const slotSelect = document.getElementById('main_prov_slot_select');
        const slot = slotSelect ? parseInt(slotSelect.value) : (activeSlotNum || 1);
        window.open('https://pisophone.pages.dev/installer/?mac=' + ESP32_MAC + '&ip=' + ESP32_HOST + '&slot=' + slot, '_blank');
    };

    window.openDeprovisionModal = function(slot, devId) {
        activeSlotNum = slot;
        document.getElementById('deprov_slot_num').textContent = slot;
        document.getElementById('deprov_log').textContent = 'Connect phone via USB and click Confirm Deprovision.\n';
        document.getElementById('deprovision_modal').style.display = 'flex';
    };
    window.closeDeprovisionModal = function() {
        document.getElementById('deprovision_modal').style.display = 'none';
    };

    window.executeDeprovisionFlow = async function() {
        const btn = document.getElementById('start_deprov_btn');
        const log = document.getElementById('deprov_log');
        const pin = document.getElementById('deprov_pin_input').value.trim() || '1234';
        btn.disabled = true;

        const appendLog = (msg) => {
            log.textContent += msg + '\n';
            log.scrollTop = log.scrollHeight;
        };

        try {
            if (!navigator.usb) throw new Error('WebUSB not supported in this browser.');
            if (!window.webADB) throw new Error('WebADB library not loaded.');

            appendLog('[1/3] Connecting USB device...');
            await window.webADB.connect((t) => appendLog(t));

            appendLog('[2/3] Deprovisioning Device Owner and removing kiosk package...');
            await window.webADB.deprovisionDevice(pin, (t) => appendLog(t));

            appendLog('[3/3] Freeing Slot #' + activeSlotNum + ' on ESP32...');
            await fetch('/api/slots/unpair?slot=' + activeSlotNum, { method: 'POST' });

            appendLog('\n✅ DEPROVISION COMPLETE! Phone restored, seat slot is open.');
            setTimeout(() => location.reload(), 1500);
        } catch (err) {
            appendLog('\n[ERROR] ' + (err.message || err));
            btn.disabled = false;
        }
    };

    window.checkQuickAdjustExpired = function() {
        const sel = document.getElementById('quick_adjust_target');
        const warn = document.getElementById('quick_adjust_warn');
        if (!sel || !warn) return false;
        let hasExpired = false;
        if (sel.value === 'ALL') {
            const expiredOpts = sel.querySelectorAll('option[data-expired="true"]');
            hasExpired = (expiredOpts.length > 0);
            if (hasExpired) {
                warn.innerHTML = '🚫 <b>Broadcast Notice:</b> ' + expiredOpts.length + ' registered device(s) are EXPIRED. Adjusting time is blocked until credits are allocated.';
                warn.style.display = 'block';
            } else {
                warn.style.display = 'none';
            }
        } else {
            const opt = sel.options[sel.selectedIndex];
            hasExpired = (opt && opt.getAttribute('data-expired') === 'true');
            if (hasExpired) {
                const label = opt ? opt.text : 'Selected Device';
                warn.innerHTML = '🚫 <b>Device Expired:</b> ' + label + ' is EXPIRED. Manual time adjustment is blocked until credits are allocated.';
                warn.style.display = 'block';
            } else {
                warn.style.display = 'none';
            }
        }
        return hasExpired;
    };

    window.validateQuickAdjust = function(e, action) {
        if (window.checkQuickAdjustExpired()) {
            if (e) {
                e.preventDefault();
                e.stopPropagation();
            }
            const sel = document.getElementById('quick_adjust_target');
            const isAll = (sel && sel.value === 'ALL');
            const msg = isAll 
                ? "❌ Action Blocked: One or more devices in broadcast are EXPIRED!\n\nPlease allocate credits in the Master Credit Vault to pair and reactivate all devices before adjusting time."
                : "❌ Action Blocked: The selected device is EXPIRED!\n\nPlease allocate credits in the Master Credit Vault to pair and reactivate the device before adjusting time.";
            alert(msg);
            return false;
        }
        return true;
    };

    document.addEventListener('DOMContentLoaded', function() {
        if (window.checkQuickAdjustExpired) window.checkQuickAdjustExpired();
    });
    </script>

</body>
</html>
)HTML";



String getIpFromDeviceId(String id) {
    int startIdx = 0;
    while (startIdx < androidIps.length()) {
        int comma = androidIps.indexOf(',', startIdx);
        if (comma == -1) comma = androidIps.length();
        String entry = androidIps.substring(startIdx, comma);
        entry.trim();
        if (entry.length() > 0) {
            DeviceConfig cfg;
            if (parseDeviceEntry(entry, cfg)) {
                if (cfg.id == id || cfg.ip == id) {
                    return cfg.ip;
                }
            }
        }
        startIdx = comma + 1;
    }
    return id;
}

String getPrimaryTerminalIp() {
    // 1. If armedIp is valid
    if (armedIp.length() > 0) {
        String ip = getIpFromDeviceId(armedIp);
        if (ip.length() > 0 && ip != "127.0.0.1") return ip;
    }
    // 2. Most recently seen tracked device from heartbeat
    unsigned long bestSeen = 0;
    String bestIp = "";
    for (int i = 0; i < trackedDeviceCount; i++) {
        if (trackedDevices[i].lastKnownIp.length() > 0 && trackedDevices[i].lastKnownIp != "127.0.0.1") {
            if (trackedDevices[i].lastSeenMs > bestSeen) {
                bestSeen = trackedDevices[i].lastSeenMs;
                bestIp = trackedDevices[i].lastKnownIp;
            }
        }
    }
    if (bestIp.length() > 0 && (millis() - bestSeen < 120000)) return bestIp;

    // 3. Primary configured IP in androidIps
    if (androidIps.length() > 0) {
        DeviceConfig cfg;
        int comma = androidIps.indexOf(',');
        String entry = (comma == -1) ? androidIps : androidIps.substring(0, comma);
        if (parseDeviceEntry(entry, cfg) && cfg.ip.length() > 0) {
            return cfg.ip;
        }
    }
    return "";
}

void triggerCoinEvent() {
    Serial.printf("[+] Physical coin pulse detected on GPIO %d (Simple Beam Sensor)!\n", coinPin);
    
    // In Armed-Only mode (relayMode == 1), check active armed state or recent session grace window
    bool wasArmed = isSlotArmed() || (millis() - lastArmedTimeMs < 10000);
    if (relayMode == 1 && !wasArmed) {
        Serial.printf("[-] Dropped coin rejected: Slot is in Armed-Only mode and is NOT armed!\n");
        return;
    }

    // Condition check: Check if a specific device is armed, or auto-route to active/connected terminal
    bool isArmed = isSlotArmed() || wasArmed;
    String targetIp = "";
    if (armedIp.length() > 0) {
        targetIp = getIpFromDeviceId(armedIp);
    } else if (lastArmedIp.length() > 0 && (millis() - lastArmedTimeMs < 30000)) {
        targetIp = lastArmedIp;
    }
    if (targetIp.length() == 0) {
        targetIp = getPrimaryTerminalIp();
        if (targetIp.length() > 0) {
            Serial.printf("[⚡ AUTO-ROUTED COIN] Auto-routing coin credit to primary terminal: %s\n", targetIp.c_str());
            isArmed = true;
        }
    }

    // Automatically credit the configured coinPrice (PHP) and minutesPerCoin time for Simple Beam Sensor
    uint32_t beamCreditPhp = (coinPrice > 0.0f) ? (uint32_t)round(coinPrice) : 1;
    totalCoinsLifetime += beamCreditPhp;
    totalCoinsSession += beamCreditPhp;
    totalEarningsLifetime += coinPrice;
    totalEarningsSession += coinPrice;

    // Mark revenue dirty for debounced idle save to protect flash wear under high volume
    revenueDirty = true;
    lastCoinChangeTime = millis();

    triggerLedBlink();
    
    unsigned long long ts = (unsigned long long)millis();
    String txId = String(millis()) + "-" + String(random(1000, 9999));
    int addedSeconds = minutesPerCoin * 60;
    
    // Broadcast instantly over WebSocket if connected
    if (isWsConnected && wsClient.connected()) {
        Serial.printf("[⚡] Pushing Simple Beam Coin (₱%.2f PHP credit, +%d mins) instantly over WebSocket!\n", coinPrice, minutesPerCoin);
        String innerJson = "{\"seconds\":" + String(addedSeconds) + ",\"minutes\":" + String(minutesPerCoin) + ",\"amount\":" + String(coinPrice, 2) + ",\"tx_id\":\"" + txId + "\",\"ts\":\"" + String(ts) + "\"}";
        String payload = aes_encrypt(innerJson, sharedSecret);
        String json = "{\"event\":\"COIN_DETECTED\",\"payload\":\"" + payload + "\",\"seconds\":" + String(addedSeconds) + ",\"amount\":" + String(coinPrice, 2) + ",\"tx_id\":\"" + txId + "\"}";
        sendWsText(wsClient, json);
        armedUntil = millis() + ARM_TTL;
    }
    
    if (targetIp.length() > 0) {
        Serial.printf("[⚡] Routing Simple Beam Coin (₱%.2f, +%d mins) to IP: %s\n", coinPrice, minutesPerCoin, targetIp.c_str());
        sendAuthenticated(targetIp, targetPort, "/add_time", "/challenge", "minutes=" + String(minutesPerCoin) + "&seconds=" + String(addedSeconds) + "&amount=" + String(coinPrice, 2) + "&tx_id=" + txId, 1000);
        armedUntil = millis() + ARM_TTL;
    } else if (trackedDeviceCount > 0) {
        // Fallback: Dispatch to all tracked devices if no specific primary IP is resolved
        for (int i = 0; i < trackedDeviceCount; i++) {
            if (trackedDevices[i].lastKnownIp.length() > 0 && trackedDevices[i].lastKnownIp != "127.0.0.1") {
                Serial.printf("[⚡ FALLBACK] Dispatching coin to tracked device IP: %s\n", trackedDevices[i].lastKnownIp.c_str());
                sendAuthenticated(trackedDevices[i].lastKnownIp, targetPort, "/add_time", "/challenge", "minutes=" + String(minutesPerCoin) + "&seconds=" + String(addedSeconds) + "&amount=" + String(coinPrice, 2) + "&tx_id=" + txId, 1000);
            }
        }
    }
}

void triggerUniversalCoinEvent(int pulses) {
    if (pulses <= 0) return;
    Serial.printf("[⚡ UNIVERSAL COIN] %d total pulses accumulated on GPIO %d (₱%d PHP)\n", pulses, universalCoinPin, pulses);

    // In Armed-Only mode (relayMode == 1), check if train started armed or was armed recently
    bool wasArmed = isSlotArmed() || pulseTrainWasArmed || (millis() - lastArmedTimeMs < 10000);
    if (relayMode == 1 && !wasArmed) {
        Serial.printf("[-] Universal coin pulses rejected: Slot is in Armed-Only mode and is NOT armed!\n");
        return;
    }

    // Condition check: Check if a specific device is armed, or auto-route to active/connected terminal
    bool isArmed = isSlotArmed() || wasArmed;
    String targetIp = "";
    if (armedIp.length() > 0) {
        targetIp = getIpFromDeviceId(armedIp);
    } else if (pulseTrainDeviceIp.length() > 0) {
        targetIp = pulseTrainDeviceIp;
    } else if (lastArmedIp.length() > 0 && (millis() - lastArmedTimeMs < 30000)) {
        targetIp = lastArmedIp;
    }
    if (targetIp.length() == 0) {
        targetIp = getPrimaryTerminalIp();
        if (targetIp.length() > 0) {
            Serial.printf("[⚡ AUTO-ROUTED COIN] Auto-routing ₱%d universal coin to terminal: %s\n", pulses, targetIp.c_str());
            isArmed = true;
        }
    }

    // Dynamic rate calculation: minutesPerCoin for coinPrice PHP
    // Pulses directly represent PHP count (1 pulse = 1 PHP, 5 pulses = 5 PHP, 10 pulses = 10 PHP, 20 pulses = 20 PHP)
    float effectiveRateMinutes = (coinPrice > 0.0f) ? ((float)minutesPerCoin / coinPrice) : (float)minutesPerCoin;
    int addedSeconds = (int)round((float)pulses * effectiveRateMinutes * 60.0f);
    int addedMinutes = addedSeconds / 60;
    if (addedSeconds <= 0) {
        addedSeconds = 60;
        addedMinutes = 1;
    }

    totalCoinsLifetime += pulses;
    totalCoinsSession += pulses;
    totalEarningsLifetime += (float)pulses;
    totalEarningsSession += (float)pulses;

    // Mark revenue dirty for debounced idle save to protect flash wear under high volume
    revenueDirty = true;
    lastCoinChangeTime = millis();

    triggerLedBlink(pulses > 1 ? 4 : 2);

    unsigned long long ts = (unsigned long long)millis();
    String txId = String(millis()) + "-" + String(random(1000, 9999));

    // Broadcast instantly over WebSocket if connected
    if (isWsConnected && wsClient.connected()) {
        Serial.printf("[⚡] Pushing ₱%d (+%d mins / %d secs) over WebSocket!\n", pulses, addedMinutes, addedSeconds);
        String innerJson = "{\"seconds\":" + String(addedSeconds) + ",\"minutes\":" + String(addedMinutes) + ",\"amount\":" + String(pulses) + ",\"tx_id\":\"" + txId + "\",\"ts\":\"" + String(ts) + "\"}";
        String payload = aes_encrypt(innerJson, sharedSecret);
        String json = "{\"event\":\"COIN_DETECTED\",\"payload\":\"" + payload + "\",\"seconds\":" + String(addedSeconds) + ",\"amount\":" + String(pulses) + ",\"tx_id\":\"" + txId + "\"}";
        sendWsText(wsClient, json);
        armedUntil = millis() + ARM_TTL;
    }

    if (targetIp.length() > 0) {
        Serial.printf("[⚡] Routing universal coin to IP: %s\n", targetIp.c_str());
        sendAuthenticated(targetIp, targetPort, "/add_time", "/challenge", "minutes=" + String(addedMinutes) + "&seconds=" + String(addedSeconds) + "&amount=" + String(pulses) + "&tx_id=" + txId, 1000);
        armedUntil = millis() + ARM_TTL;
    } else if (trackedDeviceCount > 0) {
        for (int i = 0; i < trackedDeviceCount; i++) {
            if (trackedDevices[i].lastKnownIp.length() > 0 && trackedDevices[i].lastKnownIp != "127.0.0.1") {
                Serial.printf("[⚡ FALLBACK] Dispatching coin to tracked device IP: %s\n", trackedDevices[i].lastKnownIp.c_str());
                sendAuthenticated(trackedDevices[i].lastKnownIp, targetPort, "/add_time", "/challenge", "minutes=" + String(addedMinutes) + "&seconds=" + String(addedSeconds) + "&amount=" + String(pulses) + "&tx_id=" + txId, 1000);
            }
        }
    }
}

int getDeviceTimeRemainingSeconds(String targetIp, String* errOut) {
    int rem = getTrackedTimeRemaining(targetIp, 15000);
    if (rem < 0) {
        if (errOut) *errOut = "Device offline or unreachable via telemetry.";
        return -1;
    }
    return rem;
}

// ============================================================================
// WEB PORTAL & HTTP REST API ROUTE HANDLERS

// ============================================================================
bool checkAuth() {
    if (webServer.hasArg("device_id") && webServer.hasArg("ts") && webServer.hasArg("sig")) {
        String devId = webServer.arg("device_id");
        String tsStr = webServer.arg("ts");
        String sig = webServer.arg("sig");
        if (verifyTelemetryAuth(devId, tsStr, sig)) {
            return true;
        }
    }
    if (webServer.hasArg("challenge") && webServer.hasArg("sig")) {
        String challenge = webServer.arg("challenge");
        String sig = webServer.arg("sig");
        if (sig.equals(calculateHMAC(challenge, sharedSecret))) {
            return true;
        }
    }
    if (!webServer.authenticate("admin", webPassword.c_str())) {
        webServer.requestAuthentication(BASIC_AUTH, "HARDWARE Admin Login", "Unauthorized: Please enter admin credentials.");
        return false;
    }
    return true;
}

void redirectHome() {
    webServer.sendHeader("Location", "/");
    webServer.send(303);
}

void handleLogout() {
    webServer.requestAuthentication(BASIC_AUTH, "HARDWARE Admin Login", "Logged out");
    webServer.send(401, "text/html; charset=utf-8", R"HTML(
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Logged Out - HARDWARE Controller</title>
    <style>
        :root {
            --bg: #0f172a;
            --card-bg: #1e293b;
            --text-main: #f8fafc;
            --text-muted: #94a3b8;
            --border: #334155;
            --primary: #10b981;
            --primary-hover: #059669;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            background-color: var(--bg);
            color: var(--text-main);
            display: flex;
            align-items: center;
            justify-content: center;
            min-height: 100vh;
            padding: 16px;
        }
        .logout-card {
            background-color: var(--card-bg);
            border: 1px solid var(--border);
            border-radius: 16px;
            padding: 32px 24px;
            max-width: 400px;
            width: 100%;
            text-align: center;
            box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.5);
        }
        .icon { font-size: 44px; margin-bottom: 14px; }
        h2 { font-size: 20px; font-weight: 800; margin-bottom: 8px; color: var(--text-main); }
        p { font-size: 14px; color: var(--text-muted); line-height: 1.5; margin-bottom: 24px; }
        .login-btn {
            display: inline-flex;
            align-items: center;
            justify-content: center;
            gap: 8px;
            width: 100%;
            padding: 12px 18px;
            background-color: var(--primary);
            color: white;
            text-decoration: none;
            border-radius: 8px;
            font-size: 14px;
            font-weight: 600;
            transition: background-color 0.2s, transform 0.1s;
        }
        .login-btn:hover {
            background-color: var(--primary-hover);
            transform: translateY(-1px);
        }
    </style>
</head>
<body>
    <div class="logout-card">
        <div class="icon">🔒</div>
        <h2>Logged Out</h2>
        <p>You have successfully logged out of the HARDWARE Admin Console. Session credentials have been invalidated.</p>
        <a href="/" class="login-btn">🔑 Log In Again</a>
    </div>
</body>
</html>
)HTML");
}

static bool isPlaceholderTag(const char* start, const char* end) {
    int len = end - start + 1;
    if (len < 3 || len > 32) return false;
    if (*start != '{' || *end != '}') return false;
    for (const char* p = start + 1; p < end; p++) {
        char c = *p;
        if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_')) {
            return false;
        }
    }
    return true;
}

static String getPlaceholderValue(const String& tag) {
    if (tag == "{WIFI_SSID}") return wifiSsid;
    if (tag == "{WIFI_PASS}") return wifiPass;
    if (tag == "{MAC_ADDRESS}") return macAddressStr;
    if (tag == "{COIN_PIN}") return String(coinPin);
    if (tag == "{U_COIN_PIN}") return String(universalCoinPin);
    if (tag == "{LED_PIN}") return String(ledPin);
    if (tag == "{LED_ACTIVE_LOW_SELECTED}") return ledActiveLow ? "selected" : "";
    if (tag == "{LED_ACTIVE_HIGH_SELECTED}") return !ledActiveLow ? "selected" : "";
    if (tag == "{RELAY_PIN}") return String(relayPin);
    if (tag == "{RELAY_MODE_ALWAYS_SELECTED}") return (relayMode == 0) ? "selected" : "";
    if (tag == "{RELAY_MODE_ARMED_SELECTED}") return (relayMode == 1) ? "selected" : "";
    if (tag == "{RELAY_LOW_SELECTED}") return relayActiveLow ? "selected" : "";
    if (tag == "{RELAY_HIGH_SELECTED}") return !relayActiveLow ? "selected" : "";
    if (tag == "{IPS}") return androidIps;
    if (tag == "{DEVICE_SLOTS_MANAGER}") return renderLicenseSlotsHtml();
    if (tag == "{SLOT_OPTIONS}") return renderSlotOptions();
    if (tag == "{MAX_SLOTS}") return String(maxLicensedSlots);
    if (tag == "{PORT}") return String(targetPort);
    if (tag == "{ADMIN_PASSWORD}") return webPassword;
    if (tag == "{PRICE}") return String(coinPrice);
    if (tag == "{MINUTES}") return String(minutesPerCoin);
    if (tag == "{DEBOUNCE}") return String(lockoutDebounceMs);
    if (tag == "{DEVICE_OPTIONS}") return renderDeviceOptions("");
    if (tag == "{MATCH_MINUTES}") return String(matchMinutes);
    if (tag == "{P1_OPTIONS}") return renderDeviceOptions(p1Ip.length() > 0 ? p1Ip : getFirstKnownIp());
    if (tag == "{P2_OPTIONS}") return renderDeviceOptions(p2Ip);
    if (tag == "{MATCH_ALERT}") {
        if (matchStatusMsg.length() > 0) {
            String msg = matchStatusMsg;
            matchStatusMsg = "";
            return msg;
        }
        return "";
    }
    if (tag == "{QUICK_TIME_ALERT}") {
        if (quickTimeStatusMsg.length() > 0) {
            String msg = quickTimeStatusMsg;
            quickTimeStatusMsg = "";
            return msg;
        }
        return "";
    }
    if (tag == "{TOTAL_COINS}") return String(totalCoinsLifetime);
    if (tag == "{SESSION_COINS}") return String(totalCoinsSession);
    if (tag == "{SHARED_SECRET}") return sharedSecret;
    if (tag == "{IP_ADDRESS}") return WiFi.localIP().toString();
    if (tag == "{WIFI_RSSI}") return String(WiFi.RSSI());
    if (tag == "{WIFI_QUALITY}") {
        int rssi = WiFi.RSSI();
        if (WiFi.status() != WL_CONNECTED || rssi <= -100) return "0";
        if (rssi >= -50) return "100";
        return String(2 * (rssi + 100));
    }
    return tag;
}

void streamPortalHtml() {
    webServer.setContentLength(CONTENT_LENGTH_UNKNOWN);
    webServer.send(200, "text/html; charset=utf-8", "");

    const char* ptr = PORTAL_HTML_TEMPLATE;
    const char* chunkStart = ptr;

    while (*ptr != '\0') {
        if (*ptr == '{') {
            const char* tagEnd = strchr(ptr, '}');
            if (tagEnd && isPlaceholderTag(ptr, tagEnd)) {
                if (ptr > chunkStart) {
                    while (chunkStart < ptr) {
                        int len = min((int)(ptr - chunkStart), 1024);
                        char buf[1025];
                        memcpy(buf, chunkStart, len);
                        buf[len] = '\0';
                        webServer.sendContent(buf);
                        chunkStart += len;
                    }
                }
                
                int tagLen = tagEnd - ptr + 1;
                char tagBuf[33];
                memcpy(tagBuf, ptr, tagLen);
                tagBuf[tagLen] = '\0';
                
                String val = getPlaceholderValue(String(tagBuf));
                if (val.length() > 0) {
                    webServer.sendContent(val);
                }
                
                ptr = tagEnd + 1;
                chunkStart = ptr;
                continue;
            }
        }
        ptr++;
    }

    if (ptr > chunkStart) {
        while (chunkStart < ptr) {
            int len = min((int)(ptr - chunkStart), 1024);
            char buf[1025];
            memcpy(buf, chunkStart, len);
            buf[len] = '\0';
            webServer.sendContent(buf);
            chunkStart += len;
        }
    }

    webServer.sendContent("");
}

void handlePortalRoot() {
    if (!checkAuth()) return;
    if (macAddressStr.length() == 0) {
        uint8_t mac[6];
        esp_read_mac(mac, ESP_MAC_WIFI_STA);
        char macBuf[18];
        snprintf(macBuf, sizeof(macBuf), "%02X:%02X:%02X:%02X:%02X:%02X", mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
        macAddressStr = String(macBuf);
    }
    
    // Check for redirection-based pairing action from the HTTPS Installer
    if (webServer.hasArg("action") && webServer.arg("action") == "pair") {
        int slot = webServer.hasArg("slot") ? webServer.arg("slot").toInt() : 0;
        String id = webServer.hasArg("id") ? webServer.arg("id") : "";
        String ip = webServer.hasArg("ip") ? webServer.arg("ip") : "";
        String name = webServer.hasArg("name") ? webServer.arg("name") : ("PisoPhone " + String(slot));

        if (slot >= 1 && slot <= maxLicensedSlots && id.length() > 0) {
            bool res = pairDeviceToSlot(slot, id, ip, name);
            if (res) {
                sendCloudSnapshot();
                // Send standard response but redirect to base root "/" after 3 seconds to clear query parameters
                String successHtml = R"HTML(
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta charset="UTF-8">
                        <title>Pairing Success</title>
                        <style>
                            body { background: #0b0f19; color: #10b981; font-family: sans-serif; display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100vh; margin: 0; }
                            .card { background: #111827; padding: 32px; border-radius: 16px; border: 1px solid #10b981; box-shadow: 0 4px 20px rgba(16, 185, 129, 0.2); text-align: center; max-width: 400px; }
                            h1 { margin-top: 0; font-size: 24px; }
                            p { color: #9ca3af; font-size: 14px; margin-bottom: 20px; }
                            .spinner { border: 4px solid rgba(16, 185, 129, 0.1); border-top: 4px solid #10b981; border-radius: 50%; width: 36px; height: 36px; animation: spin 1s linear infinite; margin: 0 auto; }
                            @keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }
                        </style>
                        <script>
                            setTimeout(function() { window.location.href = '/'; }, 3000);
                        </script>
                    </head>
                    <body>
                        <div class="card">
                            <h1>🎉 Device Paired Successfully!</h1>
                            <p>Slot #_SLOT_ is now linked to your PisoPhone terminal.</p>
                            <p>Returning to your local Admin Console dashboard...</p>
                            <div class="spinner"></div>
                        </div>
                    </body>
                    </html>
                )HTML";
                successHtml.replace("_SLOT_", String(slot));
                webServer.send(200, "text/html", successHtml);
                return;
            }
        }
    }
    
    streamPortalHtml();
}

void handleReboot() {
    if (!checkAuth()) return;
    Serial.println("\n[🔄 HTTP API] Reboot request received from Web Portal.");
    if (revenueDirty || totalCoinsLifetime != lastSavedTotalCoins || totalEarningsLifetime != lastSavedTotalEarnings) {
        prefs.begin("kiosk_cfg", false);
        prefs.putULong("total_coins", totalCoinsLifetime);
        prefs.putFloat("total_earnings", totalEarningsLifetime);
        prefs.end();
        lastSavedTotalCoins = totalCoinsLifetime;
        lastSavedTotalEarnings = totalEarningsLifetime;
        revenueDirty = false;
    }
    webServer.send(200, "text/plain", "REBOOTING");
    delay(500);
    ESP.restart();
}

void handleFactoryReset() {
    if (!checkAuth()) return;
    Serial.println("\n[⚠️ HTTP API] Factory reset request received from Web Portal.");
    factoryResetDefaults();
    webServer.send(200, "text/plain", "OK");
    delay(1000);
    ESP.restart();
}

void handleResetVault() {
    if (!checkAuth()) return;
    if (webServer.hasArg("reset_pw")) {
        String enteredPw = webServer.arg("reset_pw");
        if (enteredPw == webPassword) {
            totalCoinsLifetime = 0;
            totalCoinsSession = 0;
            totalEarningsLifetime = 0.0f;
            totalEarningsSession = 0.0f;
            lastSavedTotalCoins = 0;
            lastSavedTotalEarnings = 0.0f;
            prefs.begin("kiosk_cfg", false);
            prefs.putULong("total_coins", 0);
            prefs.putFloat("total_earnings", 0.0f);
            prefs.end();
            Serial.println("[💰 VAULT] Lifetime revenue counter reset to 0 by Admin.");
        } else {
            Serial.println("[⚠️ VAULT] Reset attempted with incorrect password.");
        }
    }
    redirectHome();
}

void handleSave() {
    if (!checkAuth()) return;
    prefs.begin("kiosk_cfg", false);
    if (webServer.hasArg("wifi_ssid")) { wifiSsid = webServer.arg("wifi_ssid"); prefs.putString("wifi_ssid", wifiSsid); }
    if (webServer.hasArg("wifi_pass")) { wifiPass = webServer.arg("wifi_pass"); prefs.putString("wifi_pass", wifiPass); }
    if (webServer.hasArg("coin_pin"))   { coinPin = webServer.arg("coin_pin").toInt(); prefs.putInt("coin_pin", coinPin); }
    if (webServer.hasArg("u_coin_pin")) { universalCoinPin = webServer.arg("u_coin_pin").toInt(); prefs.putInt("u_coin_pin", universalCoinPin); }
    if (webServer.hasArg("led_pin"))    { ledPin  = webServer.arg("led_pin").toInt();  prefs.putInt("led_pin", ledPin); }
    if (webServer.hasArg("led_active_low")) {
        ledActiveLow = (webServer.arg("led_active_low") == "1");
        prefs.putBool("led_active_low", ledActiveLow);
    }
    if (webServer.hasArg("relay_pin"))  { relayPin = webServer.arg("relay_pin").toInt(); prefs.putInt("relay_pin", relayPin); }
    if (webServer.hasArg("ips")) {
        String rawIps = webServer.arg("ips");
        rawIps.trim();
        String cleanIps = "";
        int startIdx = 0;
        while (startIdx < rawIps.length()) {
            int comma = rawIps.indexOf(',', startIdx);
            if (comma == -1) comma = rawIps.length();
            String entry = rawIps.substring(startIdx, comma);
            entry.trim();
            if (entry.length() > 0) {
                DeviceConfig cfg;
                if (parseDeviceEntry(entry, cfg)) {
                    if (cleanIps.length() > 0) cleanIps += ",";
                    cleanIps += cfg.id + "|" + cfg.ip + "|" + cfg.name;
                }
            }
            startIdx = comma + 1;
        }
        androidIps = cleanIps;
        prefs.putString("ips", androidIps);

        // Prune or clear in-memory telemetry tracking
        if (androidIps.length() == 0) {
            trackedDeviceCount = 0;
        } else {
            int newCount = 0;
            for (int i = 0; i < trackedDeviceCount; i++) {
                bool keep = false;
                int sIdx = 0;
                while (sIdx < androidIps.length()) {
                    int c = androidIps.indexOf(',', sIdx);
                    if (c == -1) c = androidIps.length();
                    String e = androidIps.substring(sIdx, c);
                    DeviceConfig cCfg;
                    if (parseDeviceEntry(e, cCfg)) {
                        if ((cCfg.id.length() > 0 && cCfg.id == trackedDevices[i].deviceId) || cCfg.ip == trackedDevices[i].lastKnownIp) {
                            keep = true;
                            break;
                        }
                    }
                    sIdx = c + 1;
                }
                if (keep) {
                    if (newCount != i) {
                        trackedDevices[newCount] = trackedDevices[i];
                    }
                    newCount++;
                }
            }
            trackedDeviceCount = newCount;
        }
    }
    if (webServer.hasArg("port"))       { targetPort = webServer.arg("port").toInt(); prefs.putInt("port", targetPort); }
    if (webServer.hasArg("admin_pw"))   { webPassword = webServer.arg("admin_pw"); prefs.putString("admin_pw", webPassword); }
    if (webServer.hasArg("price"))      { coinPrice = webServer.arg("price").toFloat(); prefs.putFloat("price", coinPrice); }
    if (webServer.hasArg("minutes"))    { minutesPerCoin = webServer.arg("minutes").toInt(); prefs.putInt("minutes", minutesPerCoin); }
    if (webServer.hasArg("debounce"))   { lockoutDebounceMs = webServer.arg("debounce").toInt(); prefs.putInt("debounce", lockoutDebounceMs); }
    if (webServer.hasArg("relay_active_low")) {
        relayActiveLow = (webServer.arg("relay_active_low") == "1" || webServer.arg("relay_active_low") == "true");
        prefs.putBool("relay_active_low", relayActiveLow);
    }
    if (webServer.hasArg("relay_mode")) {
        relayMode = webServer.arg("relay_mode").toInt();
        prefs.putInt("relay_mode", relayMode);
    }
    if (webServer.hasArg("shared_secret")) {
        sharedSecret = webServer.arg("shared_secret");
        prefs.putString("shared_secret", sharedSecret);
    }
    prefs.end();

    // Dynamic GPIO Pin re-binding & ISR attachment
    pinMode(coinPin, INPUT_PULLUP);
    detachInterrupt(digitalPinToInterrupt(universalCoinPin));
    pinMode(universalCoinPin, INPUT_PULLUP);
    attachInterrupt(digitalPinToInterrupt(universalCoinPin), universalCoinIsr, FALLING);
    setLedHardware(currentLedState == LED_STATE_CONNECTED);
    processRelayState();

    Serial.println("\n[+] Config updated and saved. Pushing live config to registered Android terminals...");

    // True Push Configuration to all registered Android terminals
    int startIdx = 0;
    while (startIdx < androidIps.length()) {
        int comma = androidIps.indexOf(',', startIdx);
        if (comma == -1) comma = androidIps.length();
        String entry = androidIps.substring(startIdx, comma);
        entry.trim();
        if (entry.length() > 0) {
            DeviceConfig cfg;
            if (parseDeviceEntry(entry, cfg)) {
                if (cfg.ip.length() > 0 && cfg.ip != "127.0.0.1") {
                    String configParams = "price=" + String(coinPrice) + "&minutes=" + String(minutesPerCoin) + "&admin_pin=" + webPassword;
                    if (cfg.name.length() > 0) {
                        configParams += "&device_name=" + urlEncode(cfg.name);
                    }
                    sendAuthenticated(cfg.ip, targetPort, "/config", "/challenge", configParams, 1000);
                }
            }
        }
        startIdx = comma + 1;
    }

    webServer.send(200, "text/plain", "OK");
}

void handleAddTime() {
    if (!checkAuth()) return;
    int minutes = 60;
    if (webServer.hasArg("add_minutes")) {
        minutes = webServer.arg("add_minutes").toInt();
    }
    if (webServer.hasArg("action") && webServer.arg("action") == "subtract") {
        minutes = -abs(minutes);
    }
    String targetIp = webServer.hasArg("target_ip") ? webServer.arg("target_ip") : "ALL";

    // Enforce Expiration Check (RULE 6: Single verification path)
    uint64_t currentMs = getCurrentMasterTimeMs();
    if (targetIp == "ALL") {
        int expiredCount = 0;
        String expiredDetails = "";
        int startIdx = 0, devNum = 1;
        while (startIdx < androidIps.length()) {
            int comma = androidIps.indexOf(',', startIdx);
            if (comma == -1) comma = androidIps.length();
            String entry = androidIps.substring(startIdx, comma);
            entry.trim();
            if (entry.length() > 0) {
                DeviceConfig cfg;
                if (parseDeviceEntry(entry, cfg)) {
                    int slotIdx = findSlotIndexForDevice(cfg.id, cfg.ip);
                    int daysLeft = -1;
                    int expStatus = getSlotExpirationStatus(slotIdx, currentMs, daysLeft);
                    if (expStatus == 2) {
                        expiredCount++;
                        if (expiredDetails.length() > 0) expiredDetails += ", ";
                        expiredDetails += "PisoPhone " + String(devNum) + " (" + cfg.ip + ")";
                    }
                    devNum++;
                }
            }
            startIdx = comma + 1;
        }

        if (expiredCount > 0) {
            Serial.printf("[-] handleAddTime blocked: %d device(s) are EXPIRED: %s\n", expiredCount, expiredDetails.c_str());
            quickTimeStatusMsg = "<div style='background:#fee2e2;color:#dc2626;padding:10px 14px;border-radius:8px;margin-bottom:12px;font-size:12px;font-weight:700;border:1px solid rgba(239,68,68,0.3);'>❌ Adjustment Blocked: " + String(expiredCount) + " device(s) are EXPIRED (" + expiredDetails + ")! Add credits in the Master Credit Vault to pair and reactivate all devices.</div>";
            redirectHome();
            return;
        }
    } else {
        // Specific target IP
        DeviceConfig targetCfg;
        targetCfg.ip = targetIp;
        int startIdx = 0;
        while (startIdx < androidIps.length()) {
            int comma = androidIps.indexOf(',', startIdx);
            if (comma == -1) comma = androidIps.length();
            String entry = androidIps.substring(startIdx, comma);
            entry.trim();
            if (entry.length() > 0) {
                DeviceConfig cfg;
                if (parseDeviceEntry(entry, cfg) && cfg.ip == targetIp) {
                    targetCfg = cfg;
                    break;
                }
            }
            startIdx = comma + 1;
        }

        int slotIdx = findSlotIndexForDevice(targetCfg.id, targetCfg.ip);
        int daysLeft = -1;
        int expStatus = getSlotExpirationStatus(slotIdx, currentMs, daysLeft);
        if (expStatus == 2) {
            Serial.printf("[-] handleAddTime blocked: Target device %s (Slot #%d) is EXPIRED!\n",
                targetIp.c_str(), (slotIdx >= 0) ? licenseSlots[slotIdx].slotNum : 0);
            quickTimeStatusMsg = "<div style='background:#fee2e2;color:#dc2626;padding:10px 14px;border-radius:8px;margin-bottom:12px;font-size:12px;font-weight:700;border:1px solid rgba(239,68,68,0.3);'>❌ Adjustment Blocked: Target device " + targetIp + " is EXPIRED! Add credits in the Master Credit Vault to pair device.</div>";
            redirectHome();
            return;
        }
    }

    sendAddTime(minutes, targetIp);
    quickTimeStatusMsg = "<div style='background:#e8f5e9;color:#2e7d32;padding:10px 14px;border-radius:8px;margin-bottom:12px;font-size:12px;font-weight:700;border:1px solid rgba(16,185,129,0.3);'>✅ Adjusted " + String(minutes > 0 ? "+" : "") + String(minutes) + "m for " + (targetIp == "ALL" ? "All Devices" : targetIp) + ".</div>";
    redirectHome();
}

void handleOneVsOne() {
    if (!checkAuth()) return;
    p1Ip = webServer.hasArg("p1_ip") ? webServer.arg("p1_ip") : "";
    p2Ip = webServer.hasArg("p2_ip") ? webServer.arg("p2_ip") : "";
    if (webServer.hasArg("match_minutes")) {
        matchMinutes = webServer.arg("match_minutes").toInt();
    }
    
    prefs.begin("kiosk_cfg", false);
    prefs.putString("p1", p1Ip);
    prefs.putString("p2", p2Ip);
    prefs.putInt("match", matchMinutes);
    prefs.end();

    String winner = webServer.hasArg("winner") ? webServer.arg("winner") : "";

    if (winner != "" && p1Ip != "" && p2Ip != "") {
        if (p1Ip == p2Ip) {
            matchStatusMsg = "<div style='background:#ffebee;color:#c62828;padding:8px 12px;border-radius:4px;margin-bottom:10px;font-size:13px;'>❌ <b>Match Blocked:</b> Player 1 and Player 2 cannot be the same device!</div>";
            redirectHome();
            return;
        }

        if (matchMinutes <= 0) {
            matchStatusMsg = "<div style='background:#ffebee;color:#c62828;padding:8px 12px;border-radius:4px;margin-bottom:10px;font-size:13px;'>❌ <b>Match Blocked:</b> Stake minutes must be at least 1 minute!</div>";
            redirectHome();
            return;
        }

        int reqStakeSeconds = matchMinutes * 60;
        String p1Err = "", p2Err = "";
        int p1Sec = getDeviceTimeRemainingSeconds(p1Ip, &p1Err);
        int p2Sec = getDeviceTimeRemainingSeconds(p2Ip, &p2Err);

        if (p1Sec < 0 || p2Sec < 0) {
            String detail = "";
            if (p1Sec < 0) detail += "<br>• <b>Player 1 (" + p1Ip + "):</b> " + p1Err;
            if (p2Sec < 0) detail += "<br>• <b>Player 2 (" + p2Ip + "):</b> " + p2Err;
            matchStatusMsg = "<div style='background:#ffebee;color:#c62828;padding:8px 12px;border-radius:4px;margin-bottom:10px;font-size:13px;'>❌ <b>Match Failed:</b> Unable to connect or verify time balance:" + detail + "</div>";
            redirectHome();
            return;
        }

        int p1Mins = p1Sec / 60;
        int p2Mins = p2Sec / 60;

        if (p1Sec < reqStakeSeconds || p2Sec < reqStakeSeconds) {
            matchStatusMsg = "<div style='background:#ffebee;color:#c62828;padding:8px 12px;border-radius:4px;margin-bottom:10px;font-size:13px;'>❌ <b>Stake Denied:</b> Both devices must have at least " + String(matchMinutes) + "m of active time.<br>• Player 1 (" + p1Ip + "): <b>" + String(p1Mins) + "m remaining</b><br>• Player 2 (" + p2Ip + "): <b>" + String(p2Mins) + "m remaining</b></div>";
            redirectHome();
            return;
        }

        // Both devices meet the stake requirements
        if (winner == "p1") {
            sendAddTime(matchMinutes, p1Ip);
            yield();
            sendAddTime(-matchMinutes, p2Ip);
            matchStatusMsg = "<div style='background:#e8f5e9;color:#2e7d32;padding:8px 12px;border-radius:4px;margin-bottom:10px;font-size:13px;'>🏆 <b>Player 1 Won:</b> Transferred +" + String(matchMinutes) + "m to Player 1 (" + p1Ip + ") and deducted -" + String(matchMinutes) + "m from Player 2 (" + p2Ip + ").</div>";
        } else if (winner == "p2") {
            sendAddTime(matchMinutes, p2Ip);
            yield();
            sendAddTime(-matchMinutes, p1Ip);
            matchStatusMsg = "<div style='background:#e8f5e9;color:#2e7d32;padding:8px 12px;border-radius:4px;margin-bottom:10px;font-size:13px;'>🏆 <b>Player 2 Won:</b> Transferred +" + String(matchMinutes) + "m to Player 2 (" + p2Ip + ") and deducted -" + String(matchMinutes) + "m from Player 1 (" + p1Ip + ").</div>";
        }
    }

    redirectHome();
}

void handleInsertCoin() {
    if (!checkAuth()) return;
    triggerCoinEvent();
    webServer.send(200, "text/plain", "OK");
}

void handleInsertUniversalCoin() {
    if (!checkAuth()) return;
    int pulses = webServer.hasArg("pulses") ? webServer.arg("pulses").toInt() : 1;
    if (pulses <= 0) pulses = 1;
    triggerUniversalCoinEvent(pulses);
    webServer.send(200, "text/plain", "OK");
}

void handleQueryTime() {
    if (!checkAuth()) return;
    if (!webServer.hasArg("ip")) {
        webServer.send(400, "application/json", "{\"success\":false,\"error\":\"Missing ip parameter\"}");
        return;
    }
    String targetIp = webServer.arg("ip");
    targetIp.trim();
    if (targetIp.length() == 0) {
        webServer.send(400, "application/json", "{\"success\":false,\"error\":\"Empty ip parameter\"}");
        return;
    }

    String queryErr = "";
    int seconds = getDeviceTimeRemainingSeconds(targetIp, &queryErr);
    if (seconds < 0) {
        if (queryErr.length() == 0) {
            char defaultErr[64];
            snprintf(defaultErr, sizeof(defaultErr), "Device offline or unreachable on port %d", targetPort);
            queryErr = defaultErr;
        }
        char errBuf[256];
        snprintf(errBuf, sizeof(errBuf), "{\"success\":false,\"ip\":\"%s\",\"error\":\"%s\"}", targetIp.c_str(), queryErr.c_str());
        webServer.send(200, "application/json", errBuf);
        return;
    }

    int mins = seconds / 60;
    int secs = seconds % 60;
    char jsonBuf[256];
    snprintf(jsonBuf, sizeof(jsonBuf),
        "{\"success\":true,\"ip\":\"%s\",\"seconds\":%d,\"minutes\":%d,\"formatted\":\"%dm %ds\"}",
        targetIp.c_str(), seconds, mins, mins, secs);
    webServer.send(200, "application/json", jsonBuf);
}


void handleApiSlots() {
    if (!checkAuth()) return;
    String json = "{\"maxSlots\":" + String(maxLicensedSlots) + ",\"mac\":\"" + macAddressStr + "\",\"slots\":[";
    for (int i = 0; i < maxLicensedSlots; i++) {
        if (i > 0) json += ",";
        char expBuf[24];
        snprintf(expBuf, sizeof(expBuf), "%llu", (unsigned long long)licenseSlots[i].expiresAt);
        json += "{";
        json += "\"slotNum\":" + String(licenseSlots[i].slotNum) + ",";
        json += "\"deviceId\":\"" + licenseSlots[i].deviceId + "\",";
        json += "\"ip\":\"" + licenseSlots[i].ip + "\",";
        json += "\"name\":\"" + licenseSlots[i].name + "\",";
        json += "\"expiresAt\":" + String(expBuf) + ",";
        json += "\"active\":" + String(licenseSlots[i].active ? "true" : "false") + ",";
        json += "\"isBound\":" + String(licenseSlots[i].deviceId.length() > 0 ? "true" : "false");
        json += "}";
    }
    json += "]}";
    webServer.send(200, "application/json", json);
}

void handleApiSlotPair() {
    if (!checkAuth()) return;
    int slot = webServer.hasArg("slot") ? webServer.arg("slot").toInt() : 0;
    String id = webServer.hasArg("id") ? webServer.arg("id") : "";
    String ip = webServer.hasArg("ip") ? webServer.arg("ip") : "";
    String name = webServer.hasArg("name") ? webServer.arg("name") : "";

    if (slot < 1 || slot > maxLicensedSlots || id.length() == 0) {
        webServer.send(400, "application/json", "{\"success\":false,\"error\":\"Invalid slot or device ID\"}");
        return;
    }

    bool res = pairDeviceToSlot(slot, id, ip, name);
    if (res) {
        sendCloudSnapshot();
        webServer.send(200, "application/json", "{\"success\":true,\"slot\":" + String(slot) + "}");
    } else {
        webServer.send(500, "application/json", "{\"success\":false,\"error\":\"Failed to pair\"}");
    }
}

void handleApiSlotUnpair() {
    if (!checkAuth()) return;
    int slot = webServer.hasArg("slot") ? webServer.arg("slot").toInt() : 0;
    if (slot < 1 || slot > maxLicensedSlots) {
        webServer.send(400, "application/json", "{\"success\":false,\"error\":\"Invalid slot number\"}");
        return;
    }

    bool res = unpairSlot(slot);
    if (res) {
        sendCloudSnapshot();
        webServer.send(200, "application/json", "{\"success\":true,\"slot\":" + String(slot) + "}");
    } else {
        webServer.send(500, "application/json", "{\"success\":false,\"error\":\"Failed to unpair\"}");
    }
}

void handleApiSlotApplyToken() {
    if (!checkAuth()) return;
    String token = webServer.hasArg("token") ? webServer.arg("token") : "";
    if (token.length() == 0) {
        webServer.send(400, "application/json", "{\"success\":false,\"error\":\"Missing token\"}");
        return;
    }

    bool ok = applySlotToken(token);
    if (ok) {
        sendCloudSnapshot();
        webServer.send(200, "application/json", "{\"success\":true,\"maxSlots\":" + String(maxLicensedSlots) + "}");
    } else {
        webServer.send(403, "application/json", "{\"success\":false,\"error\":\"Invalid slot token or cryptographic signature mismatch\"}");
    }
}

void handleApiSlotCloudSync() {
    if (!checkAuth()) return;
    sendCloudSnapshot();
    webServer.send(200, "application/json", "{\"success\":true,\"message\":\"Cloud snapshot sent\"}");
}

// Emulate Payment API: Allows browser/website emulator to credit the ESP32 vault
void handleApiCreditsEmulatePayment() {
    webServer.sendHeader("Access-Control-Allow-Origin", "*");
    webServer.sendHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
    webServer.sendHeader("Access-Control-Allow-Headers", "Content-Type");
    if (webServer.method() == HTTP_OPTIONS) {
        webServer.send(204);
        return;
    }

    String type = webServer.hasArg("type") ? webServer.arg("type") : "month";
    int count = webServer.hasArg("count") ? webServer.arg("count").toInt() : 1;
    if (count < 1) count = 1;
    if (count > 100) count = 100;

    type.toLowerCase();
    type.trim();

    if (type == "month") {
        monthlyCredits += count;
    } else if (type == "year") {
        annualCredits += count;
    } else if (type == "test") {
        testCredits += count;
    } else {
        webServer.send(400, "application/json", "{\"success\":false,\"error\":\"Invalid type. Must be 'month', 'year', or 'test'\"}");
        return;
    }

    saveCreditVault();
    Serial.printf("[+] Emulate Payment: Added %d x %s credit(s). Vault: M=%d, Y=%d, T=%d\n",
        count, type.c_str(), monthlyCredits, annualCredits, testCredits);

    String json = "{\"success\":true,\"type\":\"" + type + "\",\"added\":" + String(count) + 
        ",\"monthly\":" + String(monthlyCredits) + 
        ",\"annual\":" + String(annualCredits) + 
        ",\"test\":" + String(testCredits) + 
        ",\"message\":\"Successfully credited " + String(count) + " " + type + " credit(s) to ESP32 vault.\"}";
    webServer.send(200, "application/json", json);
}

// Allocate Credit API: Consumes 1 credit from vault and updates slot expiration
void handleApiCreditsAllocate() {
    webServer.sendHeader("Access-Control-Allow-Origin", "*");
    webServer.sendHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
    webServer.sendHeader("Access-Control-Allow-Headers", "Content-Type");
    if (webServer.method() == HTTP_OPTIONS) {
        webServer.send(204);
        return;
    }

    int slot = webServer.hasArg("slot") ? webServer.arg("slot").toInt() : 0;
    String type = webServer.hasArg("type") ? webServer.arg("type") : "month";
    String err = "";

    if (allocateCreditToSlot(slot, type, err)) {
        int idx = slot - 1;
        char expBuf[24];
        snprintf(expBuf, sizeof(expBuf), "%llu", (unsigned long long)licenseSlots[idx].expiresAt);
        String json = "{\"success\":true,\"slot\":" + String(slot) + 
            ",\"type\":\"" + type + "\"" + 
            ",\"expiresAt\":" + String(expBuf) + 
            ",\"monthly\":" + String(monthlyCredits) + 
            ",\"annual\":" + String(annualCredits) + 
            ",\"test\":" + String(testCredits) + "}";
        webServer.send(200, "application/json", json);
    } else {
        webServer.send(400, "application/json", "{\"success\":false,\"error\":\"" + err + "\"}");
    }
}

// Status API: Inspect credit vault counts
void handleApiCreditsStatus() {
    webServer.sendHeader("Access-Control-Allow-Origin", "*");
    String json = "{\"monthly\":" + String(monthlyCredits) + 
        ",\"annual\":" + String(annualCredits) + 
        ",\"test\":" + String(testCredits) + 
        ",\"maxSlots\":" + String(maxLicensedSlots) + "}";
    webServer.send(200, "application/json", json);
}

void handleApiStatus() {
    if (!checkAuth()) return;

    int rssi = WiFi.RSSI();
    int quality = 0;
    bool isConnected = (WiFi.status() == WL_CONNECTED);
    if (isConnected) {
        if (rssi <= -100) quality = 0;
        else if (rssi >= -50) quality = 100;
        else quality = 2 * (rssi + 100);
    }
    
    String qualityStatus = "Disconnected";
    if (isConnected) {
        if (quality >= 75) qualityStatus = "Excellent";
        else if (quality >= 50) qualityStatus = "Good";
        else if (quality >= 25) qualityStatus = "Fair";
        else qualityStatus = "Weak";
    }

    String json = "{";
    json += "\"wifi\":{";
    json += "\"rssi\":" + String(rssi) + ",";
    json += "\"quality\":" + String(quality) + ",";
    json += "\"status\":\"" + qualityStatus + "\",";
    json += "\"ssid\":\"" + String(wifiSsid) + "\",";
    json += "\"ip\":\"" + WiFi.localIP().toString() + "\",";
    json += "\"connected\":" + String(isConnected ? "true" : "false");
    json += "},";
    json += "\"devices\":[";

    bool first = true;
    for (int i = 0; i < maxLicensedSlots; i++) {
        if (!first) json += ",";
        first = false;
        
        int sNum = licenseSlots[i].slotNum;
        String devId = licenseSlots[i].deviceId;
        String ip = licenseSlots[i].ip;
        String name = licenseSlots[i].name.length() > 0 ? licenseSlots[i].name : ("PisoPhone " + String(sNum));
        bool isBound = (devId.length() > 0);
        
        int rem = -1;
        int bat = 100;
        bool chg = false;
        bool online = false;
        
        if (isBound) {
            rem = getTrackedTimeRemaining(ip, 15000, devId);
            bat = getTrackedBatteryLevel(ip, devId);
            chg = getTrackedChargingState(ip, devId);
            online = (rem >= 0);
        }
        
        json += "{";
        json += "\"slotNum\":" + String(sNum) + ",";
        json += "\"isBound\":" + String(isBound ? "true" : "false") + ",";
        json += "\"id\":\"" + devId + "\",";
        json += "\"ip\":\"" + ip + "\",";
        json += "\"name\":\"" + name + "\",";
        json += "\"time\":" + String(rem) + ",";
        json += "\"online\":" + String(online ? "true" : "false") + ",";
        json += "\"battery\":" + String(bat) + ",";
        json += "\"charging\":" + String(chg ? "true" : "false");
        json += "}";
    }
    json += "]}";
    webServer.send(200, "application/json", json);
}

void handleCheckQualification() {
    if (!checkAuth()) return;
    String p1 = webServer.hasArg("p1") ? webServer.arg("p1") : "";
    String p2 = webServer.hasArg("p2") ? webServer.arg("p2") : "";
    int mins = webServer.hasArg("minutes") ? webServer.arg("minutes").toInt() : 15;
    if (mins <= 0) mins = 1;

    p1.trim();
    p2.trim();

    if (p1.length() == 0 || p2.length() == 0) {
        webServer.send(400, "application/json", "{\"success\":false,\"error\":\"Missing player IP parameters\"}");
        return;
    }
    if (p1 == p2) {
        webServer.send(200, "application/json", "{\"success\":true,\"qualified\":false,\"error\":\"Player 1 and Player 2 cannot be the same device!\"}");
        return;
    }

    String p1Err = "", p2Err = "";
    int p1Sec = getDeviceTimeRemainingSeconds(p1, &p1Err);
    int p2Sec = getDeviceTimeRemainingSeconds(p2, &p2Err);

    int stakeSec = mins * 60;
    bool p1Ok = (p1Sec >= stakeSec);
    bool p2Ok = (p2Sec >= stakeSec);
    bool bothQualified = (p1Ok && p2Ok);

    int p1M = (p1Sec >= 0) ? (p1Sec / 60) : 0;
    int p1S = (p1Sec >= 0) ? (p1Sec % 60) : 0;
    int p2M = (p2Sec >= 0) ? (p2Sec / 60) : 0;
    int p2S = (p2Sec >= 0) ? (p2Sec % 60) : 0;

    char p1FmtBuf[32], p2FmtBuf[32];
    if (p1Sec >= 0) snprintf(p1FmtBuf, sizeof(p1FmtBuf), "%dm %ds", p1M, p1S);
    else strncpy(p1FmtBuf, "Offline", sizeof(p1FmtBuf));

    if (p2Sec >= 0) snprintf(p2FmtBuf, sizeof(p2FmtBuf), "%dm %ds", p2M, p2S);
    else strncpy(p2FmtBuf, "Offline", sizeof(p2FmtBuf));

    char msgBuf[128];
    if (bothQualified) {
        snprintf(msgBuf, sizeof(msgBuf), "Both devices meet the %dm stake requirement.", mins);
    } else if (p1Sec < 0 || p2Sec < 0) {
        strncpy(msgBuf, "One or both devices cannot be reached.", sizeof(msgBuf));
    } else {
        if (!p1Ok && !p2Ok) {
            snprintf(msgBuf, sizeof(msgBuf), "Both players need to add more time to meet the %dm stake.", mins);
        } else if (!p1Ok) {
            snprintf(msgBuf, sizeof(msgBuf), "Player 1 needs at least %dm more active time.", mins - p1M);
        } else {
            snprintf(msgBuf, sizeof(msgBuf), "Player 2 needs at least %dm more active time.", mins - p2M);
        }
    }

    char jsonBuf[512];
    snprintf(jsonBuf, sizeof(jsonBuf),
        "{\"success\":true,\"qualified\":%s,\"stake_minutes\":%d,"
        "\"p1_ip\":\"%s\",\"p1_seconds\":%d,\"p1_formatted\":\"%s\",\"p1_ok\":%s,\"p1_err\":\"%s\","
        "\"p2_ip\":\"%s\",\"p2_seconds\":%d,\"p2_formatted\":\"%s\",\"p2_ok\":%s,\"p2_err\":\"%s\","
        "\"message\":\"%s\"}",
        bothQualified ? "true" : "false", mins,
        p1.c_str(), p1Sec, p1FmtBuf, p1Ok ? "true" : "false", p1Err.c_str(),
        p2.c_str(), p2Sec, p2FmtBuf, p2Ok ? "true" : "false", p2Err.c_str(),
        msgBuf);

    webServer.send(200, "application/json", jsonBuf);
}

void handleIdentify() {
    String reqIp = webServer.hasArg("ip") ? webServer.arg("ip") : webServer.client().remoteIP().toString();
    String devName = getDeviceNameByIpOrId(reqIp, "");
    String json = "{\"device\":\"HARDWARE_kiosk\",\"mac\":\"" + macAddressStr + "\",\"version\":\"3.0\",\"price\":" + String(coinPrice) + ",\"minutes\":" + String(minutesPerCoin);
    if (devName.length() > 0) {
        json += ",\"device_name\":\"" + devName + "\"";
    }
    json += "}";
    webServer.send(200, "application/json", json);
}

void handleHeartbeat() {
    if (webServer.hasArg("device_id") && webServer.hasArg("ts") && webServer.hasArg("sig")) {
        String deviceId = webServer.arg("device_id");
        String reqIp = webServer.hasArg("ip") ? webServer.arg("ip") : "";
        if (reqIp.length() == 0 || reqIp == "127.0.0.1") reqIp = webServer.client().remoteIP().toString();
        String tsStr = webServer.arg("ts");
        String sig = webServer.arg("sig");
        
        if (verifyTelemetryAuth(deviceId, tsStr, sig)) {
            unsigned long long ts = strtoull(tsStr.c_str(), NULL, 10);
            updateMasterTime(ts);
            int timeRem = webServer.hasArg("time") ? webServer.arg("time").toInt() : 0;
            int state = webServer.hasArg("state") ? webServer.arg("state").toInt() : 0;
            int battery = webServer.hasArg("battery") ? webServer.arg("battery").toInt() : 100;
            bool charging = webServer.hasArg("charging") ? (webServer.arg("charging").toInt() == 1 || webServer.arg("charging") == "true") : false;

            updateDeviceTelemetry(deviceId, reqIp, timeRem, state, battery, charging, ts);

            String devName = getDeviceNameByIpOrId(reqIp, deviceId);

            int slotIdx = findSlotIndexForDevice(deviceId, reqIp);
            int daysLeft = -1;
            int expStatus = getSlotExpirationStatus(slotIdx, ts, daysLeft);

            String status = (expStatus == 2) ? "slot_expired" : "ok";
            String json = "{\"status\":\"" + status + "\",\"device\":\"HARDWARE_kiosk\",\"mac\":\"" + macAddressStr + "\",\"price\":" + String(coinPrice) + ",\"minutes\":" + String(minutesPerCoin);
            if (devName.length() > 0) {
                json += ",\"device_name\":\"" + devName + "\"";
            }
            if (slotIdx >= 0) {
                json += ",\"slot_num\":" + String(licenseSlots[slotIdx].slotNum);
                char expBuf[24];
                snprintf(expBuf, sizeof(expBuf), "%llu", (unsigned long long)licenseSlots[slotIdx].expiresAt);
                json += ",\"expires_at\":" + String(expBuf);
            }
            if (expStatus == 2) {
                // HARD LOCKDOWN: Slot is expired or uncredited on ESP32
                json += ",\"slot_expired\":true,\"slot_status\":\"expired\",\"slot_warning\":false";
                json += ",\"message\":\"Device Expired: Please add credits to pair device to ESP32.\"";
            } else if (expStatus == 1) {
                // WARNING: Slot nearing expiration
                json += ",\"slot_expired\":false,\"slot_status\":\"warning\",\"slot_warning\":true";
                json += ",\"slot_warning_days_left\":" + String(daysLeft);
                if (daysLeft == 0) {
                    json += ",\"warning_message\":\"Device slot expiring soon (< 24 hours). Add credits to extend.\"";
                } else {
                    json += ",\"warning_message\":\"Device slot expires in " + String(daysLeft) + " day(s). Add credits to extend.\"";
                }
            } else {
                json += ",\"slot_expired\":false,\"slot_status\":\"active\",\"slot_warning\":false";
            }
            json += "}";
            webServer.send(200, "application/json", json);
            return;
        } else {
            webServer.send(403, "application/json", "{\"error\":\"Forbidden\"}");
            return;
        }
    }
    webServer.send(400, "application/json", "{\"error\":\"Missing auth params\"}");
}

void handlePing() {
    handleHeartbeat();
}

String generateActivationCode(String mac) {
    String expectedSig = calculateHMAC(mac, sharedSecret.c_str());
    String code = expectedSig.substring(0, 12);
    code.toUpperCase();
    return code;
}

void handleActivate() {
    if (webServer.hasArg("code")) {
        String code = webServer.arg("code");
        code.toUpperCase();
        
        String expected = generateActivationCode(macAddressStr);
        if (code == expected) {
            is_licensed = true;
            prefs.begin("kiosk_cfg", false);
            prefs.putBool("licensed", true);
            prefs.end();
            webServer.send(200, "application/json", "{\"success\":true}");
            return;
        }
    }
    webServer.send(403, "application/json", "{\"success\":false,\"error\":\"Invalid activation code\"}");
}

void handleGetConfig() {
    String json = "{\"device\":\"HARDWARE_kiosk\",\"price\":" + String(coinPrice) + ",\"minutes\":" + String(minutesPerCoin) + ",\"relay_pin\":" + String(relayPin) + "}";
    webServer.send(200, "application/json", json);
}

void handleTriggerAndroid() {
    if (!checkAuth()) return;
    String ip = webServer.arg("ip");
    String action = webServer.arg("action");
    if (ip.length() > 0 && action.length() > 0) {
        Serial.printf("[⚡ TRIGGER] Sending %s to %s\n", action.c_str(), ip.c_str());
        sendAuthenticated(ip, targetPort, "/trigger_action", "/challenge", "action=" + action, 800);
        webServer.send(200, "text/plain", "Trigger sent");
    } else {
        webServer.send(400, "text/plain", "Missing IP or action");
    }
}

void handleAnnounce() {
    handleHeartbeat();
}

void handleCrashReport() {
    String body = webServer.arg("plain");
    Serial.printf("\n[⚠️ CRASH REPORT FROM CLIENT]\n%s\n", body.c_str());
    webServer.send(200, "text/plain", "OK");
}

void handleOtaForm() {
    if (!checkAuth()) return;
    String html = R"HTML(
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>HARDWARE Firmware Upgrade</title>
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap" rel="stylesheet">
    <style>
        :root {
            --bg: #f8fafc;
            --sub-bg: #ffffff;
            --text-main: #0f172a;
            --text-muted: #64748b;
            --primary: #4f46e5;
            --primary-hover: #4338ca;
            --border: #e2e8f0;
            --card-shadow: 0 4px 6px -1px rgba(0,0,0,0.05), 0 2px 4px -1px rgba(0,0,0,0.03);
            --input-bg: #fafafa;
        }
        [data-theme="dark"] {
            --bg: #0b1120;
            --sub-bg: #1e293b;
            --text-main: #f8fafc;
            --text-muted: #94a3b8;
            --primary: #6366f1;
            --primary-hover: #4f46e5;
            --border: #334155;
            --card-shadow: 0 4px 6px -1px rgba(0,0,0,0.3);
            --input-bg: #0f172a;
        }
        * { box-sizing: border-box; }
        body { font-family: 'Inter', sans-serif; background: var(--bg); margin: 20px; color: var(--text-main); transition: background-color 0.2s, color 0.2s; }
        .container { background: var(--sub-bg); padding: 28px; border-radius: 16px; max-width: 460px; margin: 20px auto; box-shadow: var(--card-shadow); text-align: center; border: 1px solid var(--border); }
        .top-bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
        h2 { margin: 0; color: var(--text-main); font-size: 20px; font-weight: 700; }
        .status-box { margin: 15px 0; padding: 12px; border-radius: 8px; font-size: 14px; display: none; line-height: 1.4; text-align: left; }
        .error { background: #ffebee; border: 1px solid #ffcdd2; color: #c62828; }
        .success { background: #e8f5e9; border: 1px solid #c8e6c9; color: #1b5e20; }
        .info { background: #e3f2fd; border: 1px solid #bbdefb; color: #0d47a1; }
        [data-theme="dark"] .error { background: #450a0a; border-color: #7f1d1d; color: #fca5a5; }
        [data-theme="dark"] .success { background: #052e16; border-color: #14532d; color: #86efac; }
        [data-theme="dark"] .info { background: #082f49; border-color: #075985; color: #7dd3fc; }
        .progress-container { width: 100%; background: var(--border); border-radius: 10px; height: 18px; margin: 15px 0; overflow: hidden; display: none; }
        .progress-bar { height: 100%; width: 0%; background: #10b981; transition: width 0.1s ease-in-out; }
        input[type=file] { margin: 15px 0; padding: 12px; width: 100%; box-sizing: border-box; border: 1px solid var(--border); border-radius: 8px; background: var(--input-bg); color: var(--text-main); cursor: pointer; }
        button { padding: 12px 20px; background: var(--primary); color: white; border: none; border-radius: 8px; font-weight: 600; width: 100%; cursor: pointer; font-size: 15px; transition: background 0.2s; }
        button:hover { background: var(--primary-hover); }
        button:disabled { background: #64748b; cursor: not-allowed; opacity: 0.6; }
        .theme-btn { background: transparent; border: 1px solid var(--border); color: var(--text-main); padding: 4px 10px; border-radius: 6px; font-size: 12px; cursor: pointer; width: auto; font-weight: 500; }
        .theme-btn:hover { background: var(--border); }
        a { display: inline-block; margin-top: 18px; color: var(--primary); text-decoration: none; font-size: 13px; font-weight: 600; }
        a:hover { text-decoration: underline; }
    </style>
</head>
<body>
    <div class="container">
        <div class="top-bar">
            <h2>📲 Firmware OTA Update</h2>
            <button type="button" class="theme-btn" id="theme_toggle_btn" onclick="toggleTheme()">🌙 Dark</button>
        </div>
        <!-- MAC display removed -->
        <p style="font-size:13px; color:var(--text-muted); margin-bottom: 20px; line-height: 1.5;">
            Select a compiled <b>.bin</b> firmware file (from PlatformIO <code>firmware.bin</code> or Arduino IDE) to update your controller wirelessly.
        </p>
        
        <input type="file" id="file_input" accept=".bin">
        
        <div class="progress-container" id="progress_wrapper">
            <div class="progress-bar" id="progress_bar"></div>
        </div>
        
        <div class="status-box" id="status_message"></div>
        
        <button type="button" id="upload_button" onclick="startUpdate()">Upload & Flash Firmware</button>
        <br>
        <a href="/">&larr; Back to Kiosk Dashboard</a>
    </div>

    <script>
    (function() {
        const savedTheme = localStorage.getItem('kiosk_theme') || 'light';
        document.documentElement.setAttribute('data-theme', savedTheme);
    })();

    window.toggleTheme = function() {
        const cur = document.documentElement.getAttribute('data-theme') || 'light';
        const next = cur === 'dark' ? 'light' : 'dark';
        document.documentElement.setAttribute('data-theme', next);
        localStorage.setItem('kiosk_theme', next);
        updateThemeButton();
    };

    function updateThemeButton() {
        const btn = document.getElementById('theme_toggle_btn');
        if (btn) {
            const cur = document.documentElement.getAttribute('data-theme') || 'light';
            btn.innerHTML = cur === 'dark' ? '☀️ Light' : '🌙 Dark';
        }
    }
    document.addEventListener('DOMContentLoaded', updateThemeButton);

    window.startUpdate = function() {
        const fileInput = document.getElementById('file_input');
        const uploadBtn = document.getElementById('upload_button');
        const progressWrapper = document.getElementById('progress_wrapper');
        const progressBar = document.getElementById('progress_bar');
        const statusBox = document.getElementById('status_message');
        
        if (!fileInput.files || fileInput.files.length === 0) {
            showStatus('Please choose a .bin file to upload.', 'error');
            return;
        }
        
        const file = fileInput.files[0];
        
        // Prepare form data
        const formData = new FormData();
        formData.append('update', file, file.name);
        
        // Disable UI during flashing
        fileInput.disabled = true;
        uploadBtn.disabled = true;
        progressWrapper.style.display = 'block';
        progressBar.style.width = '0%';
        progressBar.style.background = '#10b981';
        
        showStatus('Uploading firmware binary (' + (file.size/1024).toFixed(1) + ' KB)...', 'info');
        
        const xhr = new XMLHttpRequest();
        xhr.open('POST', '/update', true);
        
        // Track Upload Progress
        xhr.upload.addEventListener('progress', function(e) {
            if (e.lengthComputable) {
                const percent = (e.loaded / e.total) * 100;
                progressBar.style.width = percent + '%';
                showStatus('Uploading: ' + Math.round(percent) + '% (' + (e.loaded/1024).toFixed(0) + ' KB / ' + (e.total/1024).toFixed(0) + ' KB)...', 'info');
                if (percent >= 99) {
                    showStatus('Flashing binary to HARDWARE partition... Please do not power off.', 'info');
                }
            }
        });
        
        // Handle response
        xhr.onload = function() {
            if (xhr.status === 200) {
                progressBar.style.width = '100%';
                progressBar.style.background = '#10b981';
                showStatus('<b>✅ SUCCESS: Firmware Updated!</b><br>Rebooting HARDWARE Controller now... returning to dashboard in 5 seconds.', 'success');
                setTimeout(function() {
                    window.location.href = '/';
                }, 5000);
            } else {
                progressBar.style.background = '#ef4444';
                showStatus('<b>❌ FAILED:</b> ' + (xhr.responseText || 'Flash error occurred'), 'error');
                fileInput.disabled = false;
                uploadBtn.disabled = false;
            }
        };
        
        xhr.onerror = function() {
            progressBar.style.background = '#ef4444';
            showStatus('<b>❌ Connection Error:</b> Network disconnected or connection was lost during upload.', 'error');
            fileInput.disabled = false;
            uploadBtn.disabled = false;
        };
        
        xhr.send(formData);
    }
    
    window.showStatus = function(text, type) {
        const box = document.getElementById('status_message');
        box.style.display = 'block';
        box.className = 'status-box ' + type;
        box.innerHTML = text;
    }
    </script>
</body>
</html>
)HTML";
    if (macAddressStr.length() == 0) {
        uint8_t mac[6];
        esp_read_mac(mac, ESP_MAC_WIFI_STA);
        char macBuf[18];
        snprintf(macBuf, sizeof(macBuf), "%02X:%02X:%02X:%02X:%02X:%02X", mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
        macAddressStr = String(macBuf);
    }
    html.replace("{MAC_ADDRESS}", macAddressStr);
    webServer.send(200, "text/html; charset=utf-8", html);
}


// ============================================================================
// PORT 81 WEBSOCKET CONNECTION & HANDSHAKE SUPERVISOR
// ============================================================================

String extractUrlParam(String url, String param) {
    int start = url.indexOf(param);
    if (start == -1) return "";
    start += param.length();
    int end = url.indexOf('&', start);
    if (end == -1) {
        end = url.indexOf(' ', start);
    }
    if (end == -1) {
        end = url.length();
    }
    return url.substring(start, end);
}

void processWebSocketServer() {
    if (wsServer.hasClient()) {
        WiFiClient newClient = wsServer.available();
        if (newClient) {
            newClient.setTimeout(100);
            
            String request = "";
            String secKey = "";
            bool isUpgrade = false;
            
            while (newClient.connected()) {
                String line = newClient.readStringUntil('\n');
                if (request.length() == 0) request = line;
                line.trim();
                if (line.startsWith("Sec-WebSocket-Key:") || line.startsWith("sec-websocket-key:")) {
                    secKey = line.substring(18);
                    secKey.trim();
                }
                if (line.equalsIgnoreCase("Upgrade: websocket")) {
                    isUpgrade = true;
                }
                if (line.length() == 0) break;
            }
            
            String reqDeviceId = extractUrlParam(request, "device_id=");
            String tsStr = extractUrlParam(request, "ts=");
            String sig = extractUrlParam(request, "sig=");
            
            if (reqDeviceId.length() == 0 || tsStr.length() == 0 || sig.length() == 0 || secKey.length() == 0) {
                newClient.print("HTTP/1.1 400 Bad Request\r\n\r\nMissing auth/WebSocket headers");
                newClient.stop();
                return;
            }
            
            // 1. Verify HMAC Signature
            if (sharedSecret.length() > 0) {
                String expectedSig = calculateHMAC(reqDeviceId + ":" + tsStr, sharedSecret);
                if (!sig.equalsIgnoreCase(expectedSig)) {
                    Serial.printf("[-] WS Auth Failed for %s: Signature Mismatch\n", reqDeviceId.c_str());
                    newClient.print("HTTP/1.1 403 Forbidden\r\n\r\nInvalid Signature");
                    newClient.stop();
                    return;
                }
            }

            // 1b. Verify Replay Protection
            unsigned long long ts = strtoull(tsStr.c_str(), NULL, 10);
            if (!checkReplayProtection(reqDeviceId, ts)) {
                Serial.printf("[-] WS Auth Failed for %s: Replay Detected\n", reqDeviceId.c_str());
                newClient.print("HTTP/1.1 403 Forbidden\r\n\r\nReplay Detected");
                newClient.stop();
                return;
            }

            // 1c. Verify Slot Expiration & Lockdown
            String clientIp = newClient.remoteIP().toString();
            int wsSlotIdx = findSlotIndexForDevice(reqDeviceId, clientIp);
            int wsDaysLeft = -1;
            int wsExpStatus = getSlotExpirationStatus(wsSlotIdx, ts, wsDaysLeft);
            if (wsExpStatus == 2) {
                Serial.printf("[-] WS Mutex Rejected for %s: Slot Expired / Lockdown Active (Slot #%d)\n", 
                    reqDeviceId.c_str(), (wsSlotIdx >= 0) ? licenseSlots[wsSlotIdx].slotNum : 0);
                newClient.print("HTTP/1.1 423 Locked\r\n\r\nSLOT_EXPIRED");
                newClient.stop();
                return;
            }
            
            // 2. Hardware Mutex Check (Single-Client Lock)
            unsigned long now = millis();
            if (armedIp.length() > 0 && armedIp != reqDeviceId && now < armedUntil && (sessionStartTime == 0 || (now - sessionStartTime < MAX_SESSION_DURATION))) {
                Serial.printf("[-] WS Mutex Rejected for %s: Slot BUSY with %s\n", reqDeviceId.c_str(), armedIp.c_str());
                newClient.print("HTTP/1.1 409 Conflict\r\n\r\nSLOT_BUSY");
                newClient.stop();
                return;
            }
            
            // Mutex passed, slot acquired. Save the nonce.
            recordDeviceNonce(reqDeviceId, ts);
            
            // 3. Complete RFC6455 Handshake
            String acceptKey = computeSecWebSocketAccept(secKey);
            String response = "HTTP/1.1 101 Switching Protocols\r\n";
            response += "Upgrade: websocket\r\n";
            response += "Connection: Upgrade\r\n";
            response += "Sec-WebSocket-Accept: " + acceptKey + "\r\n\r\n";
            
            newClient.print(response);
            newClient.flush();
            newClient.setNoDelay(true);
            
            if (isWsConnected && wsClient.connected()) {
                wsClient.stop();
            }
            wsClient = newClient;
            isWsConnected = true;
            
            if (armedIp != reqDeviceId || sessionStartTime == 0) {
                sessionStartTime = now;
            }
            armedIp = reqDeviceId;
            lastArmedDeviceId = reqDeviceId;
            lastArmedIp = getIpFromDeviceId(reqDeviceId);
            lastArmedTimeMs = now;
            armedUntil = now + ARM_TTL;
            pendingWsGracefulClose = false;
            
            Serial.printf("[⚡ WS Port 81] WebSocket ARMED securely for %s (TTL: %lu s)\n", reqDeviceId.c_str(), ARM_TTL / 1000);
            sendWsText(wsClient, "{\"event\":\"ARMED\"}");
        }
    }
    
    // Process active WebSocket client frames or disconnection
    if (isWsConnected) {
        if (!wsClient.connected()) {
            Serial.printf("[*] WS Client %s disconnected. Slot released.\n", armedIp.c_str());
            isWsConnected = false;
            if (armedIp.length() > 0) {
                lastArmedDeviceId = armedIp;
                lastArmedIp = getIpFromDeviceId(armedIp);
                lastArmedTimeMs = millis();
            }
            armedIp = "";
            armedUntil = 0;
            sessionStartTime = 0;
            return;
        }
        
        if (wsClient.available()) {
            String frameText = readWsText(wsClient);
            if (frameText == "DONE" || frameText == "CLOSE") {
                noInterrupts();
                int currentPulses = isrUniversalPulseCount;
                unsigned long lastPulse = isrLastPulseTimeMs;
                interrupts();

                // If coin pulses are actively in progress or arrived in the last 600ms, hold graceful close to deliver credit!
                if (currentPulses > 0 || (millis() - lastPulse < 600 && lastPulse > 0)) {
                    Serial.printf("[⚡ WS Port 81] 'DONE' received while coin pulses are active (%d pulses). Holding graceful close to finalize credit...\n", currentPulses);
                    pendingWsGracefulClose = true;
                    pendingWsGracefulCloseUntil = millis() + 2000;
                    armedUntil = millis() + 3000; // Extend temporary guard so pulse train completes safely
                } else {
                    wsClient.stop();
                    isWsConnected = false;
                    if (armedIp.length() > 0) {
                        lastArmedDeviceId = armedIp;
                        lastArmedIp = getIpFromDeviceId(armedIp);
                        lastArmedTimeMs = millis();
                    }
                    armedIp = "";
                    armedUntil = 0;
                    sessionStartTime = 0;
                    return;
                }
            }
        }
        
        // Check session TTL expiration or pending graceful close timeout
        unsigned long now = millis();
        if (pendingWsGracefulClose && now >= pendingWsGracefulCloseUntil) {
            Serial.println("[*] Pending graceful close timed out after coin train window. Slot released.");
            pendingWsGracefulClose = false;
            wsClient.stop();
            isWsConnected = false;
            if (armedIp.length() > 0) {
                lastArmedDeviceId = armedIp;
                lastArmedIp = getIpFromDeviceId(armedIp);
                lastArmedTimeMs = millis();
            }
            armedIp = "";
            armedUntil = 0;
            sessionStartTime = 0;
            return;
        }

        if (now >= armedUntil || (sessionStartTime > 0 && (now - sessionStartTime >= MAX_SESSION_DURATION))) {
            Serial.printf("[*] WS Session TTL expired for %s. Slot released.\n", armedIp.c_str());
            sendWsText(wsClient, "{\"event\":\"TIMEOUT\"}");
            wsClient.stop();
            isWsConnected = false;
            if (armedIp.length() > 0) {
                lastArmedDeviceId = armedIp;
                lastArmedIp = getIpFromDeviceId(armedIp);
                lastArmedTimeMs = millis();
            }
            armedIp = "";
            armedUntil = 0;
            sessionStartTime = 0;
        }
    }
}

// ============================================================================
// UDP BROADCAST DISCOVERY SERVICE (Port 8888)
// Listens for UDP broadcasts from Android Kiosk devices and responds
// with the ESP32 Master box identity, IP, MAC address, and configuration.
// ============================================================================
void sendUdpDiscoveryResponse(IPAddress targetIp, uint16_t targetPort) {
    if (WiFi.status() != WL_CONNECTED) return;

    String resp = "{\"type\":\"PISOPHONE_ESP32_RESPONSE\","
                  "\"device\":\"PISOPHONE_MASTER\","
                  "\"mac\":\"" + macAddressStr + "\","
                  "\"ip\":\"" + WiFi.localIP().toString() + "\","
                  "\"port\":80,"
                  "\"ws_port\":81,"
                  "\"device_name\":\"PisoPhone Master\","
                  "\"price\":" + String(coinPrice, 2) + ","
                  "\"minutes\":" + String(minutesPerCoin) + ","
                  "\"slots\":" + String(maxLicensedSlots) + ","
                  "\"uptime\":" + String(millis() / 1000) + "}";

    // 1. Direct unicast response to client
    if (targetIp != IPAddress(0, 0, 0, 0) && targetPort > 0) {
        udpServer.beginPacket(targetIp, targetPort);
        udpServer.write((const uint8_t*)resp.c_str(), resp.length());
        udpServer.endPacket();
    }

    // 2. Local broadcast on discovery port 8888 (handles clients listening on fixed port)
    IPAddress bcast(255, 255, 255, 255);
    udpServer.beginPacket(bcast, UDP_DISCOVERY_PORT);
    udpServer.write((const uint8_t*)resp.c_str(), resp.length());
    udpServer.endPacket();
}

void processUdpDiscovery() {
    if (WiFi.status() != WL_CONNECTED) return;

    int packetSize = udpServer.parsePacket();
    if (packetSize > 0) {
        char packetBuffer[512];
        int len = udpServer.read(packetBuffer, sizeof(packetBuffer) - 1);
        if (len > 0) {
            packetBuffer[len] = '\0';
            String msg = String(packetBuffer);
            msg.trim();

            // Canonical UDP discovery protocol
            if (msg.indexOf("PISOPHONE_DISCOVER") >= 0) {
                IPAddress remoteIp = udpServer.remoteIP();
                uint16_t remotePort = udpServer.remotePort();
                Serial.printf("[⚡ UDP Discovery] Valid probe received from %s:%d. Responding...\n",
                              remoteIp.toString().c_str(), remotePort);
                sendUdpDiscoveryResponse(remoteIp, remotePort);
            }
        }
    }

    // Periodic announcement beacon (every 10 seconds while connected to WiFi)
    static unsigned long lastUdpAnnounceMs = 0;
    if (millis() - lastUdpAnnounceMs > 10000 || lastUdpAnnounceMs == 0) {
        lastUdpAnnounceMs = millis();
        sendUdpDiscoveryResponse(IPAddress(255, 255, 255, 255), UDP_DISCOVERY_PORT);
    }
}

// ============================================================================
// SERIAL CLI (For USB Console Testing)
// ============================================================================
void processSerialCli() {
    if (!Serial.available()) return;
    String line = Serial.readStringUntil('\n');
    line.trim();
    if (line.length() == 0) return;

    if (line.equalsIgnoreCase("coin")) {
        triggerCoinEvent();
    } else if (line.startsWith("ucoin ") || line.startsWith("ucoin")) {
        int firstSpace = line.indexOf(' ');
        int pulses = (firstSpace != -1) ? line.substring(firstSpace + 1).toInt() : 1;
        if (pulses <= 0) pulses = 1;
        triggerUniversalCoinEvent(pulses);
    } else if (line.startsWith("add ") || line.startsWith("add")) {
        int firstSpace = line.indexOf(' ');
        if (firstSpace != -1) {
            int minutes = line.substring(firstSpace + 1).toInt();
            sendAddTime(minutes, "ALL");
        }
    } else if (line.equalsIgnoreCase("help")) {
        Serial.println("\nCommands: coin | ucoin <1|5|10|20> | add <minutes> | help");
    }
}

// ============================================================================
// FACTORY RESET & RECOVERY SUBSYSTEM
// ============================================================================
void factoryResetDefaults() {
    Serial.println("\n=======================================================");
    Serial.println("[⚠️ FACTORY RESET] Restoring all settings to defaults...");
    Serial.println("=======================================================");
    
    prefs.begin("kiosk_cfg", false);
    prefs.clear();
    prefs.end();

    wifiSsid          = DEFAULT_SSID;
    wifiPass          = DEFAULT_PASS;
    coinPin           = DEFAULT_COIN_PIN;
    universalCoinPin  = DEFAULT_UNIVERSAL_COIN_PIN;
    ledPin            = DEFAULT_LED_PIN;
    ledActiveLow      = DEFAULT_LED_ACTIVE_LOW;
    relayPin          = DEFAULT_RELAY_PIN;
    androidIps        = "";
    targetPort        = DEFAULT_PORT;
    webPassword       = DEFAULT_ADMIN_PW;
    sharedSecret      = MASTER_CRYPTO_SECRET;
    coinPrice         = DEFAULT_PRICE;
    minutesPerCoin    = DEFAULT_MINUTES;
    lockoutDebounceMs = DEFAULT_DEBOUNCE;
    p1Ip              = "";
    p2Ip              = "";
    matchMinutes      = 15;
    totalCoinsLifetime = 0;
    totalCoinsSession  = 0;
    totalEarningsLifetime = 0.0f;
    totalEarningsSession  = 0.0f;
    lastSavedTotalCoins = 0;
    lastSavedTotalEarnings = 0.0f;

    // Visual confirmation on LED: 10 rapid strobe flashes
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

// ============================================================================
// HARDWARE RESET PIN SUPERVISOR (GPIO 2 -> GND for 5 seconds = Factory Reset)
// ============================================================================
void processHardwareResetPin() {
    if (digitalRead(HARDWARE_RESET_PIN) == LOW) {
        if (resetPinLowStart == 0) {
            resetPinLowStart = millis();
            Serial.println("[⚠️] GPIO 2 connected to GND. Hold for 5 seconds to factory reset...");
        } else if (millis() - resetPinLowStart >= 5000) {
            Serial.println("\n[⚠️ RESET] GPIO 2 held to GND for > 5 seconds! Triggering Factory Reset...");
            factoryResetDefaults();
            delay(1000);
            ESP.restart();
        }
    } else {
        if (resetPinLowStart != 0) {
            Serial.println("[*] GPIO 2 released before 5 seconds. Reset cancelled.");
            resetPinLowStart = 0;
        }
    }
}

// ============================================================================
// SETUP & INITIALIZATION
// ============================================================================
void setup() {
    Serial.begin(115200);
    unsigned long start = millis();
    while (!Serial && (millis() - start < 2500));
    delay(300);

    Serial.println("\n--- HARDWARE-C3 Master Kiosk Controller ---");

    // Load NVS Configuration
    prefs.begin("kiosk_cfg", false);
    is_licensed       = prefs.getBool("licensed", false);
    wifiSsid          = prefs.getString("wifi_ssid", wifiSsid);
    wifiPass          = prefs.getString("wifi_pass", wifiPass);
    coinPin           = prefs.getInt("coin_pin", coinPin);
    universalCoinPin  = prefs.getInt("u_coin_pin", universalCoinPin);
    ledPin            = prefs.getInt("led_pin", ledPin);
    ledActiveLow      = prefs.getBool("led_active_low", DEFAULT_LED_ACTIVE_LOW);
    relayPin          = prefs.getInt("relay_pin", relayPin);
    androidIps        = prefs.getString("ips", androidIps);
    // Sanitize and purge any corrupted legacy entries on boot
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
    loadSlotLicenses();
    loadCreditVault();
    targetPort        = prefs.getInt("port", targetPort);
    if (targetPort <= 0) targetPort = 8080;
    lastWifiCheckTime = millis();
    webPassword       = prefs.getString("admin_pw", webPassword);
    coinPrice         = prefs.getFloat("price", coinPrice);
    minutesPerCoin    = prefs.getInt("minutes", minutesPerCoin);
    lockoutDebounceMs = prefs.getInt("debounce", lockoutDebounceMs);
    relayActiveLow    = prefs.getBool("relay_active_low", false);
    relayMode         = prefs.getInt("relay_mode", 1);
    sharedSecret      = prefs.getString("shared_secret", sharedSecret);
    p1Ip              = prefs.getString("p1", p1Ip);
    p2Ip              = prefs.getString("p2", p2Ip);
    matchMinutes      = prefs.getInt("match", matchMinutes);
    totalCoinsLifetime = prefs.getULong("total_coins", 0);
    if (prefs.isKey("total_earnings")) {
        totalEarningsLifetime = prefs.getFloat("total_earnings", 0.0f);
    } else {
        totalEarningsLifetime = (float)totalCoinsLifetime * coinPrice;
    }
    lastSavedTotalCoins = totalCoinsLifetime;
    lastSavedTotalEarnings = totalEarningsLifetime;
    totalCoinsSession = 0;
    totalEarningsSession = 0.0f;
    prefs.end();

    // Initialize Dynamic Hardware Pins & Hardware Reset Pin (GPIO 2)
    pinMode(coinPin, INPUT_PULLUP);
    pinMode(universalCoinPin, INPUT_PULLUP);
    attachInterrupt(digitalPinToInterrupt(universalCoinPin), universalCoinIsr, FALLING);
    pinMode(HARDWARE_RESET_PIN, INPUT_PULLUP);
    setLedHardware(false);
    // Initialize relay hardware (Powered ON if Mode 0, Standby if Mode 1)
    setRelayHardware(relayMode == 0);
    Serial.printf("[+] Hardware Pins bound: Beam Coin Pin = GPIO %d, Universal Multi-Coin Pin = GPIO %d (ISR active), LED Pin = GPIO %d, Relay Pin = GPIO %d (ActiveLow=%s, Mode=%d), Reset Pin = GPIO %d\n", coinPin, universalCoinPin, ledPin, relayPin, relayActiveLow ? "true" : "false", relayMode, HARDWARE_RESET_PIN);

    // Immediately read hardware factory MAC address from eFuse
    uint8_t macInit[6];
    esp_read_mac(macInit, ESP_MAC_WIFI_STA);
    char macBufInit[18];
    snprintf(macBufInit, sizeof(macBufInit), "%02X:%02X:%02X:%02X:%02X:%02X", macInit[0], macInit[1], macInit[2], macInit[3], macInit[4], macInit[5]);
    macAddressStr = String(macBufInit);
    Serial.printf("[+] Hardware MAC Address: %s\n", macAddressStr.c_str());

    WiFi.persistent(false);
    WiFi.disconnect(true, true);
    delay(100);
    WiFi.mode(WIFI_STA);
    WiFi.setTxPower(WIFI_POWER_8_5dBm);
    esp_wifi_set_max_tx_power(34);
    esp_wifi_set_ps(WIFI_PS_NONE);

    WiFi.begin(wifiSsid.c_str(), wifiPass.c_str());

    Serial.printf("[*] Connecting to Wi-Fi \"%s\"", wifiSsid.c_str());
    currentLedState = LED_STATE_CONNECTING;
    unsigned long wifiConnectStart = millis();
    const unsigned long WIFI_BOOT_TIMEOUT_MS = 10000; // 10 second boot connection attempt window

    while (WiFi.status() != WL_CONNECTED && (millis() - wifiConnectStart < WIFI_BOOT_TIMEOUT_MS)) {
        delay(20);
        processLedBlink(); // Rapid flash while connecting
        if ((millis() - wifiConnectStart) % 500 < 20) {
            Serial.print(".");
        }
    }

    if (WiFi.status() == WL_CONNECTED) {
        currentLedState = LED_STATE_CONNECTED;
        setLedHardware(true);
        Serial.printf("\n[+] HARDWARE Online at %s\n", WiFi.localIP().toString().c_str());
    } else {
        currentLedState = LED_STATE_FAILED;
        setLedHardware(false);
        Serial.printf("\n[-] Wi-Fi Connection to \"%s\" Failed or Timed Out.\n", wifiSsid.c_str());
        Serial.println("[-] Waiting for Wi-Fi hotspot to become available...");
    }

    // Initialize mDNS Responder ("kioskmanager.local")
    uint8_t mac[6];
    WiFi.macAddress(mac);
    char macBuf[18];
    snprintf(macBuf, sizeof(macBuf), "%02X:%02X:%02X:%02X:%02X:%02X", mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
    macAddressStr = String(macBuf);

    if (MDNS.begin("kioskmanager")) {
        MDNS.addService("kioskmanager", "tcp", 80);
        MDNS.addService("http", "tcp", 80);
        Serial.println("[+] mDNS service active at http://kioskmanager.local");
    } else {
        Serial.println("[-] Error setting up mDNS responder!");
    }

    // Initialize Authenticated Request Worker Queue & FreeRTOS Supervisor Task (8KB stack)
    authQueue = xQueueCreate(16, sizeof(AuthRequest));
    xTaskCreate(authWorkerTask, "AuthWorker", 8192, NULL, 1, NULL);

    // Port 80: HTTP Portal & API routes
    webServer.on("/", HTTP_GET, handlePortalRoot);
    webServer.on("/install", HTTP_GET, handlePortalRoot);
    webServer.on("/provision", HTTP_GET, handlePortalRoot);
    webServer.on("/logout", HTTP_GET, handleLogout);
    webServer.on("/save", HTTP_POST, handleSave);
    webServer.on("/reboot", HTTP_POST, handleReboot);
    webServer.on("/factory_reset", HTTP_POST, handleFactoryReset);
    webServer.on("/add_time", HTTP_POST, handleAddTime);
    webServer.on("/one_vs_one", HTTP_POST, handleOneVsOne);
    webServer.on("/insert_coin", HTTP_POST, handleInsertCoin);
    webServer.on("/insert_ucoin", HTTP_POST, handleInsertUniversalCoin);
    webServer.on("/reset_vault", HTTP_POST, handleResetVault);
    webServer.on("/api/status", HTTP_GET, handleApiStatus);
    webServer.on("/check_qualification", HTTP_GET, handleCheckQualification);
    webServer.on("/identify", HTTP_GET, handleIdentify);
    webServer.on("/ping", HTTP_GET, handlePing);
    webServer.on("/heartbeat", HTTP_GET, handleHeartbeat);
    webServer.on("/activate", HTTP_POST, handleActivate);
    webServer.on("/get_config", HTTP_GET, handleGetConfig);
    webServer.on("/config", HTTP_GET, handleGetConfig);
    webServer.on("/announce", HTTP_GET, handleAnnounce);
    webServer.on("/trigger_android", HTTP_GET, handleTriggerAndroid);
    webServer.on("/crash_report", HTTP_POST, handleCrashReport);
    webServer.on("/api/slots", HTTP_GET, handleApiSlots);
    webServer.on("/api/slots/pair", HTTP_POST, handleApiSlotPair);
    webServer.on("/api/slots/unpair", HTTP_POST, handleApiSlotUnpair);
    webServer.on("/api/slots/apply_token", HTTP_POST, handleApiSlotApplyToken);
    webServer.on("/api/slots/cloud_sync", HTTP_POST, handleApiSlotCloudSync);
    webServer.on("/api/credits/emulate_payment", HTTP_ANY, handleApiCreditsEmulatePayment);
    webServer.on("/api/credits/allocate", HTTP_ANY, handleApiCreditsAllocate);
    webServer.on("/api/credits/status", HTTP_GET, handleApiCreditsStatus);
    webServer.on("/api/relay", HTTP_ANY, []() {
        if (webServer.hasArg("invert")) {
            relayActiveLow = (webServer.arg("invert") == "1" || webServer.arg("invert") == "true");
            prefs.begin("kiosk_cfg", false);
            prefs.putBool("relay_active_low", relayActiveLow);
            prefs.end();
        }
        if (webServer.hasArg("mode")) {
            relayMode = webServer.arg("mode").toInt();
            prefs.begin("kiosk_cfg", false);
            prefs.putInt("relay_mode", relayMode);
            prefs.end();
        }
        if (webServer.hasArg("state")) {
            bool state = (webServer.arg("state") == "1" || webServer.arg("state") == "true");
            setRelayHardware(state);
            webServer.send(200, "application/json", "{\"status\":\"ok\",\"relay_pin\":" + String(relayPin) + ",\"state\":" + String(state ? 1 : 0) + ",\"active_low\":" + String(relayActiveLow ? 1 : 0) + ",\"mode\":" + String(relayMode) + "}");
            return;
        }
        webServer.send(200, "application/json", "{\"status\":\"ok\",\"relay_pin\":" + String(relayPin) + ",\"active_low\":" + String(relayActiveLow ? 1 : 0) + ",\"mode\":" + String(relayMode) + "}");
    });
    
    // Port 80: Web OTA Firmware Update Endpoints
    webServer.on("/update", HTTP_GET, handleOtaForm);
    webServer.on("/update", HTTP_POST, []() {
        if (!checkAuth()) return;
        webServer.sendHeader("Connection", "close");
        if (!otaIsValidBinary || Update.hasError() || !otaUpdateSuccess) {
            String errStr = otaErrorMsg.length() > 0 ? otaErrorMsg : ("Flash write failed (Error Code " + String(Update.getError()) + ")");
            webServer.send(400, "text/plain", errStr);
        } else {
            webServer.send(200, "text/plain", "SUCCESS");
            delay(1000);
            ESP.restart();
        }
    }, []() {
        if (!checkAuth()) return;
        HTTPUpload& upload = webServer.upload();
        
        if (upload.status == UPLOAD_FILE_START) {
            otaUpdateSuccess = false;
            otaFirstChunkReceived = false;
            otaIsValidBinary = true;
            otaErrorMsg = "";
            Update.clearError();
            
            Serial.printf("[OTA] Starting firmware flash: %s\n", upload.filename.c_str());
            
            // Start flash update session
            if (!Update.begin(UPDATE_SIZE_UNKNOWN, U_FLASH)) {
                otaIsValidBinary = false;
                otaErrorMsg = "Failed to begin flash partition write (Error: " + String(Update.getError()) + ")";
                Serial.printf("[OTA] Error: %s\n", otaErrorMsg.c_str());
            }
        } else if (upload.status == UPLOAD_FILE_WRITE) {
            if (!otaIsValidBinary) return;

            if (upload.currentSize > 0) {
                if (Update.write(upload.buf, upload.currentSize) != upload.currentSize) {
                    otaIsValidBinary = false;
                    otaErrorMsg = "Flash write failed at offset " + String(Update.progress()) + " (Error: " + String(Update.getError()) + ")";
                    Serial.printf("[OTA] Error: %s\n", otaErrorMsg.c_str());
                } else {
                    // Print a dot for every write buffer chunk to monitor progress
                    Serial.print(".");
                }
            }
        } else if (upload.status == UPLOAD_FILE_END) {
            Serial.println(); // newline after progress dots
            if (otaIsValidBinary) {
                if (Update.end(true)) {
                    Serial.printf("[OTA] Firmware flashing verified & completed successfully: %u bytes\n", upload.totalSize);
                    otaUpdateSuccess = true;
                } else {
                    otaIsValidBinary = false;
                    otaErrorMsg = "Firmware verification failed after write (Error: " + String(Update.getError()) + ")";
                    Serial.printf("[OTA] Error: %s\n", otaErrorMsg.c_str());
                }
            } else {
                Update.abort();
            }
        } else if (upload.status == UPLOAD_FILE_ABORTED) {
            Update.abort();
            otaIsValidBinary = false;
            otaErrorMsg = "Upload connection was aborted prematurely.";
            Serial.println("[OTA] Upload aborted by client.");
        }
    });
    webServer.begin();

    // Port 81: Real-time WebSocket Server
    wsServer.begin();

    // Port 8888: UDP Broadcast Discovery Service
    udpServer.begin(UDP_DISCOVERY_PORT);
    Serial.printf("[!] Port %d: UDP Discovery Server active\n", UDP_DISCOVERY_PORT);

    if (WiFi.status() == WL_CONNECTED) {
        Serial.printf("[!] Port 80: Management at http://%s:80\n", WiFi.localIP().toString().c_str());
        Serial.printf("[!] Port 81: WebSocket at ws://%s:81/ws\n\n", WiFi.localIP().toString().c_str());
        // Broadcast initial arrival on network
        sendUdpDiscoveryResponse(IPAddress(255, 255, 255, 255), UDP_DISCOVERY_PORT);
    } else {
        Serial.printf("[!] Wi-Fi disconnected. Waiting for hotspot '%s' to become available...\n", wifiSsid.c_str());
    }
    
    // Initialize Wi-Fi watchdog base time
    lastWifiCheckTime = millis();
}

// ============================================================================
// MAIN EVENT LOOP (100% Non-Blocking)
// ============================================================================
void loop() {
    // 0. Process Debounced Hardware-Conservative NVS Revenue Persistence
    processRevenuePersistence();

    // 0. Process Hardware Fallback Reset Pin (GPIO 2 -> GND for 5 seconds)
    processHardwareResetPin();

    // 1. Process Hardware Coin Detectors (GPIO 4 Beam Sensor + GPIO 3 Universal Pulse Sensor)
    processCoinDetector();
    processUniversalCoinDetector();

    // 2. Process Coin Slot Power/Enable Relay (Synchronized with Arming / Insert Coin)
    processRelayState();

    // 3. Handle Port 80 HTTP Requests
    webServer.handleClient();
    yield();
    
    // 4. Handle Port 81 WebSocket Client & Frames
    processWebSocketServer();
    
    // 5. Handle Port 8888 UDP Broadcast Discovery
    processUdpDiscovery();
    
    // 6. Handle USB Serial CLI commands
    processSerialCli();
    
    // 7. Robust Non-Blocking Wi-Fi Reconnection Watchdog & LED Status Sync
    if (WiFi.status() == WL_CONNECTED) {
        currentLedState = LED_STATE_CONNECTED;
    } else {
        // Not connected. Check how long we've been trying to connect in this cycle
        if (millis() - lastWifiCheckTime < 20000) {
            currentLedState = LED_STATE_CONNECTING;
        } else {
            currentLedState = LED_STATE_FAILED;
            
            // Trigger a fresh connection attempt every 30 seconds if SSID is set
            if (wifiSsid.length() > 0 && (millis() - lastWifiCheckTime > 30000)) {
                lastWifiCheckTime = millis();
                Serial.printf("\n[📶 WATCHDOG] Wi-Fi lost. Attempting reconnection to \"%s\"...\n", wifiSsid.c_str());
                WiFi.disconnect();
                WiFi.begin(wifiSsid.c_str(), wifiPass.c_str());
                udpServer.stop();
                udpServer.begin(UDP_DISCOVERY_PORT);
            }
        }
    }
    processLedBlink();
    
    // Periodic Cloud Snapshot Sync (Every 15 mins if connected)
    static unsigned long lastCloudSnapshotMs = 0;
    if (lastCloudSnapshotMs == 0) lastCloudSnapshotMs = millis();
    if (WiFi.status() == WL_CONNECTED && (millis() - lastCloudSnapshotMs > 900000)) {
        lastCloudSnapshotMs = millis();
        sendCloudSnapshot();
    }
}
