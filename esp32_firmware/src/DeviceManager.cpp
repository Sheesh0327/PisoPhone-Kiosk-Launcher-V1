#include "DeviceManager.h"
#include "CoinSlotManager.h"
#include "HardwareManager.h"
#include "Security.h"
#include "WebServerModule.h"
#include <WiFi.h>

extern QueueHandle_t authQueue;

// ============================================================================
// INTERNAL HELPERS
// ============================================================================
static DeviceTelemetry* findTrackedDevice(const String& ip, const String& devId) {
    for (int i = 0; i < trackedDeviceCount; i++) {
        if (devId.length() > 0 && trackedDevices[i].deviceId == devId) {
            return &trackedDevices[i];
        }
        if (ip.length() > 0 && (trackedDevices[i].lastKnownIp == ip || trackedDevices[i].deviceId == ip)) {
            return &trackedDevices[i];
        }
    }
    return nullptr;
}

// ============================================================================
// SLOT MANAGEMENT & PAIRING
// ============================================================================
bool pairDeviceToSlot(int slotNum, String devId, String ip, String name) {
    if (slotNum < 1 || slotNum > maxLicensedSlots) return false;
    int targetIdx = slotNum - 1;
    devId.trim();
    ip.trim();

    // Clear devId from any other slot to avoid duplicates
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
    if (ip.length() > 0 && ip != "127.0.0.1" && ip != "0.0.0.0") {
        for (int i = 0; i < maxLicensedSlots; i++) {
            if (licenseSlots[i].deviceId.length() > 0 && licenseSlots[i].ip.length() > 0 && licenseSlots[i].ip == ip) {
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
    Serial.printf("[+] Unpairing Slot #%d (was %s / %s).\n", slotNum, prevDevId.c_str(), prevIp.c_str());

    String activeDev = getActiveCoinSessionId();
    if (getActiveCoinOwnerType() == CoinSlotOwnerType::PHONE &&
        activeDev.length() > 0 && (activeDev == prevDevId || activeDev == prevIp)) {
        if (isWsConnected && wsClient.connected()) {
            sendWsText(wsClient, "{\"event\":\"UNPAIRED\"}");
            wsClient.stop();
            isWsConnected = false;
        }
        releaseCoinSlot(activeDev, CoinSlotOwnerType::PHONE, true, "UNPAIRED");
        Serial.println("[*] Active armed session disarmed due to unpair.");
    }

    licenseSlots[idx].deviceId = "";
    licenseSlots[idx].ip = "";
    saveSlotLicenses();

    if (prevIp.length() > 0 && prevIp != "127.0.0.1") {
        sendAuthenticated(prevIp, targetPort, "/trigger_action", "/challenge", 
                          "action=slot_lockdown&slot_num=" + String(slotNum) + "&slot=" + String(slotNum), 1000);
    }
    return true;
}

bool isSlotActive(int slotIdx) {
    if (slotIdx < 0 || slotIdx >= maxLicensedSlots) return false;
    return licenseSlots[slotIdx].active;
}

void updateDynamicDeviceList(String deviceId, String ip) {
    deviceId.trim();
    ip.trim();
    if (ip.length() < 7 || ip.indexOf('.') == -1 || ip == "127.0.0.1" || ip == "0.0.0.0") return;

    bool changed = false;
    // Update licenseSlots IP if device matches
    for (int i = 0; i < maxLicensedSlots; i++) {
        if (licenseSlots[i].deviceId.length() > 0 && licenseSlots[i].deviceId == deviceId) {
            if (licenseSlots[i].ip != ip) {
                licenseSlots[i].ip = ip;
                changed = true;
            }
            break;
        }
    }

    // Update androidIps string
    String newIps = "";
    bool found = false;
    forEachConfiguredDevice([&](const DeviceConfig& cfg) {
        DeviceConfig updated = cfg;
        bool match = (deviceId.length() > 0 && cfg.id.length() > 0 && cfg.id == deviceId) || (cfg.ip == ip);
        if (match) {
            found = true;
            if (deviceId.length() > 0) updated.id = deviceId;
            if (updated.ip != ip) {
                updated.ip = ip;
                changed = true;
            }
        }
        if (newIps.length() > 0) newIps += ",";
        newIps += updated.id + "|" + updated.ip + "|" + updated.name;
        return true;
    });

    if (changed) {
        if (newIps.length() > 0) androidIps = newIps;
        saveSlotLicenses();
    }
}

String getDeviceNameByIpOrId(String reqIp, String devId) {
    int slotIdx = findSlotIndexForDevice(devId, reqIp);
    if (slotIdx >= 0) {
        return "PisoPhone " + String(licenseSlots[slotIdx].slotNum);
    }

    String foundName = "";
    int devNum = 1;
    forEachConfiguredDevice([&](const DeviceConfig& cfg) {
        if ((devId.length() > 0 && cfg.id == devId) || (reqIp.length() > 0 && cfg.ip == reqIp)) {
            if (cfg.name.length() > 0 && cfg.name != devId && cfg.name.indexOf(devId) == -1) {
                foundName = cfg.name;
            } else {
                foundName = "PisoPhone " + String(devNum);
            }
            return false; // Stop iterating
        }
        devNum++;
        return true;
    });

    return foundName.length() > 0 ? foundName : "PisoPhone";
}

// ============================================================================
// SECURITY & REPLAY PROTECTION
// ============================================================================
bool checkReplayProtection(String deviceId, unsigned long long newTs) {
    if (deviceId.length() == 0 || newTs == 0) return false;

    // Master clock window check: Reject packets older than 5 mins or > 5 mins in future
    unsigned long long currentMasterTs = getCurrentMasterTimeMs();
    if (currentMasterTs > 300000ULL) {
        if (newTs < (currentMasterTs - 300000ULL) || newTs > (currentMasterTs + 300000ULL)) {
            return false;
        }
    }

    DeviceTelemetry* dev = findTrackedDevice("", deviceId);
    if (dev) {
        // Allow up to 30s jitter
        if (dev->lastNonceTs > 30000ULL && newTs + 30000ULL < dev->lastNonceTs) {
            return false;
        }
    }
    return true;
}

bool verifyTelemetryAuth(String deviceId, String tsStr, String sig) {
    if (deviceId.length() == 0) return false;
    String expectedSig = calculateHMAC(deviceId + ":" + tsStr, MASTER_CRYPTO_SECRET);
    if (!sig.equalsIgnoreCase(expectedSig)) return false;

    unsigned long long ts = strtoull(tsStr.c_str(), NULL, 10);
    return checkReplayProtection(deviceId, ts);
}

void recordDeviceNonce(String deviceId, unsigned long long ts) {
    if (deviceId.length() == 0 || ts == 0) return;

    DeviceTelemetry* dev = findTrackedDevice("", deviceId);
    if (dev) {
        if (ts > dev->lastNonceTs) dev->lastNonceTs = ts;
        return;
    }

    if (trackedDeviceCount < MAX_TRACKED_DEVICES) {
        DeviceTelemetry& newDev = trackedDevices[trackedDeviceCount++];
        newDev.deviceId = deviceId;
        newDev.lastKnownIp = "";
        newDev.deviceName = "";
        newDev.timeRemainingSeconds = -1;
        newDev.state = 0;
        newDev.batteryLevel = -1;
        newDev.isCharging = false;
        newDev.lastSeenMs = millis();
        newDev.lastNonceTs = ts;
        newDev.isApp = (deviceId.length() > 0 && !deviceId.startsWith("DEV_"));
    }
}

// ============================================================================
// TELEMETRY & DEVICE STATE TRACKING
// ============================================================================
void updateDeviceTelemetry(String deviceId, String ip, int timeRemaining, int state, 
                           int battery, bool charging, unsigned long long ts, bool isApp, String name) {
    ip.trim();
    if (ip == "127.0.0.1") ip = "";
    if (ip.length() > 0) {
        updateDynamicDeviceList(deviceId, ip);
    }

    int validBattery = (battery >= 0 && battery <= 100) ? battery : -1;
    DeviceTelemetry* dev = findTrackedDevice(ip, deviceId);

    if (dev) {
        if (deviceId.length() > 0) dev->deviceId = deviceId;
        if (ip.length() > 0) dev->lastKnownIp = ip;
        if (name.length() > 0) dev->deviceName = name;
        dev->timeRemainingSeconds = timeRemaining;
        dev->state = state;
        if (validBattery >= 0) dev->batteryLevel = validBattery;
        dev->isCharging = charging;
        dev->lastSeenMs = millis();
        if (ts > dev->lastNonceTs) dev->lastNonceTs = ts;
        if (isApp) dev->isApp = true;
        return;
    }

    if (trackedDeviceCount < MAX_TRACKED_DEVICES) {
        DeviceTelemetry& newDev = trackedDevices[trackedDeviceCount++];
        newDev.deviceId = deviceId;
        newDev.lastKnownIp = ip;
        newDev.deviceName = (name.length() > 0) ? name : "PisoPhone Terminal";
        newDev.timeRemainingSeconds = timeRemaining;
        newDev.state = state;
        newDev.batteryLevel = validBattery;
        newDev.isCharging = charging;
        newDev.lastSeenMs = millis();
        newDev.lastNonceTs = ts;
        newDev.isApp = isApp;
    }
}

int getTrackedTimeRemaining(String ip, unsigned long maxAgeMs, String devId) {
    DeviceTelemetry* dev = findTrackedDevice(ip, devId);
    if (!dev || dev->lastSeenMs == 0) return -1;
    if (millis() - dev->lastSeenMs > maxAgeMs) return -1;

    if (dev->timeRemainingSeconds < 0) return 0;
    unsigned long elapsedSec = (millis() - dev->lastSeenMs) / 1000;
    int remaining = dev->timeRemainingSeconds - (int)elapsedSec;
    return (remaining > 0) ? remaining : 0;
}

int getTrackedBatteryLevel(String ip, String devId) {
    DeviceTelemetry* dev = findTrackedDevice(ip, devId);
    return dev ? dev->batteryLevel : -1;
}

bool getTrackedChargingState(String ip, String devId) {
    DeviceTelemetry* dev = findTrackedDevice(ip, devId);
    return dev ? dev->isCharging : false;
}

String getIpFromDeviceId(String id) {
    if (id.length() == 0) return "";

    // 1. Check licensed slots (canonical mapping)
    for (int i = 0; i < maxLicensedSlots; i++) {
        if (licenseSlots[i].deviceId.length() > 0 && licenseSlots[i].deviceId == id) {
            if (licenseSlots[i].ip.length() > 0 && licenseSlots[i].ip != "127.0.0.1") {
                return licenseSlots[i].ip;
            }
        }
    }

    // 2. Check tracked devices (active telemetry IP)
    for (int i = 0; i < trackedDeviceCount; i++) {
        if (trackedDevices[i].deviceId == id) {
            if (trackedDevices[i].lastKnownIp.length() > 0 && trackedDevices[i].lastKnownIp != "127.0.0.1") {
                return trackedDevices[i].lastKnownIp;
            }
        }
    }

    // 3. Check configured device entries
    String foundIp = "";
    forEachConfiguredDevice([&](const DeviceConfig& cfg) {
        if (cfg.id == id || cfg.ip == id) {
            foundIp = cfg.ip;
            return false;
        }
        return true;
    });
    if (foundIp.length() > 0) return foundIp;

    // Last resort: the id may itself be an address, either bare or as "DEV_<ip>" (unidentified
    // devices). Anything else is not routable.
    String candidate = id.startsWith("DEV_") ? id.substring(4) : id;
    IPAddress parsed;
    if (parsed.fromString(candidate)) return candidate;
    return "";
}

String getDeviceIdFromIp(String ip) {
    if (ip.length() == 0) return "";

    // 1. Check licensed slots
    for (int i = 0; i < maxLicensedSlots; i++) {
        if (licenseSlots[i].ip.length() > 0 && licenseSlots[i].ip == ip) {
            if (licenseSlots[i].deviceId.length() > 0) {
                return licenseSlots[i].deviceId;
            }
        }
    }

    // 2. Check tracked devices
    for (int i = 0; i < trackedDeviceCount; i++) {
        if (trackedDevices[i].lastKnownIp == ip) {
            if (trackedDevices[i].deviceId.length() > 0) {
                return trackedDevices[i].deviceId;
            }
        }
    }

    // 3. Check configured devices
    String foundId = "";
    forEachConfiguredDevice([&](const DeviceConfig& cfg) {
        if (cfg.ip == ip) {
            foundId = cfg.id;
            return false;
        }
        return true;
    });
    return foundId;
}
