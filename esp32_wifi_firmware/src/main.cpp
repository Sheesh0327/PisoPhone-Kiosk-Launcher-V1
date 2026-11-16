// Headless PisoWiFi ESP32 firmware entry point.

#include <Arduino.h>
#include <Preferences.h>
#include <WiFi.h>
#include <esp_task_wdt.h>
#include "esp_wifi.h"

#include "CoinSlotManager.h"
#include "Config.h"
#include "Diagnostics.h"
#include "FirmwareVersion.h"
#include "GatewayCoinslot.h"
#include "GatewaySessionStore.h"
#include "HardwareManager.h"
#include "HealthPolicy.h"
#include "OtaRollback.h"
#include "SerialCli.h"
#include "WebServerAuth.h"
#include "WebServerModule.h"
#include "WifiLink.h"

#define WDT_TIMEOUT_SECONDS 15

static unsigned long lastWifiCheckTime = 0;
static unsigned long lastHealthCheckMs = 0;

static void applyWifiTxPower() {
#if CONFIG_IDF_TARGET_ESP32C3
    WiFi.setTxPower(WIFI_POWER_8_5dBm);
    esp_wifi_set_max_tx_power(34);
#endif
}

static volatile bool wifiEvDisconnected = false;
static volatile uint8_t wifiEvReason = 0;
static volatile bool wifiEvGotIp = false;

static void onWifiEvent(arduino_event_id_t event, arduino_event_info_t info) {
    if (event == ARDUINO_EVENT_WIFI_STA_DISCONNECTED) {
        wifiEvReason = info.wifi_sta_disconnected.reason;
        wifiLastDisconnectReason = info.wifi_sta_disconnected.reason;
        wifiEvDisconnected = true;
    } else if (event == ARDUINO_EVENT_WIFI_STA_GOT_IP) {
        wifiEvGotIp = true;
    }
}

static void reportWifiEvents() {
    static uint8_t lastLoggedReason = 0;
    static uint32_t lastLoggedMs = 0;
    if (wifiEvGotIp) {
        wifiEvGotIp = false;
        markWifiJoined();
        configTime(28800, 0, "pool.ntp.org", "time.google.com");
        diagLog("[WIFI] Connected to '%s': IP %s, signal %d dBm\n", wifiSsid.c_str(), WiFi.localIP().toString().c_str(),
                (int)WiFi.RSSI());
    }
    if (wifiEvDisconnected) {
        wifiEvDisconnected = false;
        uint8_t reason = wifiEvReason;
        uint32_t now = millis();
        if (reason != lastLoggedReason || lastLoggedMs == 0 || now - lastLoggedMs > 20000U) {
            lastLoggedReason = reason;
            lastLoggedMs = now ? now : 1;
            const char* hint = wifilink::reasonHint(reason);
            diagLog("[WIFI] Not connected to '%s': reason %u %s%s%s\n", wifiSsid.c_str(), (unsigned)reason,
                    wifilink::reasonName(reason), hint[0] ? " - " : "", hint);
        }
    }
}

static void startWifiConnect() {
    WiFi.disconnect(true, true);
    delay(100);
    WiFi.mode(WIFI_STA);
    applyWifiTxPower();
    esp_wifi_set_ps(WIFI_PS_NONE);
    diagLog("[WIFI] Connecting to '%s' (password: %u characters)\n", wifiSsid.c_str(), (unsigned)wifiPass.length());
    WiFi.begin(wifiSsid.c_str(), wifiPass.c_str());
}

static void initHardwareWatchdog() {
#if defined(ESP_IDF_VERSION_MAJOR) && (ESP_IDF_VERSION_MAJOR >= 5)
    esp_task_wdt_config_t wdt_config = {
        .timeout_ms = WDT_TIMEOUT_SECONDS * 1000, .idle_core_mask = (1 << 0), .trigger_panic = true};
    esp_task_wdt_init(&wdt_config);
    esp_task_wdt_add(NULL);
#else
    esp_task_wdt_init(WDT_TIMEOUT_SECONDS, true);
    esp_task_wdt_add(NULL);
#endif
    Serial.printf("[+] Hardware Task Watchdog initialized (%ds timeout)\n", WDT_TIMEOUT_SECONDS);
}

static health::Monitor healthMonitor;

static void processSystemHealthAndAutoMaintenance() {
    unsigned long now = millis();
    if (now - lastHealthCheckMs < 10000) return;
    lastHealthCheckMs = now;

    uint32_t freeHeap = ESP.getFreeHeap();
    uint32_t largestBlock = ESP.getMaxAllocHeap();

    static unsigned long lastSampleMs = 0;
    if (lastSampleMs == 0 || now - lastSampleMs >= 15UL * 60UL * 1000UL) {
        lastSampleMs = now;
        diagLog("[HEALTH] up %lus heap free=%u min=%u largest=%u\n", now / 1000UL, (unsigned)freeHeap,
                (unsigned)ESP.getMinFreeHeap(), (unsigned)largestBlock);
    }

    bool safe = getCoinSlotState() == CoinSlotState::IDLE && !hasUnpersistedPayments();
    health::Action action = healthMonitor.evaluate(now, freeHeap, largestBlock, now, getCurrentMasterTimeMs(), safe);
    if (action == health::Action::None) return;

    diagLog("[HEALTH GUARD] Restarting (%s): heap free=%u largest=%u, up %lus\n", health::actionName(action),
            (unsigned)freeHeap, (unsigned)largestBlock, now / 1000UL);
    diagNoteRestartReason(health::actionName(action));
    Serial.flush();
    delay(100);
    ESP.restart();
}

static bool nvsUsable() {
    Preferences probe;
    bool ok = probe.begin("nvs_probe", false);
    if (ok) probe.end();
    return ok;
}

void setup() {
    Serial.begin(115200);
    unsigned long start = millis();
    while (!Serial && (millis() - start < 2500))
        ;
    delay(300);

    diagInit();
    otaRollbackBegin();

    diagLog("\n--- TechNet PisoWiFi Coin Box v%s ---\n", PISO_FW_VERSION);

    initHardwareWatchdog();

    if (!nvsUsable()) {
        diagLog("[NVS] FATAL: saved-data storage cannot be opened.\n");
    }
    loadAllConfig();
    gatewayInit();
    if (defaultCredentialsActive()) {
        diagLog("[AUTH] WARNING: default admin password active; coins blocked until replaced.\n");
    }
    lastWifiCheckTime = millis();

    applyCoinSlotHardwareConfig();
    initCoinSlotManager();
    pinMode(HARDWARE_RESET_PIN, INPUT_PULLUP);
    setLedHardware(false);
    setRelayHardware(false);
    Serial.printf(
        "[+] Hardware Pins bound: Coin Pin = GPIO %d, LED Pin = GPIO %d, Relay Pin = GPIO %d (ActiveLow=%s), Reset Pin = GPIO %d\n",
        universalCoinPin, ledPin, relayPin, relayActiveLow ? "true" : "false", HARDWARE_RESET_PIN);

    uint8_t macInit[6];
    esp_read_mac(macInit, ESP_MAC_WIFI_STA);
    char macBufInit[18];
    snprintf(macBufInit, sizeof(macBufInit), "%02X:%02X:%02X:%02X:%02X:%02X", macInit[0], macInit[1], macInit[2],
             macInit[3], macInit[4], macInit[5]);
    macAddressStr = String(macBufInit);
    Serial.printf("[+] Hardware MAC Address: %s\n", macAddressStr.c_str());

    WiFi.persistent(false);
    WiFi.onEvent(onWifiEvent);
    startWifiConnect();

    currentLedState = LED_STATE_CONNECTING;
    unsigned long wifiConnectStart = millis();
    const unsigned long WIFI_BOOT_TIMEOUT_MS = wifilink::BOOT_CONNECT_MS;

    while (WiFi.status() != WL_CONNECTED && (millis() - wifiConnectStart < WIFI_BOOT_TIMEOUT_MS)) {
        delay(20);
        esp_task_wdt_reset();
        processLedBlink();
        if ((millis() - wifiConnectStart) % 500 < 20) {
            Serial.print(".");
        }
    }

    if (WiFi.status() == WL_CONNECTED) {
        currentLedState = LED_STATE_CONNECTED;
        setLedHardware(true);
        diagLog("\n[+] Online at %s\n", WiFi.localIP().toString().c_str());
    } else {
        currentLedState = LED_STATE_FAILED;
        setLedHardware(false);
        diagLog("\n[-] Wi-Fi Connection to \"%s\" Failed or Timed Out.\n", wifiSsid.c_str());
        Serial.println("[-] Waiting for router hotspot to become available...");
    }

    setupWebServer();
    lastWifiCheckTime = millis();
}

void loop() {
    esp_task_wdt_reset();
    otaRollbackLoop();
    processSystemHealthAndAutoMaintenance();

    if (getCurrentMasterTimeMs() == 0 && WiFi.status() == WL_CONNECTED) {
        time_t sntpNow = time(nullptr);
        if (sntpNow >= (time_t)1700000000UL) {
            updateMasterTime((uint64_t)sntpNow * 1000ULL, "SNTP");
        }
    }

    processHardwareResetPin();
    processCoinSlotSession();
    gatewayEventsLoop();
    processRelayState();
    webServer.handleClient();
    yield();
    processSerialCli();

    if (WiFi.status() == WL_CONNECTED) {
        currentLedState = LED_STATE_CONNECTED;
        lastWifiCheckTime = millis();
    } else {
        if (millis() - lastWifiCheckTime < wifilink::RAPID_BLINK_MS) {
            currentLedState = LED_STATE_CONNECTING;
        } else {
            currentLedState = LED_STATE_FAILED;

            if (wifiSsid.length() > 0 && wifilink::retryDue(millis(), lastWifiCheckTime)) {
                lastWifiCheckTime = millis();
                diagCount(DiagCounter::WifiReconnects);
                diagLog("\n[WATCHDOG] Wi-Fi lost. Attempting reconnection to \"%s\"...\n", wifiSsid.c_str());
                startWifiConnect();
            }
        }
    }
    processLedBlink();
    reportWifiEvents();
}
