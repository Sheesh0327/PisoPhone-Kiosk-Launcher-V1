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

#define WDT_TIMEOUT_SECONDS 15
#define DAILY_MAINTENANCE_INTERVAL_MS 86400000UL // 24 Hours
#define MIN_SAFE_HEAP_BYTES 15000                 // 15 KB Critical Heap Limit

static unsigned long lastWifiCheckTime = 0;
static unsigned long lastCloudSnapshotMs = 0;
static unsigned long lastHealthCheckMs = 0;

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
    if (now - lastHealthCheckMs < 10000) return; // Check every 10 seconds
    lastHealthCheckMs = now;

    uint32_t freeHeap = ESP.getFreeHeap();
    bool heapCritical = (freeHeap < MIN_SAFE_HEAP_BYTES);
    bool dailyWindowReached = (now > DAILY_MAINTENANCE_INTERVAL_MS);

    if ((heapCritical || dailyWindowReached) && !isCoinSlotArmed()) {
        if (heapCritical) {
            Serial.printf("⚠️ [HEALTH GUARD] Free heap low (%u bytes < %d bytes threshold). Initiating safety reboot...\n", freeHeap, MIN_SAFE_HEAP_BYTES);
        } else {
            Serial.printf("ℹ️ [HEALTH GUARD] 24-hour uptime maintenance window reached. Initiating scheduled reboot...\n");
        }
        Serial.flush();
        delay(100);
        ESP.restart();
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

    // Load NVS Configuration & Lifetime Vault Revenue safely
    loadAllConfig();
    lastWifiCheckTime = millis();

    // Initialize Dynamic Hardware Pins & Hardware Reset Pin (GPIO 2)
    applyCoinSlotHardwareConfig();
    initCoinSlotManager();
    setGlobalCoinPaymentCallback([](const String& sessionId, int pulses) {
        triggerUniversalCoinEvent(pulses, sessionId);
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
    // Feed Hardware Watchdog Timer
    esp_task_wdt_reset();

    // Memory and Uptime Health Maintenance Check
    processSystemHealthAndAutoMaintenance();

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
    
    // 7. Robust Non-Blocking Wi-Fi Reconnection Watchdog & LED Status Sync
    if (WiFi.status() == WL_CONNECTED) {
        currentLedState = LED_STATE_CONNECTED;
    } else {
        if (millis() - lastWifiCheckTime < 20000) {
            currentLedState = LED_STATE_CONNECTING;
        } else {
            currentLedState = LED_STATE_FAILED;
            
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
    if (lastCloudSnapshotMs == 0) lastCloudSnapshotMs = millis();
    if (WiFi.status() == WL_CONNECTED && (millis() - lastCloudSnapshotMs > 900000)) {
        lastCloudSnapshotMs = millis();
        sendCloudSnapshot();
    }
}
