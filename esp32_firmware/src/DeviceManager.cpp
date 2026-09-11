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
    String cleanName = "PisoPhone " + String(slotNum);
    licenseSlots[targetIdx].name = cleanName;
    licenseSlots[targetIdx].active = true;

    saveSlotLicenses();
    Serial.printf("[+] Paired device %s (%s) to Slot #%d -> '%s'\n", devId.c_str(), ip.c_str(), slotNum, cleanName.c_str());

    // Actively push config to device on pairing
    if (ip.length() > 0 && ip != "127.0.0.1") {
        String pushParams = "price=" + String(coinPrice) + 
                            "&minutes=" + String(minutesPerCoin) + 
                            "&device_name=" + urlEncode(cleanName) + 
                            "&slot=" + String(slotNum) + 
                            "&slot_num=" + String(slotNum) +
                            "&admin_pin=" + webPassword;
        sendAuthenticated(ip, targetPort, "/config", "/challenge", pushParams, 1000);
    }
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

    if (prevIp.length() > 0 && prevIp != "127.0.0.1") {
        sendAuthenticated(prevIp, targetPort, "/trigger_action", "/challenge", "action=slot_lockdown&slot_num=" + String(slotNum) + "&slot=" + String(slotNum), 1000);
    }
    return true;
}


bool isSlotActive(int slotIdx) {
    if (slotIdx < 0 || slotIdx >= maxLicensedSlots) {
        return false;
    }
    return licenseSlots[slotIdx].active;
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
        return "PisoPhone " + String(licenseSlots[slotIdx].slotNum);
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
                    int sIdx = findSlotIndexForDevice(cfg.id, cfg.ip);
                    if (sIdx >= 0) return "PisoPhone " + String(licenseSlots[sIdx].slotNum);
                    return "PisoPhone " + String(devNum);
                }
            }
            devNum++;
        }
        startIdx = comma + 1;
    }
    return "PisoPhone";
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

