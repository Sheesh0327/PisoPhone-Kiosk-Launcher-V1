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
    Serial.printf("[+] Hardware Pins bound: Beam Coin Pin = GPIO %d, Universal Multi-Coin Pin = GPIO %d (ISR active), LED Pin = GPIO %d, Relay Pin = GPIO %d (ActiveLow=%s, Mode=%d), Reset Pin = GPIO %d\n",
        coinPin, universalCoinPin, ledPin, relayPin, relayActiveLow ? "true" : "false", relayMode, HARDWARE_RESET_PIN);

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
