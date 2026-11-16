#ifndef CONFIG_H
#define CONFIG_H

#include <Arduino.h>
#include <Preferences.h>

// Hardware constants and pin defaults (from envs/*.ini build flags).
extern const int DEFAULT_UNIVERSAL_COIN_PIN;
extern const int DEFAULT_LED_PIN;
extern const bool DEFAULT_LED_ACTIVE_LOW;
extern const int DEFAULT_RELAY_PIN;
extern const int HARDWARE_RESET_PIN;
extern const int DEFAULT_MINUTES_PER_COIN;

// NVS namespace and keys ("pisowifi_cfg").
extern const char* const NVS_NAMESPACE;
extern const char* const NVS_KEY_WIFI_SSID;
extern const char* const NVS_KEY_WIFI_PASS;
extern const char* const NVS_KEY_U_COIN_PIN;
extern const char* const NVS_KEY_LED_PIN;
extern const char* const NVS_KEY_LED_ACTIVE_LOW;
extern const char* const NVS_KEY_RELAY_PIN;
extern const char* const NVS_KEY_RELAY_ACTIVE_LOW;
extern const char* const NVS_KEY_PULSE_GUARD;
extern const char* const NVS_KEY_PULSE_MIN_MS;
extern const char* const NVS_KEY_MINS_PER_COIN;
extern const char* const NVS_KEY_ADMIN_PW;
extern const char* const NVS_KEY_ADMIN_PW_CHANGED;
extern const char* const NVS_KEY_WIFI_WAIT;
extern const char* const NVS_KEY_GATEWAY_KEY;

extern Preferences prefs;

extern int universalCoinPin;
extern int ledPin;
extern bool ledActiveLow;
extern int relayPin;
extern bool relayActiveLow;
extern bool coinPulseGuard;
extern int coinPulseMinMs;

extern String wifiSsid;
extern String wifiPass;
extern String webPassword;
extern bool adminPwChanged;
extern bool wifiWaitFirstJoin;
extern volatile uint8_t wifiLastDisconnectReason;
extern String macAddressStr;

extern int minutesPerCoin;
extern const unsigned long ARM_TTL;
extern const unsigned long MAX_SESSION_DURATION;

void markWifiJoined();
void runConfigMigrations();
void loadCredentials();
void loadAllConfig();
// Resets "pisowifi_cfg" (Wi-Fi, admin password, gateway key, pins) while preserving "pay_queue"
// (life_pulses and unacknowledged sessions).
void factoryResetDefaults();
void updateMasterTime(uint64_t ts, const String& sourceId = "");
uint64_t getCurrentMasterTimeMs();

#endif // CONFIG_H
