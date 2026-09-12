#include "DeviceNetwork.h"
#include "DeviceManager.h"
#include "HardwareManager.h"
#include "Security.h"
#include "WebServerModule.h"
#include <WiFiClientSecure.h>
#include <HTTPClient.h>

void sendAddTime(int minutes, String targetIp, String txId) {
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
                    int slotIdx = findSlotIndexForDevice(cfg.id, cfg.ip);
                    bool isActive = isSlotActive(slotIdx);
                    if (!isActive) {
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

void triggerCoinEvent() {
    Serial.printf("[+] Physical coin pulse detected on GPIO %d (Simple Beam Sensor)!\n", coinPin);
    
    if (relayMode == 1 && !isSlotArmed()) {
        Serial.printf("[-] Dropped coin rejected: Slot is in Armed-Only mode and is NOT armed!\n");
        return;
    }

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
        }
    }

    uint32_t beamCreditPhp = (coinPrice > 0.0f) ? (uint32_t)round(coinPrice) : 1;
    totalCoinsLifetime += beamCreditPhp;
    totalCoinsSession += beamCreditPhp;
    totalEarningsLifetime += coinPrice;
    totalEarningsSession += coinPrice;

    revenueDirty = true;
    lastCoinChangeTime = millis();

    triggerLedBlink();

    unsigned long long ts = (unsigned long long)millis();
    String txId = String(millis()) + "-" + String(random(1000, 9999));
    int addedSeconds = minutesPerCoin * 60;

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
    } else {
        for (int i = 0; i < maxLicensedSlots; i++) {
            if (licenseSlots[i].deviceId.length() > 0 && licenseSlots[i].ip.length() > 0 && licenseSlots[i].ip != "127.0.0.1") {
                Serial.printf("[⚡ FALLBACK] Dispatching coin to paired slot device IP: %s\n", licenseSlots[i].ip.c_str());
                sendAuthenticated(licenseSlots[i].ip, targetPort, "/add_time", "/challenge", "minutes=" + String(minutesPerCoin) + "&seconds=" + String(addedSeconds) + "&amount=" + String(coinPrice, 2) + "&tx_id=" + txId, 1000);
            }
        }
    }
}

void triggerUniversalCoinEvent(int pulses) {
    if (pulses <= 0) return;
    Serial.printf("[⚡ UNIVERSAL COIN] %d total pulses accumulated on GPIO %d (₱%d PHP)\n", pulses, universalCoinPin, pulses);

    if (relayMode == 1 && !isSlotArmed()) {
        Serial.printf("[-] Universal coin pulses rejected: Slot is in Armed-Only mode and is NOT armed!\n");
        return;
    }

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
        }
    }

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

    revenueDirty = true;
    lastCoinChangeTime = millis();

    triggerLedBlink(pulses > 1 ? 4 : 2);

    unsigned long long ts = (unsigned long long)millis();
    String txId = String(millis()) + "-" + String(random(1000, 9999));

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
    } else {
        for (int i = 0; i < maxLicensedSlots; i++) {
            if (licenseSlots[i].deviceId.length() > 0 && licenseSlots[i].ip.length() > 0 && licenseSlots[i].ip != "127.0.0.1") {
                Serial.printf("[⚡ FALLBACK] Dispatching coin to paired slot device IP: %s\n", licenseSlots[i].ip.c_str());
                sendAuthenticated(licenseSlots[i].ip, targetPort, "/add_time", "/challenge", "minutes=" + String(addedMinutes) + "&seconds=" + String(addedSeconds) + "&amount=" + String(pulses) + "&tx_id=" + txId, 1000);
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

void sendCloudSnapshot() {
    // Retiring outbound telemetry to Cloudflare Worker. Strictly Pages-only now.
}

void sendAuthenticated(String ip, int port, String actionPath, String challengePath, String params, int timeoutMs) {
    if (WiFi.status() != WL_CONNECTED || authQueue == NULL) return;
    
    AuthRequest req;
    memset(&req, 0, sizeof(AuthRequest));
    strncpy(req.ip, ip.c_str(), sizeof(req.ip) - 1);
    req.port = port;
    strncpy(req.actionPath, actionPath.c_str(), sizeof(req.actionPath) - 1);
    strncpy(req.challengePath, challengePath.c_str(), sizeof(req.challengePath) - 1);
    strncpy(req.params, params.c_str(), sizeof(req.params) - 1);
    req.timeoutMs = timeoutMs;
    
    xQueueSend(authQueue, &req, 0);
}

String urlEncode(const String &str) {
    String encoded = "";
    for (size_t i = 0; i < str.length(); i++) {
        char c = str.charAt(i);
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
