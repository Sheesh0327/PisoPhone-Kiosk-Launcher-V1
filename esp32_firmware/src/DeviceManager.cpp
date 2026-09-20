#include "DeviceManager.h"
#include "CoinSlotManager.h"
#include "HardwareManager.h"
#include "PaymentQueueManager.h"
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
        String pushParams = "device_name=" + urlEncode(cleanName) + 
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

    if (prevDevId.length() == 0 && prevIp.length() == 0) {
        return true; // Already unpaired
    }

    String activeDev = getActiveCoinSessionId();
    bool ownsActiveSession = (activeDev.length() > 0 && (activeDev == prevDevId || activeDev == prevIp));
    if (ownsActiveSession) {
        CoinSlotState state = getCoinSlotState();
        if (state == CoinSlotState::ARMED || state == CoinSlotState::DRAINING || state == CoinSlotState::RESERVED_ARMING) {
            Serial.printf("[-] Unpair slot #%d rejected: Device %s owns active/draining session.\n", slotNum, prevDevId.c_str());
            return false;
        }
    }

    if ((prevDevId.length() > 0 && hasPendingPaymentsForTarget(prevDevId)) ||
        (prevIp.length() > 0 && hasPendingPaymentsForTarget(prevIp))) {
        Serial.printf("[-] Unpair slot #%d rejected: Device %s has unresolved payments in queue.\n", slotNum, prevDevId.c_str());
        return false;
    }

    Serial.printf("[+] Unpairing Slot #%d (was %s / %s). Seat remains open.\n", slotNum, prevDevId.c_str(), prevIp.c_str());

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
                    if (cfg.name.length() > 0 && cfg.name != devId && cfg.name.indexOf(devId) == -1) {
                        return cfg.name;
                    }
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
    if (deviceId.length() == 0 || newTs == 0) return false;
    
    // Master clock window check: Only enforce once master clock is synchronized (> 0).
    // Rejects packets older than 5 minutes or more than 5 minutes in the future.
    unsigned long long currentMasterTs = getCurrentMasterTimeMs();
    if (currentMasterTs > 300000ULL) {
        if (newTs < (currentMasterTs - 300000ULL) || newTs > (currentMasterTs + 300000ULL)) {
            return false;
        }
    }

    for (int i = 0; i < trackedDeviceCount; i++) {
        if (trackedDevices[i].deviceId == deviceId) {
            // Allow up to 30 seconds (30000 ms) of async network jitter / concurrent requests.
            // Reject any request older than (lastNonceTs - 30000 ms).
            if (trackedDevices[i].lastNonceTs > 30000ULL && newTs + 30000ULL < trackedDevices[i].lastNonceTs) {
                return false;
            }
            return true;
        }
    }
    return true;
}

bool verifyTelemetryAuth(String deviceId, String tsStr, String sig) {
    if (deviceId.length() == 0) return false;
    String secKey = (sharedSecret.length() > 0) ? sharedSecret : String(MASTER_CRYPTO_SECRET);
    String expectedSig = calculateHMAC(deviceId + ":" + tsStr, secKey);
    bool valid = sig.equalsIgnoreCase(expectedSig);
    if (!valid && sharedSecret.length() > 0 && sharedSecret != MASTER_CRYPTO_SECRET) {
        String masterExpectedSig = calculateHMAC(deviceId + ":" + tsStr, MASTER_CRYPTO_SECRET);
        valid = sig.equalsIgnoreCase(masterExpectedSig);
    }
    if (!valid) {
        return false;
    }
    unsigned long long ts = strtoull(tsStr.c_str(), NULL, 10);
    return checkReplayProtection(deviceId, ts);
}

void recordDeviceNonce(String deviceId, unsigned long long ts) {
    if (deviceId.length() == 0 || ts == 0) return;
    for (int i = 0; i < trackedDeviceCount; i++) {
        if (trackedDevices[i].deviceId == deviceId) {
            if (ts > trackedDevices[i].lastNonceTs) {
                trackedDevices[i].lastNonceTs = ts;
            }
            return;
        }
    }
    if (trackedDeviceCount < MAX_TRACKED_DEVICES) {
        trackedDevices[trackedDeviceCount].deviceId = deviceId;
        trackedDevices[trackedDeviceCount].lastKnownIp = "";
        trackedDevices[trackedDeviceCount].timeRemainingSeconds = -1;
        trackedDevices[trackedDeviceCount].state = 0;
        trackedDevices[trackedDeviceCount].batteryLevel = -1;
        trackedDevices[trackedDeviceCount].isCharging = false;
        trackedDevices[trackedDeviceCount].lastSeenMs = 0;
        trackedDevices[trackedDeviceCount].lastNonceTs = ts;
        trackedDevices[trackedDeviceCount].isApp = false;
        trackedDeviceCount++;
    }
}

void updateDeviceTelemetry(String deviceId, String ip, int timeRemaining, int state, int battery, bool charging, unsigned long long ts, bool isApp) {
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
            if (ts > trackedDevices[i].lastNonceTs) trackedDevices[i].lastNonceTs = ts;
            if (isApp) trackedDevices[i].isApp = true;
            return;
        }
    }
    if (trackedDeviceCount < MAX_TRACKED_DEVICES) {
        trackedDevices[trackedDeviceCount].deviceId = deviceId;
        trackedDevices[trackedDeviceCount].lastKnownIp = ip;
        trackedDevices[trackedDeviceCount].timeRemainingSeconds = timeRemaining;
        trackedDevices[trackedDeviceCount].state = state;
        trackedDevices[trackedDeviceCount].batteryLevel = validBattery;
        trackedDevices[trackedDeviceCount].isCharging = charging;
        trackedDevices[trackedDeviceCount].lastSeenMs = millis();
        trackedDevices[trackedDeviceCount].lastNonceTs = ts;
        trackedDevices[trackedDeviceCount].isApp = isApp;
        trackedDeviceCount++;
    }
}

int getTrackedTimeRemaining(String ip, unsigned long maxAgeMs, String devId) {
    for (int i = 0; i < trackedDeviceCount; i++) {
        bool match = (trackedDevices[i].lastKnownIp == ip || trackedDevices[i].deviceId == ip);
        if (!match && devId.length() > 0 && trackedDevices[i].deviceId == devId) match = true;
        if (match && trackedDevices[i].lastSeenMs > 0) {
            if (millis() - trackedDevices[i].lastSeenMs <= maxAgeMs) {
                if (trackedDevices[i].timeRemainingSeconds < 0) return 0;
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
    return -1;
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
    id.trim();
    if (id.length() == 0) return "";

    // 1. Check paired license slots (authenticated binding)
    int slotIdx = findSlotIndexForDevice(id, "");
    if (slotIdx >= 0 && isSlotActive(slotIdx)) {
        String slotIp = licenseSlots[slotIdx].ip;
        slotIp.trim();
        if (slotIp.length() > 0 && slotIp != "127.0.0.1") {
            return slotIp;
        }
    }

    // 2. Check static configured androidIps
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

    // 3. If the input itself is already a valid IPv4 address
    IPAddress ipAddr;
    if (ipAddr.fromString(id)) {
        return id;
    }

    // Unresolved identity; never return deviceId as IP
    return "";
}

String getDeviceIdFromIp(String ip) {
    int startIdx = 0;
    while (startIdx < androidIps.length()) {
        int comma = androidIps.indexOf(',', startIdx);
        if (comma == -1) comma = androidIps.length();
        String entry = androidIps.substring(startIdx, comma);
        entry.trim();
        if (entry.length() > 0) {
            DeviceConfig cfg;
            if (parseDeviceEntry(entry, cfg)) {
                if (cfg.ip == ip) {
                    return cfg.id;
                }
            }
        }
        startIdx = comma + 1;
    }
    return "";
}

String getPrimaryTerminalIp() {
    String currentSession = getActiveCoinSessionId();
    if (currentSession.length() > 0) {
        int slotIdx = findSlotIndexForDevice(currentSession, "");
        if (slotIdx >= 0 && licenseSlots[slotIdx].deviceId.length() > 0) {
            String ip = getIpFromDeviceId(currentSession);
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
