#include <Arduino.h>
#include <WiFi.h>
#include <Preferences.h>
#include <ESPmDNS.h>
#include "esp_wifi.h"
#include "Config.h"
#include "Security.h"
#include "HardwareManager.h"
#include "DeviceManager.h"
#include "WebServerModule.h"

static unsigned long lastWifiCheckTime = 0;
static unsigned long lastCloudSnapshotMs = 0;

void setup() {
    Serial.begin(115200);
    unsigned long start = millis();
    while (!Serial && (millis() - start < 2500));
    delay(300);

    Serial.println("\n--- HARDWARE-C3 Master Kiosk Controller ---");

    // Load NVS Configuration & Lifetime Vault Revenue safely
    loadAllConfig();
    lastWifiCheckTime = millis();

    // Initialize Dynamic Hardware Pins & Hardware Reset Pin (GPIO 2)
    applyCoinSlotHardwareConfig();
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
    // 0. Process Debounced Hardware-Conservative NVS Revenue Persistence
    processRevenuePersistence();

    // 0. Process Hardware Fallback Reset Pin (GPIO 2 -> GND for 5 seconds)
    processHardwareResetPin();

    // 1. Process Hardware Coin Detectors (Universal Pulse Sensor)
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
