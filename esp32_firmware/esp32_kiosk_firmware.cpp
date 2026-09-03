#include <Arduino.h>
#include <WiFi.h>
#include <HTTPClient.h>
#include <WebServer.h>
#include <Preferences.h>
#include <ESPmDNS.h>
#include <Update.h>
#include "esp_wifi.h"
#include "mbedtls/md.h"
#include "mbedtls/sha1.h"
#include "mbedtls/base64.h"

// ============================================================================
// HARDWARE-C3 Master Kiosk Firmware
// Master-Slave Architecture: HARDWARE is the Absolute Source of Truth
// Port 80: HTTP Web Configuration Portal, REST API, mDNS ("pisokiosk")
// Port 81: RFC6455 Low-Latency WebSocket Server for Real-Time Time Push
// ============================================================================

// Default Wi-Fi Credentials & Factory Recovery Settings
const char* DEFAULT_SSID        = "AdminSetup";
const char* DEFAULT_PASS        = "Admin@123";
const char* DEFAULT_ADMIN_PW    = "admin";
const char* MASTER_CRYPTO_SECRET  = "e9a3b7c1f4d8025e619b4c7d03a8f2e5167b094c2d3e5f8a1b6c9d0e7f4a2b5c";
const int   DEFAULT_COIN_PIN           = 4;
const int   DEFAULT_UNIVERSAL_COIN_PIN = 3;
const int   DEFAULT_LED_PIN            = 8;
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

// Dynamic Hardware Pin Configuration (Persisted in NVS)
int coinPin          = DEFAULT_COIN_PIN;           // Linear beam sensor pin (Default GPIO 4, Pull-Up)
int universalCoinPin = DEFAULT_UNIVERSAL_COIN_PIN; // Universal multi-coin pulse sensor pin (Default GPIO 3, Pull-Up)
int ledPin           = DEFAULT_LED_PIN;            // Status/Drop indicator LED (Default GPIO 8, Active HIGH)

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

// Persistent Local Revenue Counter (NVS Coin & Earnings Audit)
uint32_t totalCoinsLifetime = 0;
uint32_t totalCoinsSession  = 0;
float totalEarningsLifetime = 0.0f;
float totalEarningsSession  = 0.0f;
uint32_t lastSavedTotalCoins = 0;
float lastSavedTotalEarnings = 0.0f;

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

void triggerLedBlink(int blinkCount = 2) {
    // A quick double blink from solid ON state means:
    // OFF (0-60ms) -> ON (60-120ms) -> OFF (120-180ms) -> ON (returns to solid)
    // This requires 3 toggle steps.
    ledBlinksRemaining = 3;
    ledState = false;
    digitalWrite(ledPin, LOW);
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
            digitalWrite(ledPin, ledState ? HIGH : LOW);
            if (ledBlinksRemaining == 0) {
                // Ensure we return cleanly to solid ON (or whatever the state machine needs)
                digitalWrite(ledPin, HIGH);
                ledState = true;
            }
        }
        return;
    }

    // 2. Wi-Fi Status LED Indicator Patterns
    switch (currentLedState) {
        case LED_STATE_CONNECTING:
            if (now - lastLedToggleTime >= LED_RAPID_TOGGLE_MS) {
                lastLedToggleTime = now;
                ledState = !ledState;
                digitalWrite(ledPin, ledState ? HIGH : LOW);
            }
            break;

        case LED_STATE_FAILED:
            if (now - lastLedToggleTime >= LED_SLOW_TOGGLE_MS) {
                lastLedToggleTime = now;
                ledState = !ledState;
                digitalWrite(ledPin, ledState ? HIGH : LOW);
            }
            break;

        case LED_STATE_CONNECTED:
            digitalWrite(ledPin, HIGH);
            ledState = true;
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

// Helper function to check if the coin slot is currently armed
bool isSlotArmed() {
    return (isWsConnected && wsClient.connected()) || (armedIp.length() > 0 && millis() < armedUntil);
}

// Controls the coin slot relay to supply power only when armed
void processRelayState() {
    bool shouldBeOn = isSlotArmed();
    static bool lastRelayState = false;
    if (shouldBeOn != lastRelayState) {
        lastRelayState = shouldBeOn;
        digitalWrite(relayPin, shouldBeOn ? HIGH : LOW);
        if (shouldBeOn) {
            Serial.printf("[⚡ RELAY] Coin Slot Relay (GPIO %d) turned ON (Coin Slot Powered & Active - %s)\n", relayPin, armedIp.c_str());
        } else {
            Serial.printf("[⚡ RELAY] Coin Slot Relay (GPIO %d) turned OFF (Standby / Coin Slot Disabled)\n", relayPin);
        }
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
    
    // Disable coin acceptance if the device is not licensed
    if (!is_licensed) {
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
    if (now < 3000 || !is_licensed) {
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

    // When pulses have arrived and no new pulses occurred for U_INTER_PULSE_TIMEOUT_MS, train is complete!
    if (count > 0 && (now - lastPulseTime >= U_INTER_PULSE_TIMEOUT_MS)) {
        noInterrupts();
        int finalPulses = isrUniversalPulseCount;
        isrUniversalPulseCount = 0;
        interrupts();

        if (finalPulses > 0) {
            triggerUniversalCoinEvent(finalPulses);
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
            http.setReuse(true);

            String ip = String(req.ip);
            String challengeUrl = "http://" + ip + ":" + String(req.port) + String(req.challengePath);
            
            if (http.begin(challengeUrl)) {
                int cCode = http.GET();
                if (cCode == 200) {
                    String challenge = http.getString();
                    http.end();
                    challenge.trim();

                    String signature = calculateHMAC(challenge, sharedSecret);
                    String actionUrl = "http://" + ip + ":" + String(req.port) + String(req.actionPath) + "?challenge=" + challenge + "&signature=" + signature;
                    if (strlen(req.params) > 0) {
                        actionUrl += "&" + String(req.params);
                    }

                    if (http.begin(actionUrl)) {
                        http.GET();
                    }
                }
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
        if (newIps.length() > 0) newIps += ",";
        newIps += deviceId + "|" + ip + "|";
        changed = true;
        Serial.printf("[+] Auto-registered new PisoPhone device IP: %s (ID: %s)\n", ip.c_str(), deviceId.c_str());
    }
    
    if (changed) {
        androidIps = newIps;
        prefs.begin("kiosk_cfg", false);
        prefs.putString("ips", androidIps);
        prefs.end();
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
            if (newTs <= trackedDevices[i].lastNonceTs) return false;
            return true;
        }
    }
    return true;
}

bool verifyTelemetryAuth(String deviceId, String tsStr, String sig) {
    if (deviceId.length() == 0 || tsStr.length() == 0 || sig.length() == 0) return false;
    String expectedSig = calculateHMAC(deviceId + ":" + tsStr, sharedSecret);
    if (sig != expectedSig) return false;
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
                opts += "<option value=\"" + cfg.ip + "\"" + sel + ">" + name + " (" + cfg.ip + ")</option>";
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
    while (startIdx < androidIps.length()) {
        int comma = androidIps.indexOf(',', startIdx);
        if (comma == -1) comma = androidIps.length();
        String entry = androidIps.substring(startIdx, comma);
        entry.trim();
        if (entry.length() > 0) {
            DeviceConfig cfg;
            if (parseDeviceEntry(entry, cfg)) {
                if (targetIp == "ALL" || targetIp == cfg.ip) {
                    String params = "minutes=" + String(minutes);
                    if (txId.length() > 0) params += "&tx_id=" + txId;
                    sendAuthenticated(cfg.ip, targetPort, "/add_time", "/challenge", params, 1000);
                }
            }
        }
        startIdx = comma + 1;
    }
}

const char PORTAL_HTML_TEMPLATE[] PROGMEM = R"HTML(
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>HARDWARE Admin Console</title>
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap" rel="stylesheet">
    <style>
        :root {
            --bg: #f8fafc;
            --sub-bg: #ffffff;
            --input-bg: #f8fafc;
            --text-main: #0f172a;
            --text-muted: #64748b;
            --primary: #4f46e5;
            --primary-hover: #4338ca;
            --border: #e2e8f0;
            --danger: #ef4444;
            --danger-hover: #dc2626;
            --success: #10b981;
            --warning: #f59e0b;
            --info: #0ea5e9;
            --card-shadow: 0 4px 6px -1px rgba(0,0,0,0.05), 0 2px 4px -1px rgba(0,0,0,0.03);
        }
        [data-theme="dark"] {
            --bg: #0b1120;
            --sub-bg: #1e293b;
            --input-bg: #0f172a;
            --text-main: #f8fafc;
            --text-muted: #94a3b8;
            --primary: #6366f1;
            --primary-hover: #4f46e5;
            --border: #334155;
            --card-shadow: 0 4px 6px -1px rgba(0,0,0,0.3);
        }
        * { box-sizing: border-box; }
        body { font-family: 'Inter', sans-serif; background: var(--bg); color: var(--text-main); margin: 0; padding: 20px; line-height: 1.5; transition: background-color 0.2s ease, color 0.2s ease; }
        .app-container { max-width: 900px; margin: 0 auto; }
        .header-bar { display: flex; justify-content: space-between; align-items: center; background: var(--sub-bg); padding: 20px 24px; border-radius: 16px; box-shadow: var(--card-shadow); margin-bottom: 24px; border: 1px solid var(--border); }
        h2 { margin: 0; font-size: 22px; font-weight: 700; display: flex; align-items: center; gap: 10px; }
        .status-badge { background: #dbeafe; color: #1e40af; padding: 6px 12px; border-radius: 20px; font-size: 12px; font-weight: 700; letter-spacing: 0.5px; }
        [data-theme="dark"] .status-badge { background: #1e3a8a; color: #93c5fd; }
        .btn { background: var(--primary); color: white; border: none; padding: 10px 18px; border-radius: 8px; font-weight: 600; cursor: pointer; transition: all 0.2s; font-size: 14px; display: inline-flex; align-items: center; justify-content: center; gap: 8px; text-decoration: none; box-sizing: border-box; }
        .btn:hover { background: var(--primary-hover); transform: translateY(-1px); }
        .btn-danger { background: var(--danger); }
        .btn-danger:hover { background: var(--danger-hover); }
        .btn-warning { background: var(--warning); color: #fff; }
        .btn-warning:hover { background: #d97706; }
        .btn-outline { background: var(--sub-bg); border: 1px solid var(--border); color: var(--text-main); }
        .btn-outline:hover { background: var(--border); }
        
        /* Tabs */
        .tabs { display: flex; gap: 8px; margin-bottom: 24px; border-bottom: 2px solid var(--border); padding-bottom: 12px; overflow-x: auto; }
        .tab { padding: 10px 20px; border-radius: 8px; font-weight: 600; font-size: 15px; color: var(--text-muted); cursor: pointer; transition: 0.2s; white-space: nowrap; }
        .tab:hover:not(.active) { background: var(--border); color: var(--text-main); }
        .tab.active { background: var(--primary); color: white; }
        .tab-content { display: none; animation: fadeIn 0.3s ease; }
        .tab-content.active { display: block; }
        @keyframes fadeIn { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: translateY(0); } }
        
        /* Grid & Cards */
        .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 20px; }
        .grid-full { grid-column: 1 / -1; }
        .card { background: var(--sub-bg); border: 1px solid var(--border); border-radius: 16px; padding: 24px; box-shadow: var(--card-shadow); }
        .card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; border-bottom: 1px solid var(--border); padding-bottom: 12px; }
        .card-title { margin: 0; font-size: 16px; font-weight: 700; color: var(--text-main); display: flex; align-items: center; gap: 8px; }
        
        /* Forms */
        .form-group { margin-bottom: 16px; }
        label { display: block; font-weight: 600; font-size: 13px; margin-bottom: 6px; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.5px; }
        input[type=text], input[type=password], input[type=number], select { width: 100%; padding: 10px 14px; border: 1px solid var(--border); border-radius: 8px; font-size: 15px; background: var(--input-bg); transition: all 0.2s; color: var(--text-main); box-sizing: border-box; }
        input:focus, select:focus { outline: none; border-color: var(--primary); box-shadow: 0 0 0 3px rgba(79, 70, 229, 0.2); background: var(--sub-bg); }
        .hint { font-size: 12px; color: var(--text-muted); margin-top: 4px; line-height: 1.4; }
        
        /* Stats */
        .stats-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 16px; }
        .stat-box { background: var(--input-bg); padding: 16px; border-radius: 12px; text-align: center; border: 1px solid var(--border); }
        .stat-val { font-size: 28px; font-weight: 800; color: var(--success); margin: 4px 0; font-variant-numeric: tabular-nums; }
        .stat-label { font-size: 11px; color: var(--text-muted); text-transform: uppercase; letter-spacing: 1px; font-weight: 700; }
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
            document.querySelector(`[onclick="switchTab('${tabId}')"]`).classList.add('active');
            document.getElementById(tabId).classList.add('active');
            localStorage.setItem('activeTab', tabId);
        }
        document.addEventListener('DOMContentLoaded', () => {
            const savedTab = localStorage.getItem('activeTab') || 'tab-dashboard';
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
        <!-- Header -->
        <div class="header-bar">
            <h2>⚙️ Kiosk Admin</h2>
            <div style="display: flex; align-items: center; gap: 10px; flex-wrap: wrap;">
                <button type="button" id="theme_toggle_btn" onclick="toggleTheme()" class="btn btn-outline" style="padding: 6px 12px; font-size: 13px;">🌙 Dark Mode</button>
                <span class="status-badge">🟢 ONLINE</span>
                <a href="/logout" onclick="return confirm('Log out?');" class="btn btn-outline" style="padding: 6px 12px; font-size: 13px;">🚪 Logout</a>
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
                <!-- Live Devices -->
                <div class="card grid-full">
                    <div class="card-header">
                        <h3 class="card-title">📡 Live Device Status</h3>
                        <span class="status-badge" style="background: #ecfccb; color: #3f6212;">LIVE SYNC</span>
                    </div>
                    <div id="live_devices_container" style="display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px;">
                        <div style="padding: 16px; text-align: center; color: var(--text-muted); grid-column: 1/-1;">Loading devices...</div>
                    </div>
                </div>

                <!-- Vault Stats -->
                <div class="card">
                    <div class="card-header">
                        <h3 class="card-title">💰 Revenue Vault</h3>
                    </div>
                    <div style="background: var(--input-bg); padding: 18px; border-radius: 12px; text-align: center; border: 1px solid var(--border); margin-bottom: 12px;">
                        <div class="stat-label" style="font-size: 13px; font-weight: 600; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 6px;">Total Coins (PHP)</div>
                        <div class="stat-val" style="font-size: 32px; font-weight: 800; color: var(--primary);">₱{TOTAL_COINS}</div>
                    </div>
                    <div style="font-size: 13px; text-align: center; color: var(--text-muted); background: var(--bg); padding: 10px; border-radius: 8px; border: 1px solid var(--border);">
                        Session: <b style="color: var(--text);">₱{SESSION_COINS}</b>
                    </div>
                    <div style="margin-top: 16px; display: flex; flex-direction: column; gap: 8px;">
                        <button type="button" class="btn" style="width: 100%; font-size: 13px;" onclick="triggerCoin()">🪙 Simulate Simple Beam Coin (GPIO 4: ₱{PRICE})</button>
                        <div style="display: grid; grid-template-columns: repeat(4, 1fr); gap: 6px;">
                            <button type="button" class="btn btn-outline" style="padding: 6px; font-size: 12px; font-weight: 700;" onclick="triggerUniversalCoin(1)">₱1 (1p)</button>
                            <button type="button" class="btn btn-outline" style="padding: 6px; font-size: 12px; font-weight: 700;" onclick="triggerUniversalCoin(5)">₱5 (5p)</button>
                            <button type="button" class="btn btn-outline" style="padding: 6px; font-size: 12px; font-weight: 700;" onclick="triggerUniversalCoin(10)">₱10 (10p)</button>
                            <button type="button" class="btn btn-outline" style="padding: 6px; font-size: 12px; font-weight: 700;" onclick="triggerUniversalCoin(20)">₱20 (20p)</button>
                        </div>
                    </div>
                </div>

                <!-- Quick Add Time -->
                <form action="/add_time" method="POST" class="card">
                    <div class="card-header">
                        <h3 class="card-title">⏱️ Quick Adjust Time</h3>
                    </div>
                    <div class="form-group">
                        <label>Target Device</label>
                        <select name="target_ip">
                            <option value="ALL">All Devices (Broadcast)</option>
                            {DEVICE_OPTIONS}
                        </select>
                    </div>
                    <div class="form-group">
                        <label>Minutes</label>
                        <input type="number" name="add_minutes" value="60">
                    </div>
                    <div style="display: flex; gap: 10px; margin-top: 20px;">
                        <button type="submit" name="action" value="add" class="btn btn-warning" style="flex: 1;">+ Add</button>
                        <button type="submit" name="action" value="subtract" class="btn btn-danger" style="flex: 1;">- Subtract</button>
                    </div>
                </form>
            </div>
        </div>

        <!-- TAB 2: SETTINGS -->
        <div id="tab-settings" class="tab-content">
            <form action="/save" method="POST" onsubmit="
                event.preventDefault();
                const rows = document.querySelectorAll('.dev-ip-row');
                const ips = [];
                rows.forEach(row => {
                    const idField = row.querySelector('.dev-id-field');
                    const ipField = row.querySelector('.dev-ip-field');
                    const nameField = row.querySelector('.dev-name-field');
                    if(ipField) {
                        const ipVal = ipField.value.trim();
                        const idVal = idField ? idField.value.trim() : '';
                        let nameVal = nameField ? nameField.value.trim().replace(/\|/g, '') : '';
                        if (ipVal.length > 0) ips.push(idVal + '|' + ipVal + '|' + nameVal);
                    }
                });
                document.getElementById('ips_hidden').value = ips.join(',');
                const formData = new FormData(this);
                fetch('/save', { method: 'POST', body: new URLSearchParams(formData) })
                    .then(res => { if (res.ok) alert('✅ Configuration saved & pushed!'); else alert('❌ Failed to save.'); })
                    .catch(err => alert('Error: ' + err));
            ">
                <div class="grid">
                    <!-- Network -->
                    <div class="card">
                        <h3 class="card-title" style="margin-bottom: 16px;">📡 Wi-Fi & Network</h3>
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
                    </div>

                    <!-- Pricing & Rules (Simple Beam Sensor Only) -->
                    <div class="card">
                        <div class="card-header" style="margin-bottom: 12px;">
                            <h3 class="card-title">🪙 Simple Beam Sensor Pricing & Rules</h3>
                            <span class="status-badge" style="background: #fef3c7; color: #92400e; font-size: 11px;">GPIO 4 ONLY</span>
                        </div>
                        <div class="hint" style="background: var(--input-bg); border: 1px solid var(--border); border-radius: 8px; padding: 10px; margin-bottom: 14px; font-size: 12px; line-height: 1.4;">
                            ⚠️ <b>Note:</b> These pricing, minutes, and debounce settings apply <b>exclusively to the Simple Optical Beam Sensor (GPIO 4)</b>.<br>
                            The <i>Universal Multi-Coin Acceptor (GPIO 3)</i> automatically recognizes hardware pulse denominations (₱1, ₱5, ₱10, ₱20) and calculates time dynamically from this rate.
                        </div>
                        <div class="form-group">
                            <label>Simple Beam Coin Price / Credit (PHP)</label>
                            <input type="number" step="0.01" name="price" value="{PRICE}">
                            <div class="hint">Fixed PHP credit awarded per coin drop on the simple beam sensor (GPIO 4).</div>
                        </div>
                        <div class="form-group">
                            <label>Minutes per Beam Coin Drop</label>
                            <input type="number" name="minutes" value="{MINUTES}">
                            <div class="hint">Session minutes granted per coin drop on the simple beam sensor (GPIO 4).</div>
                        </div>
                        <div class="form-group">
                            <label>Simple Beam Lockout Debounce (ms)</label>
                            <input type="number" name="debounce" value="{DEBOUNCE}">
                            <div class="hint">Debounce lockout period for simple beam sensor to prevent double-counting. Default 25ms.</div>
                        </div>
                    </div>

                    <!-- Devices -->
                    <div class="card grid-full">
                        <h3 class="card-title" style="margin-bottom: 16px;">📱 Registered Android Terminals</h3>
                        <div class="hint" style="margin-bottom: 12px;">Devices automatically register here when they connect to this Wi-Fi network and authenticate via the Android App.</div>
                        {DEVICE_IP_INPUTS}
                    </div>

                    <!-- Advanced Security & Pins -->
                    <div class="card">
                        <h3 class="card-title" style="margin-bottom: 16px;">🔒 Security</h3>
                        <div class="form-group">
                            <label>Admin Web Password</label>
                            <input type="password" name="admin_pw" value="{ADMIN_PASSWORD}">
                        </div>
                    </div>

                    <div class="card">
                        <h3 class="card-title" style="margin-bottom: 16px;">🔌 Hardware Pins</h3>
                        <div class="form-group">
                            <label>Simple Beam Sensor GPIO (Uses Rules Above)</label>
                            <input type="number" name="coin_pin" value="{COIN_PIN}">
                            <div class="hint">Single-coin infrared/optical beam sensor (Default GPIO 4). Awards the configured Coin Price and Minutes.</div>
                        </div>
                        <div class="form-group">
                            <label>Universal Multi-Coin Slot GPIO</label>
                            <input type="number" name="u_coin_pin" value="{U_COIN_PIN}">
                            <div class="hint">Pulse-based multi-coin acceptor for ₱1, ₱5, ₱10, ₱20 (Default GPIO 3).</div>
                        </div>
                        <div class="form-group">
                            <label>Indicator LED GPIO</label>
                            <input type="number" name="led_pin" value="{LED_PIN}">
                        </div>
                        <div class="form-group">
                            <label>Coin Slot Relay GPIO (Auto Power Cutoff)</label>
                            <input type="number" name="relay_pin" value="{RELAY_PIN}">
                            <div class="hint">Relay control pin to power/enable the coin slot when a user presses 'Insert Coin' on their phone (Default GPIO 5). Automatically cuts power / disables coin slot when idle or session expires to prevent lost coins.</div>
                        </div>
                    </div>

                    <div class="grid-full">
                        <button type="submit" class="btn" style="width: 100%; padding: 14px; font-size: 16px;">💾 Save & Push Configuration Live</button>
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
                        <span class="status-badge" style="background: #f3e8ff; color: #7e22ce;">ESPORTS</span>
                    </div>
                    {MATCH_ALERT}
                    <form action="/one_vs_one" method="POST" style="display: flex; flex-direction: column; gap: 16px;">
                        <div class="form-group" style="max-width: 200px;">
                            <label>Stake Minutes</label>
                            <input type="number" id="match_mins_input" name="match_minutes" value="{MATCH_MINUTES}" min="1">
                        </div>
                        
                        <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 24px;">
                            <div style="background: var(--bg); padding: 16px; border-radius: 12px; border: 1px solid var(--border);">
                                <label style="color: var(--primary);">🎮 Player 1</label>
                                <select id="p1_select" name="p1_ip" style="margin-bottom: 12px;">{P1_OPTIONS}</select>
                                <button type="submit" name="winner" value="p1" class="btn btn-outline" style="width: 100%;">🏆 Award Win to P1</button>
                            </div>
                            <div style="background: var(--bg); padding: 16px; border-radius: 12px; border: 1px solid var(--border);">
                                <label style="color: var(--danger);">🎮 Player 2</label>
                                <select id="p2_select" name="p2_ip" style="margin-bottom: 12px;">{P2_OPTIONS}</select>
                                <button type="submit" name="winner" value="p2" class="btn btn-outline" style="width: 100%;">🏆 Award Win to P2</button>
                            </div>
                        </div>
                        
                        <button type="button" onclick="checkMatchQualification()" class="btn" style="background: #7e22ce; align-self: flex-start;">🔍 Verify Both Players' Balances</button>
                    </form>
                    <div id="match_qual_result" style="display: none; margin-top: 16px; padding: 16px; border-radius: 8px; font-size: 14px;"></div>
                </div>

                <!-- OTA Update -->
                <div class="card">
                    <div class="card-header">
                        <h3 class="card-title">🚀 Firmware Upgrade</h3>
                    </div>
                    <p style="font-size: 13px; color: var(--text-muted); margin-bottom: 16px;">Flash a new <code style="background: #e2e8f0; padding: 2px 4px; border-radius: 4px;">.bin</code> compiled firmware wirelessly without a USB cable.</p>
                    <a href="/update" class="btn btn-outline" style="width: 100%;">Upload Firmware (OTA) &rarr;</a>
                </div>

                <!-- System Recovery -->
                <div class="card">
                    <div class="card-header">
                        <h3 class="card-title">⚠️ System Recovery</h3>
                    </div>
                    
                    <form action="/reset_vault" method="POST" onsubmit="return confirm('Reset lifetime coin counts?');" style="margin-bottom: 24px;">
                        <label>Reset Vault Counters</label>
                        <div style="display: flex; gap: 8px;">
                            <input type="password" name="reset_pw" placeholder="Admin password">
                            <button type="submit" class="btn btn-danger">Reset</button>
                        </div>
                    </form>
                    <hr style="border: none; border-top: 1px solid var(--border); margin: 16px 0;">
                    <div style="display: flex; flex-direction: column; gap: 10px;">
                        <button type="button" class="btn" style="width: 100%; background: #0284c7;" onclick="if(confirm('🔄 Reboot HARDWARE controller?')) { fetch('/reboot', {method: 'POST'}).then(() => { alert('HARDWARE is rebooting. Reconnecting in 5 seconds...'); setTimeout(() => window.location.reload(), 5000); }); }">
                            🔄 Reboot HARDWARE Controller
                        </button>
                        <button type="button" class="btn btn-danger" style="width: 100%;" onclick="if(confirm('⚠️ Factory Reset? All settings will be wiped.')) { fetch('/factory_reset', {method: 'POST'}).then(() => { alert('Resetting...'); setTimeout(() => window.location.reload(), 6000); }); }">
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
                const container = document.getElementById('live_devices_container');
                if (!container) return;
                if (data.length === 0) {
                    container.innerHTML = '<div style="padding: 16px; text-align: center; color: var(--text-muted); grid-column: 1/-1;">No PisoPhone devices registered.</div>';
                    return;
                }
                let html = '';
                data.forEach((dev, idx) => {
                    const name = dev.name || ('PisoPhone ' + (idx + 1));
                    const battery = (typeof dev.battery === 'number' && dev.battery >= 0) ? dev.battery : 100;
                    const isCharging = !!dev.charging;
                    
                    // Intuitive battery status styling: Red for critically low, yellow for warning, green for good charge
                    let batteryColor = '#16a34a'; // Green (>20%)
                    let batteryBg = '#f0fdf4';
                    let batteryBorder = '1.5px solid #bbf7d0';
                    let statusLabel = 'GOOD CHARGE';
                    
                    if (battery <= 15) {
                        batteryColor = '#dc2626'; // Red for critically low (<=15%)
                        batteryBg = '#fef2f2';
                        batteryBorder = '1.5px solid #fca5a5';
                        statusLabel = 'CRITICAL LOW';
                    } else if (battery <= 30) {
                        batteryColor = '#d97706'; // Yellow/Amber for low warning (16%-30%)
                        batteryBg = '#fffbeb';
                        batteryBorder = '1.5px solid #fcd34d';
                        statusLabel = 'LOW BATTERY';
                    }

                    if (dev.online) {
                        const mins = Math.floor(dev.time / 60);
                        const secs = dev.time % 60;
                        const timeStr = mins + 'm ' + secs + 's';
                        const active = dev.time > 0;
                        const stateBadge = active 
                            ? '<span style="padding:4px 8px;background:#16a34a;color:white;border-radius:6px;font-size:11px;font-weight:700;">ACTIVE</span>'
                            : '<span style="padding:4px 8px;background:#64748b;color:white;border-radius:6px;font-size:11px;font-weight:700;">STANDBY</span>';
                            
                        const batteryIcon = isCharging ? '⚡' : '🔋';
                        const batteryText = (isCharging ? '⚡ Charging ' : '') + battery + '%';

                        html += '<div style="display:flex; flex-direction:column; justify-content:space-between; padding:16px; border-radius:14px; background:' + batteryBg + '; border:' + batteryBorder + '; box-shadow: 0 2px 4px rgba(0,0,0,0.03); gap: 12px; transition: all 0.3s ease;">' +
                                '<div style="display:flex; justify-content:space-between; align-items:center;">' +
                                    '<div><strong style="color:#0f172a; font-size: 16px; display:block; margin-bottom:2px;">' + name + '</strong><span style="font-size:12px; color:var(--text-muted); font-family: monospace;">' + dev.ip + '</span></div>' +
                                    '<div style="text-align:right;">' +
                                        '<div style="font-weight:800; font-size:18px; color:#0f172a; font-variant-numeric: tabular-nums; line-height: 1.2; margin-bottom:6px;">' + timeStr + '</div>' +
                                        '<div>' + stateBadge + '</div>' +
                                    '</div>' +
                                '</div>' +
                                '<!-- Dynamic Battery Level Bar -->' +
                                '<div style="background: rgba(255,255,255,0.7); padding: 10px; border-radius: 10px; border: 1px solid rgba(0,0,0,0.06);">' +
                                    '<div style="display:flex; justify-content:space-between; align-items:center; font-size: 12px; font-weight: 700; margin-bottom: 6px;">' +
                                        '<span style="color:' + batteryColor + '; display:flex; align-items:center; gap:4px;">' + batteryIcon + ' ' + batteryText + '</span>' +
                                        '<span style="font-size: 10px; color:' + batteryColor + '; text-transform: uppercase; letter-spacing: 0.5px;">' + statusLabel + '</span>' +
                                    '</div>' +
                                    '<div style="height: 8px; background: #e2e8f0; border-radius: 999px; overflow: hidden;">' +
                                        '<div style="height: 100%; width: ' + battery + '%; background: ' + batteryColor + '; border-radius: 999px; transition: width 0.4s ease-in-out;"></div>' +
                                    '</div>' +
                                '</div>' +
                                '<div style="display:flex; gap: 8px; margin-top: 2px;">' +
                                    '<button type="button" class="btn" onclick="triggerAction(\'' + dev.ip + '\', \'locate\', this)" style="flex:1; padding: 8px 12px; font-size: 12px; font-weight: 700; background: #6366f1; color: white; border: none; border-radius: 8px; cursor: pointer; display: flex; align-items: center; justify-content: center; gap: 6px; box-shadow: 0 1px 2px rgba(99,102,241,0.2);">' +
                                        '📍 Locate Device (Sound, Vibrate & Flash)' +
                                    '</button>' +
                                '</div>' +
                                '</div>';
                    } else {
                        html += '<div style="display:flex; flex-direction:column; justify-content:space-between; padding:16px; border-radius:14px; background:#f8fafc; border:1px solid #e2e8f0; opacity: 0.75; gap: 12px;">' +
                                '<div style="display:flex; justify-content:space-between; align-items:center;">' +
                                    '<div><strong style="color:#64748b; font-size: 16px;">' + name + '</strong><br><span style="font-size:12px; color:var(--text-muted); font-family: monospace;">' + dev.ip + '</span></div>' +
                                    '<div><span style="padding:4px 8px;background:#94a3b8;color:white;border-radius:6px;font-size:11px;font-weight:700;">OFFLINE</span></div>' +
                                '</div>' +
                                '<div style="background: rgba(241,245,249,0.8); padding: 8px 10px; border-radius: 8px; font-size: 12px; color: #64748b; text-align: center;">Disconnected / Reconnecting...</div>' +
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

void triggerCoinEvent() {
    Serial.printf("[+] Physical coin pulse detected on GPIO %d (Simple Beam Sensor)! Checking armed session status...\n", coinPin);
    
    // Condition check: A device must have tapped 'Insert Coin' (active WebSocket or active armed TTL)
    bool isArmed = (isWsConnected && wsClient.connected()) || (armedIp.length() > 0 && millis() < armedUntil);
    
    if (!isArmed) {
        Serial.println("[-] COIN REJECTED: No device is currently armed (Insert Coin button was not clicked). Income counter and device time unchanged.");
        return;
    }

    // Automatically credit the configured coinPrice (PHP) and minutesPerCoin time for Simple Beam Sensor
    uint32_t beamCreditPhp = (coinPrice > 0.0f) ? (uint32_t)round(coinPrice) : 1;
    totalCoinsLifetime += beamCreditPhp;
    totalCoinsSession += beamCreditPhp;
    totalEarningsLifetime += coinPrice;
    totalEarningsSession += coinPrice;

    // Persist updated lifetime revenue counter to NVS in batches to prevent Flash wear-out
    if ((totalCoinsLifetime - lastSavedTotalCoins >= 5) || (fabs(totalEarningsLifetime - lastSavedTotalEarnings) >= 20.0f)) {
        prefs.begin("kiosk_cfg", false);
        prefs.putULong("total_coins", totalCoinsLifetime);
        prefs.putFloat("total_earnings", totalEarningsLifetime);
        prefs.end();
        lastSavedTotalCoins = totalCoinsLifetime;
        lastSavedTotalEarnings = totalEarningsLifetime;
    }

    triggerLedBlink();
    
    String txId = String(millis()) + "-" + String(random(1000, 9999));
    int addedSeconds = minutesPerCoin * 60;
    
    // Broadcast instantly over WebSocket if connected
    if (isWsConnected && wsClient.connected()) {
        Serial.printf("[⚡] Pushing Simple Beam Coin (₱%.2f PHP credit, +%d mins) instantly over WebSocket!\n", coinPrice, minutesPerCoin);
        String json = "{\"event\":\"COIN_DETECTED\",\"seconds\":" + String(addedSeconds) + ",\"minutes\":" + String(minutesPerCoin) + ",\"amount\":" + String(coinPrice, 2) + ",\"slot\":\"beam\",\"tx_id\":\"" + txId + "\"}";
        sendWsText(wsClient, json);
        armedUntil = millis() + ARM_TTL;
    }
    
    if (armedIp.length() > 0 && millis() < armedUntil) {
        Serial.printf("[⚡] Routing Simple Beam Coin to ARMED slot: %s (₱%.2f, +%d mins)\n", armedIp.c_str(), coinPrice, minutesPerCoin);
        String targetIpStr = getIpFromDeviceId(armedIp);
        Serial.printf("[⚡] Resolved %s to IP: %s\n", armedIp.c_str(), targetIpStr.c_str());
        sendAuthenticated(targetIpStr, targetPort, "/add_time", "/challenge", "minutes=" + String(minutesPerCoin) + "&seconds=" + String(addedSeconds) + "&amount=" + String(coinPrice, 2) + "&tx_id=" + txId, 1000);
        armedUntil = millis() + ARM_TTL;
    }
}

void triggerUniversalCoinEvent(int pulses) {
    if (pulses <= 0) return;
    Serial.printf("[⚡ UNIVERSAL COIN] %d total pulses accumulated on GPIO %d (₱%d PHP)\n", pulses, universalCoinPin, pulses);

    // Condition check: A device must have tapped 'Insert Coin' (active WebSocket or active armed TTL)
    bool isArmed = (isWsConnected && wsClient.connected()) || (armedIp.length() > 0 && millis() < armedUntil);

    if (!isArmed) {
        Serial.println("[-] COIN REJECTED: No device is currently armed (Insert Coin button was not clicked). Income counter and device time unchanged.");
        return;
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

    // Persist updated lifetime revenue counter to NVS in batches
    if ((totalCoinsLifetime - lastSavedTotalCoins >= 5) || (fabs(totalEarningsLifetime - lastSavedTotalEarnings) >= 20.0f)) {
        prefs.begin("kiosk_cfg", false);
        prefs.putULong("total_coins", totalCoinsLifetime);
        prefs.putFloat("total_earnings", totalEarningsLifetime);
        prefs.end();
        lastSavedTotalCoins = totalCoinsLifetime;
        lastSavedTotalEarnings = totalEarningsLifetime;
    }

    triggerLedBlink(pulses > 1 ? 4 : 2);

    String txId = String(millis()) + "-" + String(random(1000, 9999));

    // Broadcast instantly over WebSocket if connected
    if (isWsConnected && wsClient.connected()) {
        Serial.printf("[⚡] Pushing ₱%d (+%d mins / %d secs) over WebSocket!\n", pulses, addedMinutes, addedSeconds);
        String json = "{\"event\":\"COIN_DETECTED\",\"seconds\":" + String(addedSeconds) + ",\"minutes\":" + String(addedMinutes) + ",\"amount\":" + String(pulses) + ",\"slot\":\"universal\",\"tx_id\":\"" + txId + "\"}";
        sendWsText(wsClient, json);
        armedUntil = millis() + ARM_TTL;
    }

    if (armedIp.length() > 0 && millis() < armedUntil) {
        Serial.printf("[⚡] Routing universal coin to ARMED slot: %s\n", armedIp.c_str());
        String targetIpStr = getIpFromDeviceId(armedIp);
        Serial.printf("[⚡] Resolved %s to IP: %s\n", armedIp.c_str(), targetIpStr.c_str());
        sendAuthenticated(targetIpStr, targetPort, "/add_time", "/challenge", "minutes=" + String(addedMinutes) + "&seconds=" + String(addedSeconds) + "&amount=" + String(pulses) + "&tx_id=" + txId, 1000);
        armedUntil = millis() + ARM_TTL;
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
        if (sig.equals(calculateHMAC(challenge, sharedSecret)) || sig.equals(calculateHMAC(challenge, webPassword))) {
            return true;
        }
    }
    if (!webServer.authenticate("admin", webPassword.c_str())) {
        webServer.requestAuthentication(DIGEST_AUTH, "HARDWARE Admin Login", "Unauthorized");
        return false;
    }
    return true;
}

void redirectHome() {
    webServer.sendHeader("Location", "/");
    webServer.send(303);
}

void handleLogout() {
    // Send a bogus digest nonce to invalidate the browser's cached credentials
    webServer.sendHeader("WWW-Authenticate", "Digest realm=\"HARDWARE Admin Login\", qop=\"auth\", nonce=\"logout_nonce\", opaque=\"\"");
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
            --primary: #6366f1;
            --primary-hover: #4f46e5;
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

void handlePortalRoot() {
    if (!checkAuth()) return;
    String html = PORTAL_HTML_TEMPLATE;
    html.reserve(14000);

    html.replace("{WIFI_SSID}", wifiSsid);
    html.replace("{WIFI_PASS}", wifiPass);
    html.replace("{COIN_PIN}", String(coinPin));
    html.replace("{U_COIN_PIN}", String(universalCoinPin));
    html.replace("{LED_PIN}", String(ledPin));
    html.replace("{RELAY_PIN}", String(relayPin));
    html.replace("{IPS}", androidIps);
    html.replace("{DEVICE_IP_INPUTS}", renderDeviceIpInputs());
    html.replace("{PORT}", String(targetPort));
    html.replace("{ADMIN_PASSWORD}", webPassword);
    html.replace("{PRICE}", String(coinPrice));
    html.replace("{MINUTES}", String(minutesPerCoin));
    html.replace("{DEBOUNCE}", String(lockoutDebounceMs));
    html.replace("{DEVICE_OPTIONS}", renderDeviceOptions(""));
    html.replace("{MATCH_MINUTES}", String(matchMinutes));
    html.replace("{P1_OPTIONS}", renderDeviceOptions(p1Ip.length() > 0 ? p1Ip : getFirstKnownIp()));
    html.replace("{P2_OPTIONS}", renderDeviceOptions(p2Ip));
    
    // Match Alert Message
    if (matchStatusMsg.length() > 0) {
        html.replace("{MATCH_ALERT}", matchStatusMsg);
        matchStatusMsg = ""; // Clear after displaying once
    } else {
        html.replace("{MATCH_ALERT}", "");
    }
    
    // Revenue Vault stats (Total coins in PHP)
    html.replace("{TOTAL_COINS}", String(totalCoinsLifetime));
    html.replace("{SESSION_COINS}", String(totalCoinsSession));
    
    webServer.send(200, "text/html; charset=utf-8", html);
}

void handleReboot() {
    if (!checkAuth()) return;
    Serial.println("\n[🔄 HTTP API] Reboot request received from Web Portal.");
    if (totalCoinsLifetime != lastSavedTotalCoins || totalEarningsLifetime != lastSavedTotalEarnings) {
        prefs.begin("kiosk_cfg", false);
        prefs.putULong("total_coins", totalCoinsLifetime);
        prefs.putFloat("total_earnings", totalEarningsLifetime);
        prefs.end();
        lastSavedTotalCoins = totalCoinsLifetime;
        lastSavedTotalEarnings = totalEarningsLifetime;
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
    prefs.end();

    // Dynamic GPIO Pin re-binding & ISR attachment
    pinMode(coinPin, INPUT_PULLUP);
    detachInterrupt(digitalPinToInterrupt(universalCoinPin));
    pinMode(universalCoinPin, INPUT_PULLUP);
    attachInterrupt(digitalPinToInterrupt(universalCoinPin), universalCoinIsr, FALLING);
    pinMode(ledPin, OUTPUT);
    pinMode(relayPin, OUTPUT);
    digitalWrite(relayPin, isSlotArmed() ? HIGH : LOW);

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
    sendAddTime(minutes, targetIp);
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

void handleApiStatus() {
    if (!checkAuth()) return;
    String json = "[";
    int startIdx = 0;
    bool first = true;
    int devNum = 1;
    while (startIdx < androidIps.length()) {
        int comma = androidIps.indexOf(',', startIdx);
        if (comma == -1) comma = androidIps.length();
        String entry = androidIps.substring(startIdx, comma);
        entry.trim();
        if (entry.length() > 0) {
            DeviceConfig cfg;
            if (parseDeviceEntry(entry, cfg)) {
                if (!first) json += ",";
                first = false;
                
                int rem = getTrackedTimeRemaining(cfg.ip, 15000, cfg.id);
                int bat = getTrackedBatteryLevel(cfg.ip, cfg.id);
                bool chg = getTrackedChargingState(cfg.ip, cfg.id);
                String name = "PisoPhone " + String(devNum);
                
                json += "{";
                json += "\"id\":\"" + cfg.id + "\",";
                json += "\"ip\":\"" + cfg.ip + "\",";
                json += "\"name\":\"" + name + "\",";
                json += "\"time\":" + String(rem) + ",";
                json += "\"online\":" + String(rem >= 0 ? "true" : "false") + ",";
                json += "\"battery\":" + String(bat) + ",";
                json += "\"charging\":" + String(chg ? "true" : "false");
                json += "}";
                devNum++;
            }
        }
        startIdx = comma + 1;
    }
    json += "]";
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
    String json = "{\"device\":\"HARDWARE_kiosk\",\"version\":\"3.0\",\"price\":" + String(coinPrice) + ",\"minutes\":" + String(minutesPerCoin);
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
            int timeRem = webServer.hasArg("time") ? webServer.arg("time").toInt() : 0;
            int state = webServer.hasArg("state") ? webServer.arg("state").toInt() : 0;
            int battery = webServer.hasArg("battery") ? webServer.arg("battery").toInt() : 100;
            bool charging = webServer.hasArg("charging") ? (webServer.arg("charging").toInt() == 1 || webServer.arg("charging") == "true") : false;

            updateDeviceTelemetry(deviceId, reqIp, timeRem, state, battery, charging, ts);

            String devName = getDeviceNameByIpOrId(reqIp, deviceId);

            String status = is_licensed ? "ok" : "unlicensed";
            String json = "{\"status\":\"" + status + "\",\"device\":\"HARDWARE_kiosk\",\"mac\":\"" + macAddressStr + "\",\"price\":" + String(coinPrice) + ",\"minutes\":" + String(minutesPerCoin);
            if (devName.length() > 0) {
                json += ",\"device_name\":\"" + devName + "\"";
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
    String expectedSig = calculateHMAC(mac, MASTER_CRYPTO_SECRET);
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
            String expectedSig = calculateHMAC(reqDeviceId + ":" + tsStr, sharedSecret);
            if (sig != expectedSig) {
                Serial.printf("[-] WS Auth Failed for %s: Signature Mismatch\n", reqDeviceId.c_str());
                newClient.print("HTTP/1.1 403 Forbidden\r\n\r\nInvalid Signature");
                newClient.stop();
                return;
            }

            // 1b. Verify Replay Protection
            unsigned long long ts = strtoull(tsStr.c_str(), NULL, 10);
            if (!checkReplayProtection(reqDeviceId, ts)) {
                Serial.printf("[-] WS Auth Failed for %s: Replay Detected\n", reqDeviceId.c_str());
                newClient.print("HTTP/1.1 403 Forbidden\r\n\r\nReplay Detected");
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
            armedUntil = now + ARM_TTL;
            
            Serial.printf("[⚡ WS Port 81] WebSocket ARMED securely for %s (TTL: %lu s)\n", reqDeviceId.c_str(), ARM_TTL / 1000);
            sendWsText(wsClient, "{\"event\":\"ARMED\"}");
        }
    }
    
    // Process active WebSocket client frames or disconnection
    if (isWsConnected) {
        if (!wsClient.connected()) {
            Serial.printf("[*] WS Client %s disconnected. Slot released.\n", armedIp.c_str());
            isWsConnected = false;
            armedIp = "";
            armedUntil = 0;
            sessionStartTime = 0;
            return;
        }
        
        if (wsClient.available()) {
            String frameText = readWsText(wsClient);
            if (frameText == "DONE" || frameText == "CLOSE") {
                wsClient.stop();
                isWsConnected = false;
                armedIp = "";
                armedUntil = 0;
                sessionStartTime = 0;
                return;
            }
        }
        
        // Check session TTL expiration
        unsigned long now = millis();
        if (now >= armedUntil || (sessionStartTime > 0 && (now - sessionStartTime >= MAX_SESSION_DURATION))) {
            Serial.printf("[*] WS Session TTL expired for %s. Slot released.\n", armedIp.c_str());
            sendWsText(wsClient, "{\"event\":\"TIMEOUT\"}");
            wsClient.stop();
            isWsConnected = false;
            armedIp = "";
            armedUntil = 0;
            sessionStartTime = 0;
        }
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
    pinMode(ledPin, OUTPUT);
    for (int i = 0; i < 10; i++) {
        digitalWrite(ledPin, HIGH);
        delay(60);
        digitalWrite(ledPin, LOW);
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
    targetPort        = prefs.getInt("port", targetPort);
    webPassword       = prefs.getString("admin_pw", webPassword);
    coinPrice         = prefs.getFloat("price", coinPrice);
    minutesPerCoin    = prefs.getInt("minutes", minutesPerCoin);
    lockoutDebounceMs = prefs.getInt("debounce", lockoutDebounceMs);
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
    pinMode(ledPin, OUTPUT);
    digitalWrite(ledPin, LOW);
    pinMode(relayPin, OUTPUT);
    digitalWrite(relayPin, LOW);
    Serial.printf("[+] Hardware Pins bound: Beam Coin Pin = GPIO %d, Universal Multi-Coin Pin = GPIO %d (ISR active), LED Pin = GPIO %d, Relay Pin = GPIO %d, Reset Pin = GPIO %d\n", coinPin, universalCoinPin, ledPin, relayPin, HARDWARE_RESET_PIN);

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
        delay(50);
        processLedBlink(); // Rapid flash while connecting
        if ((millis() - wifiConnectStart) % 500 < 50) {
            Serial.print(".");
        }
    }

    if (WiFi.status() == WL_CONNECTED) {
        currentLedState = LED_STATE_CONNECTED;
        digitalWrite(ledPin, HIGH);
        Serial.printf("\n[+] HARDWARE Online at %s\n", WiFi.localIP().toString().c_str());
    } else {
        currentLedState = LED_STATE_FAILED;
        Serial.printf("\n[-] Wi-Fi Connection to \"%s\" Failed or Timed Out.\n", wifiSsid.c_str());
        Serial.println("[-] Waiting for Wi-Fi hotspot to become available...");
    }

    // Initialize mDNS Responder ("pisokiosk.local")
    uint8_t mac[6];
    WiFi.macAddress(mac);
    char macBuf[18];
    snprintf(macBuf, sizeof(macBuf), "%02X:%02X:%02X:%02X:%02X:%02X", mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
    macAddressStr = String(macBuf);

    if (MDNS.begin("pisokiosk")) {
        MDNS.addService("http", "tcp", 80);
        Serial.println("[+] mDNS service active at http://pisokiosk.local");
    } else {
        Serial.println("[-] Error setting up mDNS responder!");
    }

    // Initialize Authenticated Request Worker Queue & FreeRTOS Supervisor Task (8KB stack)
    authQueue = xQueueCreate(16, sizeof(AuthRequest));
    xTaskCreate(authWorkerTask, "AuthWorker", 8192, NULL, 1, NULL);

    // Port 80: HTTP Portal & API routes
    webServer.on("/", HTTP_GET, handlePortalRoot);
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

    if (WiFi.status() == WL_CONNECTED) {
        Serial.printf("[!] Port 80: Management at http://%s:80\n", WiFi.localIP().toString().c_str());
        Serial.printf("[!] Port 81: WebSocket at ws://%s:81/ws\n\n", WiFi.localIP().toString().c_str());
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
    
    // 4. Handle USB Serial CLI commands
    processSerialCli();
    
    // 5 & 6. Robust Non-Blocking Wi-Fi Reconnection Watchdog & LED Status Sync
    if (WiFi.status() == WL_CONNECTED) {
        currentLedState = LED_STATE_CONNECTED;
    } else {
        // Not connected. Check how long we've been trying to connect in this cycle
        if (millis() - lastWifiCheckTime < 15000) {
            // We are actively trying to reconnect: show rapid flashing (connecting state)
            currentLedState = LED_STATE_CONNECTING;
        } else {
            // Attempt window timed out: show slow flashing (failed state)
            currentLedState = LED_STATE_FAILED;
            
            // Trigger a fresh, clean connection attempt every 15 seconds
            lastWifiCheckTime = millis();
            Serial.println("\n[📶 WATCHDOG] Wi-Fi connection lost. Re-initiating non-blocking reconnection...");
            WiFi.disconnect();
            WiFi.begin(wifiSsid.c_str(), wifiPass.c_str());
        }
    }
    processLedBlink();
}
