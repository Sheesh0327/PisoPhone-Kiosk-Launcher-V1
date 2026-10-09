// Firmware entry point: setup() brings up storage, Wi-Fi, hardware and the web/WebSocket servers;
// loop() then runs short, non-blocking steps in a fixed order (watchdog, health, revenue persistence,
// super-admin sync, coin-slot session, payment retries, web server, WebSocket, UDP discovery).
// Nothing in loop() may block for long: the coin pulse counter and the 15 s watchdog depend on it.

#include <Arduino.h>
#include <WiFi.h>
#include <Preferences.h>
#include <ESPmDNS.h>
#include <esp_task_wdt.h>
#include <esp_ota_ops.h>
#include "AccountStorage.h"
#include "esp_wifi.h"
#include "Config.h"
#include "Security.h"
#include "HardwareManager.h"
#include "CoinSlotManager.h"
#include "DeviceManager.h"
#include "DeviceNetwork.h"
#include "WebServerModule.h"
#include "SuperAdminManager.h"
#include "SuperAdminCreds.h"
#include "GatewayCoinslot.h"
#include "PaymentQueueManager.h"
#include "FirmwareVersion.h"
#include "Diagnostics.h"
#include "WebServerAuth.h"
#include "HealthPolicy.h"
#include "WifiLink.h"

#define WDT_TIMEOUT_SECONDS 15

static unsigned long lastWifiCheckTime = 0;
static unsigned long lastCloudSnapshotMs = 0;
static unsigned long lastHealthCheckMs = 0;

static void applyWifiTxPower() {
#if CONFIG_IDF_TARGET_ESP32C3
    // Common ESP32-C3 mini boards have a poorly matched antenna that fails to associate at full
    // power; classic ESP32 boards keep the default.
    WiFi.setTxPower(WIFI_POWER_8_5dBm);
    esp_wifi_set_max_tx_power(34);
#endif
}

// Wi-Fi events arrive on the system event task; they only record what happened, loop() writes the log lines.
static volatile bool wifiEvDisconnected = false;
static volatile uint8_t wifiEvReason = 0;
static volatile bool wifiEvGotIp = false;

static void onWifiEvent(arduino_event_id_t event, arduino_event_info_t info) {
    if (event == ARDUINO_EVENT_WIFI_STA_DISCONNECTED) {
        wifiEvReason = info.wifi_sta_disconnected.reason;
        wifiEvDisconnected = true;
    } else if (event == ARDUINO_EVENT_WIFI_STA_GOT_IP) {
        wifiEvGotIp = true;
    }
}

// Why a connection attempt fails is the first thing an installer needs; without this every failure looks the same.
static void reportWifiEvents() {
    static uint8_t lastLoggedReason = 0;
    static uint32_t lastLoggedMs = 0;
    if (wifiEvGotIp) {
        wifiEvGotIp = false;
        diagLog("[WIFI] Connected to '%s': IP %s, signal %d dBm\n", wifiSsid.c_str(), WiFi.localIP().toString().c_str(),
                (int)WiFi.RSSI());
    }
    if (wifiEvDisconnected) {
        wifiEvDisconnected = false;
        uint8_t reason = wifiEvReason;
        uint32_t now = millis();
        // the stack retries by itself every few seconds: log a change at once, the same reason only now and then
        if (reason != lastLoggedReason || lastLoggedMs == 0 || now - lastLoggedMs > 20000U) {
            lastLoggedReason = reason;
            lastLoggedMs = now ? now : 1;
            const char* hint = wifilink::reasonHint(reason);
            diagLog("[WIFI] Not connected to '%s': reason %u %s%s%s\n", wifiSsid.c_str(), (unsigned)reason,
                    wifilink::reasonName(reason), hint[0] ? " - " : "", hint);
        }
    }
}

// Starts a connection attempt from a clean radio state (boot and every retry).
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
    Serial.printf("[+] Hardware Task Watchdog (esp_task_wdt) initialized (%ds timeout, panic reset enabled)\n",
                  WDT_TIMEOUT_SECONDS);
}

static health::Monitor healthMonitor;

// Looks at memory every 10 s and restarts only when HealthPolicy.h says it is needed and safe (see that file).
static void processSystemHealthAndAutoMaintenance() {
    unsigned long now = millis();
    if (now - lastHealthCheckMs < 10000) return;
    lastHealthCheckMs = now;

    uint32_t freeHeap = ESP.getFreeHeap();
    uint32_t largestBlock = ESP.getMaxAllocHeap();

    // A line every 15 minutes makes a slow leak visible in the diagnostics log long before it matters.
    static unsigned long lastSampleMs = 0;
    if (lastSampleMs == 0 || now - lastSampleMs >= 15UL * 60UL * 1000UL) {
        lastSampleMs = now;
        diagLog("[HEALTH] up %lus heap free=%u min=%u largest=%u\n", now / 1000UL, (unsigned)freeHeap,
                (unsigned)ESP.getMinFreeHeap(), (unsigned)largestBlock);
    }

    // Never restart with a coin session open or a payment that only exists in RAM.
    bool safe = getCoinSlotState() == CoinSlotState::IDLE && !hasUnpersistedPayments();
    health::Action action = healthMonitor.evaluate(now, freeHeap, largestBlock, now, getCurrentMasterTimeMs(), safe);
    if (action == health::Action::None) return;

    diagLog("[HEALTH GUARD] Restarting (%s): heap free=%u largest=%u, up %lus\n", health::actionName(action),
            (unsigned)freeHeap, (unsigned)largestBlock, now / 1000UL);
    diagNoteRestartReason(health::actionName(action));
    flushRevenueNow();
    Serial.flush();
    delay(100);
    ESP.restart();
}

void setup() {
    Serial.begin(115200);
    unsigned long start = millis();
    while (!Serial && (millis() - start < 2500))
        ;
    delay(300);

    diagInit();

    diagLog("\n--- HARDWARE Master Kiosk Controller v%s ---\n", PISO_FW_VERSION);

    // Initialize Hardware Watchdog Early
    initHardwareWatchdog();

    // Load NVS Configuration & Lifetime Vault Revenue safely
    loadAllConfig();
    accountsBegin();
    gatewayInit();
    if (defaultCredentialsActive()) {
        diagLog(
            "[AUTH] WARNING: the default admin password has not been changed yet; coins are blocked until it is.\n");
    }
    lastWifiCheckTime = millis();

    // Initialize Dynamic Hardware Pins & Hardware Reset Pin (GPIO 2)
    applyCoinSlotHardwareConfig();
    initCoinSlotManager();
    setGlobalCoinPaymentCallback(
        [](const String& sessionId, int pulses) { triggerUniversalCoinEvent(pulses, sessionId); });
    pinMode(HARDWARE_RESET_PIN, INPUT_PULLUP);
    setLedHardware(false);
    // Initialize relay hardware (OFF by default - Armed-Only mode)
    setRelayHardware(false);
    Serial.printf(
        "[+] Hardware Pins bound: Universal Multi-Coin Pin = GPIO %d, LED Pin = GPIO %d, Relay Pin = GPIO %d (ActiveLow=%s), Reset Pin = GPIO %d\n",
        universalCoinPin, ledPin, relayPin, relayActiveLow ? "true" : "false", HARDWARE_RESET_PIN);

    // Immediately read hardware factory MAC address from eFuse
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
        diagLog("\n[+] HARDWARE Online at %s\n", WiFi.localIP().toString().c_str());
    } else {
        currentLedState = LED_STATE_FAILED;
        setLedHardware(false);
        diagLog("\n[-] Wi-Fi Connection to \"%s\" Failed or Timed Out.\n", wifiSsid.c_str());
        Serial.println("[-] Waiting for Wi-Fi hotspot to become available...");
    }

    setupWebServer();

    lastWifiCheckTime = millis();
}

// A freshly flashed image that survives a minute of normal running is confirmed, so a bootloader built
// with app rollback would not revert it. With the stock Arduino bootloader this call does nothing.
static void confirmRunningImageWhenStable() {
    static bool confirmed = false;
    if (confirmed || millis() < 60000UL) return;
    confirmed = true;
    esp_err_t r = esp_ota_mark_app_valid_cancel_rollback();
    Serial.printf("[OTA] Running image confirmed (%s)\n", esp_err_to_name(r));
}

void loop() {
    // Feed Hardware Watchdog Timer
    esp_task_wdt_reset();
    confirmRunningImageWhenStable();

    // Memory and Uptime Health Maintenance Check
    processSystemHealthAndAutoMaintenance();

    // 0. Process Debounced Hardware-Conservative NVS Revenue Persistence
    processRevenuePersistence();
    accountsLoop();

    // 0. Process Super Admin 5-minute auto-reset retrieval window
    processSuperAdminLoop();
    superAdminSyncLoop();

    // 0. Process Hardware Fallback Reset Pin (GPIO 2 -> GND for 5 seconds)
    processHardwareResetPin();

    // 1. Process Unified Coin Slot Manager (Arming, Pulse Accumulation & Draining)
    processCoinSlotSession();
    gatewayEventsLoop(); // push coin events to the router (if it asked for them)

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
        lastWifiCheckTime = millis(); // a drop gets the stack's own reconnect and then a full retry, counted from here
    } else {
        if (millis() - lastWifiCheckTime < wifilink::RAPID_BLINK_MS) {
            currentLedState = LED_STATE_CONNECTING;
        } else {
            currentLedState = LED_STATE_FAILED;

            if (wifiSsid.length() > 0 && wifilink::retryDue(millis(), lastWifiCheckTime)) {
                lastWifiCheckTime = millis();
                diagCount(DiagCounter::WifiReconnects);
                diagLog("\n[📶 WATCHDOG] Wi-Fi lost. Attempting reconnection to \"%s\"...\n", wifiSsid.c_str());
                startWifiConnect();
                udpServer.stop();
                udpServer.begin(UDP_DISCOVERY_PORT);
            }
        }
    }
    processLedBlink();
    reportWifiEvents();

    // Periodic Cloud Snapshot Sync (Every 15 mins if connected)
    if (lastCloudSnapshotMs == 0) lastCloudSnapshotMs = millis();
    if (WiFi.status() == WL_CONNECTED && (millis() - lastCloudSnapshotMs > 900000)) {
        lastCloudSnapshotMs = millis();
        sendCloudSnapshot();
    }
}
