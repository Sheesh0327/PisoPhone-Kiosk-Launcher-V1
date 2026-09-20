#include <Arduino.h>
#include <WiFi.h>
#include <Preferences.h>
#include <ESPmDNS.h>
#include <esp_task_wdt.h>
#include "esp_wifi.h"
#include "Config.h"
#include "Security.h"
#include "HardwareManager.h"
#include "CoinSlotManager.h"
#include "DeviceManager.h"
#include "DeviceNetwork.h"
#include "WebServerModule.h"
#include "SuperAdminManager.h"
#include "PaymentQueueManager.h"
#include <esp_system.h>

#define WDT_TIMEOUT_SECONDS 15
#define LOW_HEAP_WARNING_BYTES 25000              // 25 KB: Shed expendable work & stop admission
#define MIN_SAFE_HEAP_BYTES 10000                 // 10 KB Critical Heap Limit: Request gated restart

static unsigned long lastWifiCheckTime = 0;
static unsigned long lastCloudSnapshotMs = 0;
static unsigned long lastHealthCheckMs = 0;
static unsigned long lastLoopTimeMs = 0;
static unsigned long maxLoopGapMs = 0;
static uint32_t wifiBackoffMs = 5000;             // Bounded exponential backoff with jitter

static void logBootResetDiagnostics() {
    esp_reset_reason_t reason = esp_reset_reason();
    Serial.printf("\n[🔍 BOOT DIAGNOSTICS] ESP32 Reset Reason: %d ", (int)reason);
    switch (reason) {
        case ESP_RST_POWERON:   Serial.println("(Power-on reset)"); break;
        case ESP_RST_EXT:       Serial.println("(External pin reset)"); break;
        case ESP_RST_SW:        Serial.println("(Software ESP.restart)"); break;
        case ESP_RST_PANIC:     Serial.println("(Exception / Panic reset)"); break;
        case ESP_RST_INT_WDT:   Serial.println("(Interrupt watchdog reset)"); break;
        case ESP_RST_TASK_WDT:  Serial.println("(Task watchdog reset)"); break;
        case ESP_RST_WDT:       Serial.println("(Other watchdog reset)"); break;
        case ESP_RST_DEEPSLEEP: Serial.println("(Deep sleep wake reset)"); break;
        case ESP_RST_BROWNOUT:  Serial.println("(Brownout reset)"); break;
        default:                Serial.println("(Unknown reset)"); break;
    }
}

static void initHardwareWatchdog() {
#if defined(ESP_IDF_VERSION_MAJOR) && (ESP_IDF_VERSION_MAJOR >= 5)
    esp_task_wdt_config_t wdt_config = {
        .timeout_ms = WDT_TIMEOUT_SECONDS * 1000,
        .idle_core_mask = (1 << 0),
        .trigger_panic = true
    };
    esp_task_wdt_init(&wdt_config);
    esp_task_wdt_add(NULL);
#else
    esp_task_wdt_init(WDT_TIMEOUT_SECONDS, true);
    esp_task_wdt_add(NULL);
#endif
    Serial.printf("[+] Hardware Task Watchdog (esp_task_wdt) initialized (%ds timeout, panic reset enabled)\n", WDT_TIMEOUT_SECONDS);
}

static void processSystemHealthAndAutoMaintenance() {
    unsigned long now = millis();
    if (now - lastHealthCheckMs < 5000) return; // Health check every 5 seconds
    lastHealthCheckMs = now;

    uint32_t freeHeap = ESP.getFreeHeap();
    uint32_t minFreeHeap = ESP.getMinFreeHeap();

    // 1. Low Memory Load Shedding: Stop new admissions and trim stale telemetry cache
    if (freeHeap < LOW_HEAP_WARNING_BYTES) {
        if (!isMaintenanceReasonActive(MAINT_REASON_HEAP)) {
            Serial.printf("⚠️ [HEALTH GUARD] Free heap low (%u bytes). Stopping new session admissions and shedding cache...\n", freeHeap);
            setMaintenanceReason(MAINT_REASON_HEAP, true);
        }
    } else if (isMaintenanceReasonActive(MAINT_REASON_HEAP) && freeHeap >= (LOW_HEAP_WARNING_BYTES + 5000)) {
        // Hysteresis recovery if memory pressure subsides without reboot
        setMaintenanceReason(MAINT_REASON_HEAP, false);
        Serial.printf("ℹ️ [HEALTH GUARD] Free heap recovered (%u bytes). Resuming admissions for heap condition.\n", freeHeap);
    }

    // 2. Critical Heap Pressure: Attempt safe gated restart only if safe
    if (freeHeap < MIN_SAFE_HEAP_BYTES) {
        Serial.printf("🚨 [HEALTH GUARD] Critical free heap (%u bytes < %d bytes threshold). Requesting gated restart...\n",
                      freeHeap, MIN_SAFE_HEAP_BYTES);
        requestSystemRestart("Low Memory Emergency Recovery", 10000);
    }
}

void setup() {
    Serial.begin(115200);
    unsigned long start = millis();
    while (!Serial && (millis() - start < 2500));
    delay(300);

    Serial.println("\n--- HARDWARE-C3 Master Kiosk Controller ---");

    // Initialize Hardware Watchdog Early
    initHardwareWatchdog();

    // Log Boot Reset Reason Diagnostics
    logBootResetDiagnostics();

    // Load NVS Configuration & Lifetime Vault Revenue safely
    loadAllConfig();
    lastWifiCheckTime = millis();

    // Initialize Dynamic Hardware Pins & Hardware Reset Pin (GPIO 2)
    applyCoinSlotHardwareConfig();
    initCoinSlotManager();
    setGlobalCoinPaymentCallback([](const String& sessionId, int pulses) -> bool {
        return triggerUniversalCoinEvent(pulses, sessionId);
    });
    pinMode(HARDWARE_RESET_PIN, INPUT_PULLUP);
    setLedHardware(false);
    // Initialize relay hardware (OFF by default - Armed-Only mode)
    setRelayHardware(false);
    Serial.printf("[+] Hardware Pins bound: Universal Multi-Coin Pin = GPIO %d, LED Pin = GPIO %d, Relay Pin = GPIO %d (ActiveLow=%s), Reset Pin = GPIO %d\n",
        universalCoinPin, ledPin, relayPin, relayActiveLow ? "true" : "false", HARDWARE_RESET_PIN);

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
    const unsigned long WIFI_BOOT_TIMEOUT_MS = 10000;

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
        Serial.printf("\n[+] HARDWARE Online at %s\n", WiFi.localIP().toString().c_str());
    } else {
        currentLedState = LED_STATE_FAILED;
        setLedHardware(false);
        Serial.printf("\n[-] Wi-Fi Connection to \"%s\" Failed or Timed Out.\n", wifiSsid.c_str());
        Serial.println("[-] Waiting for Wi-Fi hotspot to become available...");
    }

    setupWebServer();

    lastWifiCheckTime = millis();
}

void loop() {
    unsigned long loopStart = millis();
    if (lastLoopTimeMs > 0) {
        unsigned long gap = loopStart - lastLoopTimeMs;
        if (gap > maxLoopGapMs) {
            maxLoopGapMs = gap;
            if (gap > 500) {
                Serial.printf("[⏱️ PERF] Main loop gap spike: %lu ms (max: %lu ms)\n", gap, maxLoopGapMs);
            }
        }
    }
    lastLoopTimeMs = loopStart;

    // Feed Hardware Watchdog Timer on genuine loop progress
    esp_task_wdt_reset();

    // Memory and Uptime Health Maintenance Check
    processSystemHealthAndAutoMaintenance();

    // 0. Process Pending System Restart
    processPendingSystemRestart();

    // 0. Process Debounced Hardware-Conservative NVS Revenue Persistence
    processRevenuePersistence();

    // 0. Process Super Admin 5-minute auto-reset retrieval window
    processSuperAdminLoop();

    // 0. Process Hardware Fallback Reset Pin (GPIO 2 -> GND for 5 seconds)
    processHardwareResetPin();

    // 1. Process Unified Coin Slot Manager (Arming, Pulse Accumulation & Draining)
    processCoinSlotSession();

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
    
    // 7. Robust Non-Blocking Wi-Fi Reconnection Watchdog with Bounded Exponential Backoff + Jitter
    if (WiFi.status() == WL_CONNECTED) {
        currentLedState = LED_STATE_CONNECTED;
        wifiBackoffMs = 5000; // Reset backoff on successful connection
    } else {
        if (millis() - lastWifiCheckTime < 10000) {
            currentLedState = LED_STATE_CONNECTING;
        } else {
            currentLedState = LED_STATE_FAILED;
            
            if (wifiSsid.length() > 0 && (millis() - lastWifiCheckTime > wifiBackoffMs)) {
                lastWifiCheckTime = millis();
                // Add +/- 20% pseudo-random jitter to prevent network thundering herd
                uint32_t jitter = (esp_random() % (wifiBackoffMs / 4 + 1));
                uint32_t nextInterval = wifiBackoffMs * 2;
                if (nextInterval > 60000) nextInterval = 60000; // Max 60 seconds
                wifiBackoffMs = nextInterval + jitter;

                Serial.printf("\n[📶 WATCHDOG] Wi-Fi lost. Attempting reconnection to \"%s\" (next retry in ~%lu ms)...\n",
                              wifiSsid.c_str(), (unsigned long)wifiBackoffMs);
                WiFi.disconnect();
                WiFi.begin(wifiSsid.c_str(), wifiPass.c_str());
                udpServer.stop();
                udpServer.begin(UDP_DISCOVERY_PORT);
            }
        }
    }
    processLedBlink();
    
    // Periodic Cloud Snapshot Sync (Every 15 mins if connected)
    if (lastCloudSnapshotMs == 0) lastCloudSnapshotMs = millis();
    if (WiFi.status() == WL_CONNECTED && (millis() - lastCloudSnapshotMs > 900000)) {
        lastCloudSnapshotMs = millis();
        sendCloudSnapshot();
    }
}
