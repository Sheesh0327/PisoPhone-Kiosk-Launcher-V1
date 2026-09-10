#include "DeviceManager.h"
#include "HardwareManager.h"
#include "Security.h"
#include "WebServerModule.h"
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>

extern QueueHandle_t authQueue;

bool pairDeviceToSlot(int slotNum, String devId, String ip, String name) {
    if (slotNum < 1 || slotNum > maxLicensedSlots) return false;
    int targetIdx = slotNum - 1;
    devId.trim();
    ip.trim();

    for (int i = 0; i < maxLicensedSlots; i++) {
        if (i != targetIdx && licenseSlots[i].deviceId.length() > 0 && licenseSlots[i].deviceId == devId) {
            licenseSlots[i].deviceId = "";
            licenseSlots[i].ip = "";
        }
    }

    licenseSlots[targetIdx].deviceId = devId;
    if (ip.length() > 0) licenseSlots[targetIdx].ip = ip;
    licenseSlots[targetIdx].name = name.length() > 0 ? name : ("PisoPhone " + String(slotNum));
    licenseSlots[targetIdx].active = true;

    saveSlotLicenses();
    Serial.printf("[+] Paired device %s (%s) to Slot #%d (requires credit allocation to arm)\n", devId.c_str(), ip.c_str(), slotNum);
    return true;
}

int findSlotIndexForDevice(String devId, String ip) {
    devId.trim();
    ip.trim();
    if (devId.length() > 0) {
        for (int i = 0; i < maxLicensedSlots; i++) {
            if (licenseSlots[i].deviceId.length() > 0 && licenseSlots[i].deviceId == devId) {
                return i;
            }
        }
    }
    if (ip.length() > 0 && ip != "127.0.0.1") {
        for (int i = 0; i < maxLicensedSlots; i++) {
            if (licenseSlots[i].ip.length() > 0 && licenseSlots[i].ip == ip) {
                return i;
            }
        }
    }
    return -1;
}

bool unpairSlot(int slotNum) {
    if (slotNum < 1 || slotNum > maxLicensedSlots) return false;
    int idx = slotNum - 1;
    String prevDevId = licenseSlots[idx].deviceId;
    String prevIp = licenseSlots[idx].ip;
    Serial.printf("[+] Unpairing Slot #%d (was %s / %s). Seat remains open.\n", slotNum, prevDevId.c_str(), prevIp.c_str());
    
    if (armedIp.length() > 0 && (armedIp == prevDevId || armedIp == prevIp)) {
        armedIp = "";
        armedUntil = 0;
        sessionStartTime = 0;
        if (isWsConnected && wsClient.connected()) {
            sendWsText(wsClient, "{\"event\":\"UNPAIRED\"}");
            wsClient.stop();
            isWsConnected = false;
        }
        Serial.println("[*] Active armed session disarmed due to unpair.");
    }
    if (lastArmedDeviceId == prevDevId || lastArmedIp == prevIp) {
        lastArmedDeviceId = "";
        lastArmedIp = "";
        lastArmedTimeMs = 0;
    }

    licenseSlots[idx].deviceId = "";
    licenseSlots[idx].ip = "";
    saveSlotLicenses();
    return true;
}

bool allocateCreditToSlot(int slotNum, String type, String& errorMsg) {
    if (slotNum < 1 || slotNum > maxLicensedSlots) {
        errorMsg = "Invalid slot number";
        return false;
    }
    int idx = slotNum - 1;
    type.toLowerCase();
    type.trim();

    uint64_t durationMs = 0;
    if (type == "month") {
        if (monthlyCredits < 1) {
            errorMsg = "No monthly credits available in vault. Please purchase credits on website.";
            return false;
        }
        monthlyCredits--;
        durationMs = 30ULL * 86400000ULL;
    } else if (type == "year") {
        if (annualCredits < 1) {
            errorMsg = "No annual credits available in vault. Please purchase credits on website.";
            return false;
        }
        annualCredits--;
        durationMs = 365ULL * 86400000ULL;
    } else if (type == "test") {
        if (testCredits > 0) testCredits--;
        durationMs = 120000ULL;
    } else {
        errorMsg = "Unknown credit type. Must be 'month', 'year', or 'test'";
        return false;
    }

    uint64_t currentMs = getCurrentMasterTimeMs();
    uint64_t baseTs = (licenseSlots[idx].expiresAt > currentMs) ? licenseSlots[idx].expiresAt : currentMs;
    licenseSlots[idx].expiresAt = baseTs + durationMs;
    licenseSlots[idx].active = true;

    saveCreditVault();
    saveSlotLicenses();
    Serial.printf("[+] Credit Allocated: Slot #%d +%s (New Expiry: %llu). Vault: M=%d, Y=%d, T=%d\n",
        slotNum, type.c_str(), (unsigned long long)licenseSlots[idx].expiresAt,
        monthlyCredits, annualCredits, testCredits);
    return true;
}

int getSlotExpirationStatus(int slotIdx, uint64_t currentMs, int& outDaysLeft) {
    outDaysLeft = -1;
    if (slotIdx < 0 || slotIdx >= maxLicensedSlots) {
        return 2;
    }
    if (!licenseSlots[slotIdx].active) {
        return 2;
    }
    if (licenseSlots[slotIdx].expiresAt == 0) {
        outDaysLeft = 0;
        return 2;
    }
    if (currentMs >= licenseSlots[slotIdx].expiresAt) {
        outDaysLeft = 0;
        return 2;
    }
    uint64_t diff = licenseSlots[slotIdx].expiresAt - currentMs;
    uint64_t oneDayMs = 86400000ULL;
    outDaysLeft = (int)(diff / oneDayMs);
    if (diff <= (7ULL * oneDayMs) || (diff <= 60000ULL)) {
        return 1;
    }
    return 0;
}

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
                changed = true;
            }
        }
        startIdx = comma + 1;
    }
    
    if (found) {
        for (int i = 0; i < maxLicensedSlots; i++) {
            if (licenseSlots[i].deviceId == deviceId && licenseSlots[i].deviceId.length() > 0) {
                if (licenseSlots[i].ip != ip) {
                    licenseSlots[i].ip = ip;
                    changed = true;
                }
                break;
            }
        }
    }
    
    if (changed) {
        saveSlotLicenses();
    }
}

String getDeviceNameByIpOrId(String reqIp, String devId) {
    int slotIdx = findSlotIndexForDevice(devId, reqIp);
    if (slotIdx >= 0) {
        return licenseSlots[slotIdx].name;
    }
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
                    return cfg.name.length() > 0 ? cfg.name : ("PisoPhone " + String(devNum));
                }
            }
            devNum++;
        }
        startIdx = comma + 1;
    }
    return devId.length() > 0 ? devId : "PisoPhone 1";
}

bool checkReplayProtection(String deviceId, unsigned long long newTs) {
    for (int i = 0; i < trackedDeviceCount; i++) {
        if (trackedDevices[i].deviceId == deviceId) {
            if (trackedDevices[i].lastNonceTs > 0 && newTs + 300000ULL < trackedDevices[i].lastNonceTs) {
                if (millis() - trackedDevices[i].lastSeenMs < 30000) {
                    return false;
                }
            }
            return true;
        }
    }
    return true;
}

bool verifyTelemetryAuth(String deviceId, String tsStr, String sig) {
    if (deviceId.length() == 0) return false;
    if (sharedSecret.length() == 0) {
        return true;
    }
    String expectedSig = calculateHMAC(deviceId + ":" + tsStr, sharedSecret);
    if (!sig.equalsIgnoreCase(expectedSig)) {
        return false;
    }
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

void updateDeviceTelemetry(String deviceId, String ip, int timeRemaining, int state, int battery, bool charging, unsigned long long ts) {
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

int getTrackedTimeRemaining(String ip, unsigned long maxAgeMs, String devId) {
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

int getTrackedBatteryLevel(String ip, String devId) {
    for (int i = 0; i < trackedDeviceCount; i++) {
        bool match = (trackedDevices[i].lastKnownIp == ip || trackedDevices[i].deviceId == ip);
        if (!match && devId.length() > 0 && trackedDevices[i].deviceId == devId) match = true;
        if (match) {
            return trackedDevices[i].batteryLevel;
        }
    }
    return 100;
}

bool getTrackedChargingState(String ip, String devId) {
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

String getPrimaryTerminalIp() {
    if (armedIp.length() > 0) {
        int slotIdx = findSlotIndexForDevice(armedIp, "");
        if (slotIdx >= 0 && licenseSlots[slotIdx].deviceId.length() > 0) {
            String ip = getIpFromDeviceId(armedIp);
            if (ip.length() > 0 && ip != "127.0.0.1") return ip;
        }
    }
    unsigned long bestSeen = 0;
    String bestIp = "";
    for (int i = 0; i < maxLicensedSlots; i++) {
        if (licenseSlots[i].deviceId.length() > 0 && licenseSlots[i].ip.length() > 0 && licenseSlots[i].ip != "127.0.0.1") {
            for (int t = 0; t < trackedDeviceCount; t++) {
                if (trackedDevices[t].deviceId == licenseSlots[i].deviceId || trackedDevices[t].lastKnownIp == licenseSlots[i].ip) {
                    if (trackedDevices[t].lastSeenMs > bestSeen) {
                        bestSeen = trackedDevices[t].lastSeenMs;
                        bestIp = licenseSlots[i].ip;
                    }
                }
            }
        }
    }
    if (bestIp.length() > 0 && (millis() - bestSeen < 120000)) return bestIp;

    for (int i = 0; i < maxLicensedSlots; i++) {
        if (licenseSlots[i].deviceId.length() > 0 && licenseSlots[i].ip.length() > 0 && licenseSlots[i].ip != "127.0.0.1") {
            return licenseSlots[i].ip;
        }
    }
    return "";
}

void sendAddTime(int minutes, String targetIp, String txId) {
    if (androidIps.length() == 0) return;
    int startIdx = 0;
    uint64_t currentMs = getCurrentMasterTimeMs();
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
                    int daysLeft = -1;
                    int expStatus = getSlotExpirationStatus(slotIdx, currentMs, daysLeft);
                    if (expStatus == 2) {
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
    
    bool wasArmed = isSlotArmed() || (millis() - lastArmedTimeMs < 10000);
    if (relayMode == 1 && !wasArmed) {
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

    bool wasArmed = isSlotArmed() || pulseTrainWasArmed || (millis() - lastArmedTimeMs < 10000);
    if (relayMode == 1 && !wasArmed) {
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
    if (WiFi.status() != WL_CONNECTED) return;
    if (ESP.getFreeHeap() < 35000) {
        Serial.printf("[☁️ CLOUD] Skipping snapshot report, free heap low (%u bytes)\n", ESP.getFreeHeap());
        return;
    }
    
    WiFiClientSecure client;
    client.setInsecure();
    client.setTimeout(4);

    HTTPClient http;
    http.setTimeout(4000);
    if (!http.begin(client, "https://pisophone-api.pisophone-support.workers.dev/api/box/report-snapshot")) {
        return;
    }
    http.addHeader("Content-Type", "application/json");

    String json = "{";
    json += "\"mac\":\"" + macAddressStr + "\",";
    json += "\"tier\":" + String(maxLicensedSlots) + ",";
    json += "\"lifetimeCoins\":" + String(totalCoinsLifetime) + ",";
    json += "\"lifetimeEarnings\":" + String(totalEarningsLifetime, 2) + ",";
    json += "\"wifiSsid\":\"" + wifiSsid + "\",";
    json += "\"firmwareVersion\":\"2.4.0-SLOT-MANAGER\",";
    json += "\"slots\":[";
    for (int i = 0; i < maxLicensedSlots; i++) {
        if (i > 0) json += ",";
        char expBuf[24];
        snprintf(expBuf, sizeof(expBuf), "%llu", (unsigned long long)licenseSlots[i].expiresAt);
        json += "{";
        json += "\"slotNum\":" + String(licenseSlots[i].slotNum) + ",";
        json += "\"deviceId\":\"" + licenseSlots[i].deviceId + "\",";
        json += "\"ip\":\"" + licenseSlots[i].ip + "\",";
        json += "\"name\":\"" + licenseSlots[i].name + "\",";
        json += "\"expiresAt\":" + String(expBuf);
        json += "}";
    }
    json += "]}";

    int code = http.POST(json);
    if (code > 0) {
        Serial.printf("[☁️ CLOUD] Snapshot reported successfully (HTTP %d)\n", code);
    } else {
        Serial.printf("[☁️ CLOUD] Snapshot report failed: %s\n", http.errorToString(code).c_str());
    }
    http.end();
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
