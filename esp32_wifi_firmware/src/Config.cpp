// Persistent configuration (NVS namespace "pisowifi_cfg"): defaults, loading/saving of pins,
// Wi-Fi, admin password, and monotonic master clock.

#include "Config.h"
#include "ConfigMigration.h"
#include "CoinCredit.h"
#include "Diagnostics.h"
#include "GatewayCoinslot.h"
#include "GatewaySessionStore.h"
#include "MasterClock.h"
#include "PinRules.h"
#include "PulseGuard.h"
#include "SetupGate.h"
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>

#if !defined(PISO_PIN_COIN) || !defined(PISO_PIN_LED) || !defined(PISO_PIN_RELAY) || !defined(PISO_PIN_RESET)
#error "Define PISO_PIN_COIN, PISO_PIN_LED, PISO_PIN_RELAY and PISO_PIN_RESET in the PlatformIO env build_flags."
#endif
const int DEFAULT_UNIVERSAL_COIN_PIN = PISO_PIN_COIN;
const int DEFAULT_LED_PIN = PISO_PIN_LED;
const bool DEFAULT_LED_ACTIVE_LOW = false;
const int DEFAULT_RELAY_PIN = PISO_PIN_RELAY;
const int HARDWARE_RESET_PIN = PISO_PIN_RESET;
const int DEFAULT_MINUTES_PER_COIN = 6;

const char* const NVS_NAMESPACE = "pisowifi_cfg";
const char* const NVS_KEY_WIFI_SSID = "wifi_ssid";
const char* const NVS_KEY_WIFI_PASS = "wifi_pass";
const char* const NVS_KEY_U_COIN_PIN = "u_coin_pin";
const char* const NVS_KEY_LED_PIN = "led_pin";
const char* const NVS_KEY_LED_ACTIVE_LOW = "led_act_low";
const char* const NVS_KEY_RELAY_PIN = "relay_pin";
const char* const NVS_KEY_RELAY_ACTIVE_LOW = "relay_act_low";
const char* const NVS_KEY_PULSE_GUARD = "pulse_guard";
const char* const NVS_KEY_PULSE_MIN_MS = "pulse_min_ms";
const char* const NVS_KEY_MINS_PER_COIN = "mins_per_coin";
const char* const NVS_KEY_ADMIN_PW = cfgmig::K_ADMIN_PW;
const char* const NVS_KEY_ADMIN_PW_CHANGED = cfgmig::K_ADMIN_PW_CHANGED;
const char* const NVS_KEY_WIFI_WAIT = "wifi_wait";
const char* const NVS_KEY_GATEWAY_KEY = "gw_key";

Preferences prefs;

int universalCoinPin = DEFAULT_UNIVERSAL_COIN_PIN;
int ledPin = DEFAULT_LED_PIN;
bool ledActiveLow = DEFAULT_LED_ACTIVE_LOW;
int relayPin = DEFAULT_RELAY_PIN;
bool relayActiveLow = false;
bool coinPulseGuard = true;
int coinPulseMinMs = PULSE_MIN_MS_DEFAULT;

String wifiSsid = setupgate::DEFAULT_WIFI_SSID;
String wifiPass = setupgate::DEFAULT_WIFI_PASSWORD;
String webPassword = "";
bool adminPwChanged = false;
bool wifiWaitFirstJoin = false;
volatile uint8_t wifiLastDisconnectReason = 0;
String macAddressStr = "";

int minutesPerCoin = DEFAULT_MINUTES_PER_COIN;

const unsigned long ARM_TTL = 20000;
const unsigned long MAX_SESSION_DURATION = 120000;

static masterclock::Stamp masterStamp;
static portMUX_TYPE masterClockMux = portMUX_INITIALIZER_UNLOCKED;

static void setMasterStamp(uint64_t ts, unsigned long nowMs) {
    portENTER_CRITICAL(&masterClockMux);
    masterStamp.ts = ts;
    masterStamp.ms = (uint32_t)nowMs;
    portEXIT_CRITICAL(&masterClockMux);
}

uint64_t getCurrentMasterTimeMs() {
    masterclock::Stamp copy;
    portENTER_CRITICAL(&masterClockMux);
    copy = masterStamp;
    portEXIT_CRITICAL(&masterClockMux);
    return masterclock::now(copy, (uint32_t)millis());
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
        if (ts > current) setMasterStamp(ts, nowMs);
        return;
    }

    if (outlierTimestamp > 0 && sourceId.length() > 0 && sourceId != outlierSource &&
        (uint64_t)(nowMs - outlierMillis) <= MASTER_CLOCK_WINDOW_MS) {
        uint64_t projected = outlierTimestamp + (uint64_t)(nowMs - outlierMillis);
        if (absDiff(ts, projected) <= MASTER_CLOCK_AGREE_MS) {
            diagLog("[CLOCK] Master clock re-synced (%llu -> %llu) after agreement from '%s' and '%s'.\n", current, ts,
                    outlierSource.c_str(), sourceId.c_str());
            setMasterStamp(ts, nowMs);
            outlierTimestamp = 0;
            outlierSource = "";
            return;
        }
    }
    outlierTimestamp = ts;
    outlierMillis = nowMs;
    outlierSource = sourceId;
}

struct PrefsStore {
    bool isKey(const char* k) { return prefs.isKey(k); }
    std::string getString(const char* k, const std::string& d) { return prefs.getString(k, d.c_str()).c_str(); }
    void putString(const char* k, const std::string& v) { prefs.putString(k, v.c_str()); }
    bool getBool(const char* k, bool d) { return prefs.getBool(k, d); }
    void putBool(const char* k, bool v) { prefs.putBool(k, v); }
    uint32_t getUInt(const char* k, uint32_t d) { return prefs.getULong(k, d); }
    void putUInt(const char* k, uint32_t v) { prefs.putULong(k, v); }
};

void runConfigMigrations() {
    prefs.begin(NVS_NAMESPACE, false);
    PrefsStore store;
    cfgmig::Result r = cfgmig::runAll(store);
    prefs.end();
    if (r.storeIsNewer) {
        Serial.printf("[CONFIG] Settings are from a newer firmware (format %u > %u); leaving them untouched.\n",
                      (unsigned)r.from, (unsigned)cfgmig::CURRENT_VERSION);
    } else if (r.ranAny) {
        Serial.printf("[CONFIG] Settings format %u -> %u\n", (unsigned)r.from, (unsigned)r.to);
    }
}

void markWifiJoined() {
    wifiLastDisconnectReason = 0;
    if (!wifiWaitFirstJoin) return;
    wifiWaitFirstJoin = false;
    prefs.begin(NVS_NAMESPACE, false);
    prefs.putBool(NVS_KEY_WIFI_WAIT, false);
    prefs.end();
}

void loadCredentials() {
    prefs.begin(NVS_NAMESPACE, false);
    webPassword = prefs.getString(NVS_KEY_ADMIN_PW, "");
    adminPwChanged = prefs.getBool(NVS_KEY_ADMIN_PW_CHANGED, false);
    wifiWaitFirstJoin = prefs.getBool(NVS_KEY_WIFI_WAIT, false);
    prefs.end();

    if (!adminPwChanged) {
        Serial.println("\n================ FIRST-TIME SETUP ================");
        Serial.println(" This box is using its default password.");
        Serial.println(" Replace the admin password before accepting coins.");
        Serial.println("==================================================\n");
    }
}

void loadAllConfig() {
    runConfigMigrations();
    loadCredentials();

    prefs.begin(NVS_NAMESPACE, false);
    wifiSsid = prefs.getString(NVS_KEY_WIFI_SSID, wifiSsid);
    wifiPass = prefs.getString(NVS_KEY_WIFI_PASS, wifiPass);
    universalCoinPin = prefs.getInt(NVS_KEY_U_COIN_PIN, universalCoinPin);
    ledPin = prefs.getInt(NVS_KEY_LED_PIN, ledPin);
    ledActiveLow = prefs.getBool(NVS_KEY_LED_ACTIVE_LOW, DEFAULT_LED_ACTIVE_LOW);
    relayPin = prefs.getInt(NVS_KEY_RELAY_PIN, relayPin);
    if (pinrules::problem(pinrules::THIS_CHIP, universalCoinPin, relayPin, ledPin, HARDWARE_RESET_PIN)) {
        Serial.printf("[CONFIG] Stored pins coin=%d relay=%d led=%d cannot be used: back to the defaults.\n",
                      universalCoinPin, relayPin, ledPin);
        universalCoinPin = DEFAULT_UNIVERSAL_COIN_PIN;
        relayPin = DEFAULT_RELAY_PIN;
        ledPin = DEFAULT_LED_PIN;
    }

    minutesPerCoin = clampMinutesPerCoin(prefs.getInt(NVS_KEY_MINS_PER_COIN, DEFAULT_MINUTES_PER_COIN));
    webPassword = prefs.getString(NVS_KEY_ADMIN_PW, webPassword);
    relayActiveLow = prefs.getBool(NVS_KEY_RELAY_ACTIVE_LOW, false);
    coinPulseGuard = prefs.getBool(NVS_KEY_PULSE_GUARD, true);
    coinPulseMinMs = pulseMinMsClamp(prefs.getInt(NVS_KEY_PULSE_MIN_MS, PULSE_MIN_MS_DEFAULT));
    prefs.end();

    Serial.printf("[CONFIG] Loaded NVS Config: SSID='%s', RelayPin=%d, MinsPerCoin=%d\n", wifiSsid.c_str(), relayPin,
                  minutesPerCoin);
}

void factoryResetDefaults() {
    Serial.println("\n=======================================================");
    diagLog("[FACTORY RESET] Restoring operator settings to defaults (payment queue and life_pulses preserved)...");
    Serial.println("=======================================================");

    prefs.begin(NVS_NAMESPACE, false);
    prefs.clear();
    prefs.end();

    // Re-initialize default admin credentials and clear cached gateway key without touching "pay_queue".
    runConfigMigrations();
    loadCredentials();
    gatewayInit();

    wifiSsid = setupgate::DEFAULT_WIFI_SSID;
    wifiPass = setupgate::DEFAULT_WIFI_PASSWORD;
    universalCoinPin = DEFAULT_UNIVERSAL_COIN_PIN;
    ledPin = DEFAULT_LED_PIN;
    ledActiveLow = DEFAULT_LED_ACTIVE_LOW;
    relayPin = DEFAULT_RELAY_PIN;
    relayActiveLow = false;
    coinPulseGuard = true;
    coinPulseMinMs = PULSE_MIN_MS_DEFAULT;
    minutesPerCoin = DEFAULT_MINUTES_PER_COIN;

    Serial.println("[FACTORY RESET] Defaults restored cleanly.");
}
