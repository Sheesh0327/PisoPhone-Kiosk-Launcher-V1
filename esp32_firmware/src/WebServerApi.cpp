#include "WebServerApi.h"
#include "WebServerModule.h"
#include "WebServerAuth.h"
#include "Config.h"
#include "Security.h"
#include "HardwareManager.h"
#include "DeviceManager.h"
#include "CoinSlotManager.h"
#include "DeviceNetwork.h"
#include "PaymentQueueManager.h"
#include <WiFi.h>
#include <WebServer.h>

void handleAddTime() {
    if (!checkAdminAuth()) return;
    int minutes = 60;
    if (webServer.hasArg("add_minutes")) {
        minutes = webServer.arg("add_minutes").toInt();
    }
    if (webServer.hasArg("adjust_action") && webServer.arg("adjust_action") == "subtract") {
        minutes = -abs(minutes);
    }
    String targetIp = webServer.hasArg("target_ip") ? webServer.arg("target_ip") : "ALL";

    // Enforce Expiration Check (RULE 6: Single verification path)
    if (targetIp != "ALL") {
        DeviceConfig targetCfg;
        targetCfg.ip = targetIp;
        forEachConfiguredDevice([&](const DeviceConfig& cfg) {
            if (cfg.ip == targetIp) {
                targetCfg = cfg;
                return false;
            }
            return true;
        });

        int slotIdx = findSlotIndexForDevice(targetCfg.id, targetCfg.ip);
        bool isActive = isSlotActive(slotIdx);
        if (!isActive) {
            Serial.printf("[-] handleAddTime blocked: Target device %s (Slot #%d) is EXPIRED!\n",
                targetIp.c_str(), (slotIdx >= 0) ? licenseSlots[slotIdx].slotNum : 0);
            quickTimeStatusMsg = "<div style='background:#fee2e2;color:#dc2626;padding:10px 14px;border-radius:8px;margin-bottom:12px;font-size:12px;font-weight:700;border:1px solid rgba(239,68,68,0.3);'>❌ Adjustment Blocked: Target device " + targetIp + " is EXPIRED! Add credits in the Master Credit Vault to pair device.</div>";
            redirectHome();
            return;
        }
    }

    sendAddTime(minutes, targetIp);
    quickTimeStatusMsg = "<div style='background:#e8f5e9;color:#2e7d32;padding:10px 14px;border-radius:8px;margin-bottom:12px;font-size:12px;font-weight:700;border:1px solid rgba(16,185,129,0.3);'>✅ Adjusted " + String(minutes > 0 ? "+" : "") + String(minutes) + "m for " + (targetIp == "ALL" ? "All Active Devices" : targetIp) + ".</div>";
    redirectHome();
}

void handleQueryTime() {
    if (!checkAuth()) return;
    if (!webServer.hasArg("ip")) {
        webServer.send(400, "application/json", "{\"success\":false,\"error\":\"Missing ip parameter\"}");
        return;
    }
    String targetIp = webServer.arg("ip");
    targetIp.trim();
    if (targetIp.length() == 0) {
        webServer.send(400, "application/json", "{\"success\":false,\"error\":\"Empty ip parameter\"}");
        return;
    }

    String queryErr = "";
    int seconds = getDeviceTimeRemainingSeconds(targetIp, &queryErr);
    if (seconds < 0) {
        if (queryErr.length() == 0) {
            char defaultErr[64];
            snprintf(defaultErr, sizeof(defaultErr), "Device offline or unreachable on port %d", targetPort);
            queryErr = defaultErr;
        }
        char errBuf[256];
        snprintf(errBuf, sizeof(errBuf), "{\"success\":false,\"ip\":\"%s\",\"error\":\"%s\"}", targetIp.c_str(), queryErr.c_str());
        webServer.send(200, "application/json", errBuf);
        return;
    }

    int mins = seconds / 60;
    int secs = seconds % 60;
    char jsonBuf[256];
    snprintf(jsonBuf, sizeof(jsonBuf),
        "{\"success\":true,\"ip\":\"%s\",\"seconds\":%d,\"minutes\":%d,\"formatted\":\"%dm %ds\"}",
        targetIp.c_str(), seconds, mins, mins, secs);
    webServer.send(200, "application/json", jsonBuf);
}

void handleApiSlots() {
    if (!checkAdminAuth()) return;
    String json = "{\"maxSlots\":" + String(maxLicensedSlots) + ",\"mac\":\"" + macAddressStr + "\",\"slots\":[";
    for (int i = 0; i < maxLicensedSlots; i++) {
        if (i > 0) json += ",";
        bool isActive = isSlotActive(i);
        bool isExpiredOrInactive = (!isActive);
        int expStatus = isActive ? 0 : 2;

        json += "{";
        json += "\"slotNum\":" + String(licenseSlots[i].slotNum) + ",";
        json += "\"deviceId\":\"" + licenseSlots[i].deviceId + "\",";
        json += "\"ip\":\"" + licenseSlots[i].ip + "\",";
        json += "\"name\":\"" + licenseSlots[i].name + "\",";
        json += "\"active\":" + String(licenseSlots[i].active ? "true" : "false") + ",";
        json += "\"isBound\":" + String(licenseSlots[i].deviceId.length() > 0 ? "true" : "false") + ",";
        json += "\"expStatus\":" + String(expStatus) + ",";
        json += "\"isExpiredOrInactive\":" + String(isExpiredOrInactive ? "true" : "false");
        json += "}";
    }
    json += "]}";
    webServer.send(200, "application/json", json);
}

void handleApiSlotPair() {
    if (!checkAdminAuth()) return;
    int slot = webServer.hasArg("slot") ? webServer.arg("slot").toInt() : 0;
    String id = webServer.hasArg("id") ? webServer.arg("id") : "";
    String ip = webServer.hasArg("ip") ? webServer.arg("ip") : "";
    String name = webServer.hasArg("name") ? webServer.arg("name") : "";

    if (slot < 1 || slot > maxLicensedSlots || id.length() == 0) {
        webServer.send(400, "application/json", "{\"success\":false,\"error\":\"Invalid slot or device ID\"}");
        return;
    }

    bool res = pairDeviceToSlot(slot, id, ip, name);
    if (res) {
        sendCloudSnapshot();
        webServer.send(200, "application/json", "{\"success\":true,\"slot\":" + String(slot) + "}");
    } else {
        webServer.send(500, "application/json", "{\"success\":false,\"error\":\"Failed to pair\"}");
    }
}

void handleApiSlotPairRequest() {
    String reqIp = webServer.hasArg("ip") ? webServer.arg("ip") : "";
    reqIp.trim();
    if (reqIp.length() == 0 || reqIp == "127.0.0.1" || reqIp == "0.0.0.0") {
        reqIp = webServer.client().remoteIP().toString();
    }
    String devId = webServer.hasArg("device_id") ? webServer.arg("device_id") : (webServer.hasArg("id") ? webServer.arg("id") : "");
    devId.trim();
    String devName = webServer.hasArg("name") ? webServer.arg("name") : "PisoPhone Terminal";
    devName.trim();
    int battery = webServer.hasArg("battery") ? webServer.arg("battery").toInt() : -1;
    bool charging = webServer.hasArg("charging") ? (webServer.arg("charging").toInt() == 1 || webServer.arg("charging") == "true") : false;

    if (devId.length() == 0 && reqIp.length() > 0 && reqIp != "127.0.0.1" && reqIp != "0.0.0.0") {
        devId = "DEV_" + reqIp;
    }
    if (devId.length() > 0 || reqIp.length() > 0) {
        updateDynamicDeviceList(devId, reqIp);
        updateDeviceTelemetry(devId, reqIp, -1, 0, battery, charging, 0, true, devName);
    }
    int slotIdx = findSlotIndexForDevice(devId, reqIp);
    String json = "{\"success\":true,\"paired\":" + String(slotIdx >= 0 ? "true" : "false") +
                  ",\"slot\":" + String(slotIdx >= 0 ? slotIdx + 1 : 0) +
                  ",\"mac\":\"" + macAddressStr + "\"}";
    webServer.send(200, "application/json", json);
}

void handleApiSlotUnpair() {
    if (!checkAdminAuth()) return;
    int slot = webServer.hasArg("slot") ? webServer.arg("slot").toInt() : 0;
    if (slot < 1 || slot > maxLicensedSlots) {
        webServer.send(400, "application/json", "{\"success\":false,\"error\":\"Invalid slot number\"}");
        return;
    }

    bool res = unpairSlot(slot);
    if (res) {
        sendCloudSnapshot();
        webServer.send(200, "application/json", "{\"success\":true,\"slot\":" + String(slot) + "}");
    } else {
        webServer.send(500, "application/json", "{\"success\":false,\"error\":\"Failed to unpair\"}");
    }
}

void handleApiSlotApplyToken() {
    if (!checkAdminAuth()) return;
    String token = webServer.hasArg("token") ? webServer.arg("token") : "";
    if (token.length() == 0) {
        webServer.send(400, "application/json", "{\"success\":false,\"error\":\"Missing token\"}");
        return;
    }

    bool ok = applySlotToken(token);
    if (ok) {
        sendCloudSnapshot();
        webServer.send(200, "application/json", "{\"success\":true,\"maxSlots\":" + String(maxLicensedSlots) + "}");
    } else {
        webServer.send(403, "application/json", "{\"success\":false,\"error\":\"Invalid slot token or cryptographic signature mismatch\"}");
    }
}

void handleApiSlotCloudSync() {
    if (!checkAdminAuth()) return;
    sendCloudSnapshot();
    webServer.send(200, "application/json", "{\"success\":true,\"message\":\"Cloud snapshot sent\"}");
}

void handleApiStatus() {
    if (!checkAuth()) return;

    int rssi = WiFi.RSSI();
    int quality = 0;
    bool isConnected = (WiFi.status() == WL_CONNECTED);
    if (isConnected) {
        if (rssi <= -100) quality = 0;
        else if (rssi >= -50) quality = 100;
        else quality = 2 * (rssi + 100);
    }
    
    String qualityStatus = "Disconnected";
    if (isConnected) {
        if (quality >= 75) qualityStatus = "Excellent";
        else if (quality >= 50) qualityStatus = "Good";
        else if (quality >= 25) qualityStatus = "Fair";
        else qualityStatus = "Weak";
    }

    String json = "{";
    json += "\"wifi\":{";
    json += "\"rssi\":" + String(rssi) + ",";
    json += "\"quality\":" + String(quality) + ",";
    json += "\"status\":\"" + qualityStatus + "\",";
    json += "\"ssid\":\"" + String(wifiSsid) + "\",";
    json += "\"ip\":\"" + WiFi.localIP().toString() + "\",";
    json += "\"connected\":" + String(isConnected ? "true" : "false");
    json += "},";
    json += "\"devices\":[";

    bool first = true;
    for (int i = 0; i < maxLicensedSlots; i++) {
        if (!first) json += ",";
        first = false;
        
        int sNum = licenseSlots[i].slotNum;
        String devId = licenseSlots[i].deviceId;
        String ip = licenseSlots[i].ip;
        String name = licenseSlots[i].name.length() > 0 ? licenseSlots[i].name : ("PisoPhone " + String(sNum));
        if (name == devId || name.startsWith("Terminal") || (devId.length() > 0 && name.indexOf(devId) != -1)) {
            name = "PisoPhone " + String(sNum);
        }
        bool isBound = (devId.length() > 0);
        
        int rem = -1;
        int bat = -1;
        bool chg = false;
        bool online = false;
        
        if (isBound) {
            rem = getTrackedTimeRemaining(ip, 40000, devId);
            bat = getTrackedBatteryLevel(ip, devId);
            chg = getTrackedChargingState(ip, devId);
            online = (rem >= 0);
        }
        
        json += "{";
        json += "\"slotNum\":" + String(sNum) + ",";
        json += "\"isBound\":" + String(isBound ? "true" : "false") + ",";
        json += "\"id\":\"" + devId + "\",";
        json += "\"ip\":\"" + ip + "\",";
        json += "\"name\":\"" + name + "\",";
        json += "\"time\":" + String(rem) + ",";
        json += "\"online\":" + String(online ? "true" : "false") + ",";
        json += "\"battery\":" + String(bat) + ",";
        json += "\"charging\":" + String(chg ? "true" : "false") + ",";
        json += "\"active\":" + String(licenseSlots[i].active ? "true" : "false") + ",";
        json += "\"expStatus\":" + String(licenseSlots[i].active ? 0 : 2) + ",";
        json += "\"isExpiredOrInactive\":" + String(!licenseSlots[i].active ? "true" : "false");
        json += "}";
    }
    json += "],\"unassigned_devices\":[";
    bool firstUnassigned = true;
    unsigned long nowMs = millis();
    for (int i = 0; i < trackedDeviceCount; i++) {
        if (trackedDevices[i].deviceId.length() == 0 && trackedDevices[i].lastKnownIp.length() == 0) continue;
        bool isFromApp = trackedDevices[i].isApp || 
                         (trackedDevices[i].deviceId.length() > 0 && !trackedDevices[i].deviceId.startsWith("DEV_")) ||
                         (trackedDevices[i].batteryLevel >= 0);
        if (!isFromApp) continue;
        String dId = trackedDevices[i].deviceId;
        if (dId.length() == 0) dId = trackedDevices[i].lastKnownIp;
        if (findSlotIndexForDevice(dId, trackedDevices[i].lastKnownIp) >= 0) continue;
        
        bool isOnline = (nowMs - trackedDevices[i].lastSeenMs < 30000);
        if (nowMs - trackedDevices[i].lastSeenMs > 300000) continue;
        
        if (!firstUnassigned) json += ",";
        firstUnassigned = false;
        
        String dName = trackedDevices[i].deviceName;
        if (dName.length() == 0 || dName == dId) {
            dName = getDeviceNameByIpOrId(trackedDevices[i].lastKnownIp, dId);
        }
        if (dName.length() == 0 || dName == dId) dName = "PisoPhone Terminal";
        
        json += "{";
        json += "\"id\":\"" + dId + "\",";
        json += "\"ip\":\"" + trackedDevices[i].lastKnownIp + "\",";
        json += "\"name\":\"" + dName + "\",";
        json += "\"battery\":" + String(trackedDevices[i].batteryLevel) + ",";
        json += "\"charging\":" + String(trackedDevices[i].isCharging ? "true" : "false") + ",";
        json += "\"online\":" + String(isOnline ? "true" : "false");
        json += "}";
    }
    json += "]}";
    webServer.send(200, "application/json", json);
}

void handleIdentify() {
    String reqIp = webServer.hasArg("ip") ? webServer.arg("ip") : "";
    reqIp.trim();
    if (reqIp.length() == 0 || reqIp == "127.0.0.1" || reqIp == "0.0.0.0") {
        reqIp = webServer.client().remoteIP().toString();
    }
    String devId = webServer.hasArg("device_id") ? webServer.arg("device_id") : (webServer.hasArg("id") ? webServer.arg("id") : "");
    devId.trim();
    String devName = webServer.hasArg("name") ? webServer.arg("name") : "";
    devName.trim();
    bool isApp = (webServer.hasArg("app") || webServer.hasArg("client") || webServer.hasArg("source") || devId.length() > 0);

    if (devId.length() == 0 && reqIp.length() > 0 && reqIp != "127.0.0.1" && reqIp != "0.0.0.0") {
        devId = "DEV_" + reqIp;
    }
    if (reqIp.length() > 0 && reqIp != "127.0.0.1" && reqIp != "0.0.0.0") {
        updateDynamicDeviceList(devId, reqIp);
        updateDeviceTelemetry(devId, reqIp, -1, 0, -1, false, 0, isApp, devName);
    }
    String dName = getDeviceNameByIpOrId(reqIp, devId);
    int slotIdx = findSlotIndexForDevice(devId, reqIp);
    String json = "{\"device\":\"HARDWARE_kiosk\",\"mac\":\"" + macAddressStr + "\",\"version\":\"3.0\",\"minutes\":" + String(minutesPerCoin) + ",\"price\":1.0";
    if (dName.length() > 0) {
        json += ",\"device_name\":\"" + dName + "\"";
    }
    json += "}";
    webServer.send(200, "application/json", json);
}

struct SessionCoinTx {
    String devId;
    String txId;
    int pulses;
    int seconds;
    double amount;
    unsigned long ts;
    bool acknowledged;
};

static const int MAX_SESSION_TX = 32;
static SessionCoinTx sessionTxList[MAX_SESSION_TX];
static int sessionTxCount = 0;

void recordSessionCoinTx(const String& devId, const String& txId, int pulses, int seconds, double amount) {
    if (txId.length() == 0 || pulses <= 0) return;
    for (int i = 0; i < sessionTxCount; i++) {
        if (sessionTxList[i].txId == txId) {
            return;
        }
    }
    if (sessionTxCount < MAX_SESSION_TX) {
        sessionTxList[sessionTxCount] = { devId, txId, pulses, seconds, amount, millis(), false };
        sessionTxCount++;
    } else {
        for (int i = 0; i < MAX_SESSION_TX - 1; i++) {
            sessionTxList[i] = sessionTxList[i + 1];
        }
        sessionTxList[MAX_SESSION_TX - 1] = { devId, txId, pulses, seconds, amount, millis(), false };
    }
}

static void clearSessionCoinTx(const String& devId) {
    if (devId.length() == 0) {
        sessionTxCount = 0;
        return;
    }
    int writeIdx = 0;
    for (int i = 0; i < sessionTxCount; i++) {
        if (sessionTxList[i].devId != devId) {
            sessionTxList[writeIdx++] = sessionTxList[i];
        }
    }
    sessionTxCount = writeIdx;
}

static void acknowledgeSessionCoinTx(const String& txId) {
    for (int i = 0; i < sessionTxCount; i++) {
        if (sessionTxList[i].txId == txId) {
            sessionTxList[i].acknowledged = true;
            return;
        }
    }
}

void handleApiCoinslotArm() {
    String devId = webServer.hasArg("device_id") ? webServer.arg("device_id") : (webServer.hasArg("id") ? webServer.arg("id") : "");
    devId.trim();
    String reqIp = webServer.hasArg("ip") ? webServer.arg("ip") : "";
    reqIp.trim();
    if (reqIp.length() == 0 || reqIp == "127.0.0.1" || reqIp == "0.0.0.0") {
        reqIp = webServer.client().remoteIP().toString();
    }
    int durationSec = webServer.hasArg("duration") ? webServer.arg("duration").toInt() : 45;
    if (durationSec <= 0 || durationSec > 300) durationSec = 45;

    if (devId.length() == 0 && reqIp.length() > 0 && reqIp != "127.0.0.1" && reqIp != "0.0.0.0") {
        devId = "DEV_" + reqIp;
    }

    int slotIdx = findSlotIndexForDevice(devId, reqIp);
    if (slotIdx < 0) {
        updateDynamicDeviceList(devId, reqIp);
        slotIdx = findSlotIndexForDevice(devId, reqIp);
    }
    if (slotIdx < 0) {
        String json = "{\"success\":false,\"status\":\"unpaired\",\"error\":\"SLOT_NOT_PAIRED\",\"message\":\"Device is not paired to any slot on this ESP32.\"}";
        webServer.send(423, "application/json", json);
        return;
    }

    if (!isSlotActive(slotIdx)) {
        String json = "{\"success\":false,\"status\":\"locked\",\"error\":\"SLOT_EXPIRED\",\"slot\":" + String(licenseSlots[slotIdx].slotNum) + ",\"message\":\"Device slot is expired or inactive.\"}";
        webServer.send(423, "application/json", json);
        return;
    }

    if (isCoinSlotBusy(devId, CoinSlotOwnerType::PHONE)) {
        String holder = getActiveCoinSessionId();
        String json = "{\"success\":false,\"status\":\"busy\",\"error\":\"SLOT_BUSY\",\"holder\":\"" + holder + "\",\"message\":\"Coin slot is currently in use by another device.\"}";
        webServer.send(409, "application/json", json);
        return;
    }

    unsigned long ttlMs = durationSec * 1000UL;
    bool reserved = reserveCoinSlot(devId, CoinSlotOwnerType::PHONE, ttlMs,
        [](const String& id, int pulses) {
            triggerUniversalCoinEvent(pulses, id);
        },
        [](const String& id, const char* reason) {
            Serial.printf("[⚡ COIN SLOT] Session ended for %s (%s)\n", id.c_str(), reason);
        }
    );

    if (!reserved) {
        webServer.send(500, "application/json", "{\"success\":false,\"status\":\"error\",\"error\":\"ARM_FAILED\"}");
        return;
    }

    clearSessionCoinTx(devId);

    int sNum = licenseSlots[slotIdx].slotNum;
    String json = "{\"success\":true,\"status\":\"armed\",\"slot\":" + String(sNum) + ",\"duration\":" + String(durationSec) + ",\"minutes_per_coin\":" + String(minutesPerCoin) + ",\"price\":1.0}";
    webServer.send(200, "application/json", json);
}

void handleApiCoinslotUnarm() {
    String devId = webServer.hasArg("device_id") ? webServer.arg("device_id") : (webServer.hasArg("id") ? webServer.arg("id") : "");
    devId.trim();
    if (devId.length() == 0) {
        devId = getActiveCoinSessionId();
    }
    
    releaseCoinSlot(devId, CoinSlotOwnerType::PHONE, false, "CLIENT_DONE");
    webServer.send(200, "application/json", "{\"success\":true,\"status\":\"idle\"}");
}

void handleApiCoinslotStatus() {
    String devId = webServer.hasArg("device_id") ? webServer.arg("device_id") : (webServer.hasArg("id") ? webServer.arg("id") : "");
    devId.trim();
    if (devId.length() == 0) {
        String reqIp = webServer.client().remoteIP().toString();
        devId = getDeviceIdFromIp(reqIp);
    }
    
    CoinSlotState st = getCoinSlotState();
    String stateStr = (st == CoinSlotState::ARMED) ? "ARMED" : ((st == CoinSlotState::DRAINING) ? "DRAINING" : "IDLE");
    String activeDev = getActiveCoinSessionId();
    bool isArmed = isCoinSlotArmed();
    
    int remainingSec = 0;
    unsigned long armedUntil = getCoinSlotArmedUntilMs();
    if (isArmed && armedUntil > millis()) {
        remainingSec = (int)((armedUntil - millis()) / 1000UL);
    }

    String json = "{";
    json += "\"success\":true,";
    json += "\"armed\":" + String(isArmed ? "true" : "false") + ",";
    json += "\"state\":\"" + stateStr + "\",";
    json += "\"holder\":\"" + activeDev + "\",";
    json += "\"remaining_seconds\":" + String(remainingSec) + ",";
    json += "\"minutes_per_coin\":" + String(minutesPerCoin) + ",";
    json += "\"transactions\":[";
    
    bool first = true;
    for (int i = 0; i < sessionTxCount; i++) {
        if (devId.length() > 0 && sessionTxList[i].devId.length() > 0 && sessionTxList[i].devId != devId && activeDev != devId) continue;
        if (!first) json += ",";
        first = false;
        json += "{";
        json += "\"tx_id\":\"" + sessionTxList[i].txId + "\",";
        json += "\"pulses\":" + String(sessionTxList[i].pulses) + ",";
        json += "\"amount\":" + String(sessionTxList[i].amount, 2) + ",";
        json += "\"seconds\":" + String(sessionTxList[i].seconds) + ",";
        json += "\"minutes\":" + String(sessionTxList[i].seconds / 60) + ",";
        json += "\"acknowledged\":" + String(sessionTxList[i].acknowledged ? "true" : "false") + ",";
        json += "\"ts\":" + String(sessionTxList[i].ts);
        json += "}";
    }
    json += "]}";
    webServer.send(200, "application/json", json);
}

void handleApiCoinslotAck() {
    String txId = webServer.hasArg("tx_id") ? webServer.arg("tx_id") : "";
    txId.trim();
    String devId = webServer.hasArg("device_id") ? webServer.arg("device_id") : "";
    devId.trim();
    
    if (txId.length() > 0) {
        acknowledgeSessionCoinTx(txId);
        if (devId.length() > 0) {
            acknowledgePhonePayment(devId, txId);
        }
    }
    webServer.send(200, "application/json", "{\"success\":true,\"tx_id\":\"" + txId + "\"}");
}
