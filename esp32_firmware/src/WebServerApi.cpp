#include "WebServerApi.h"
#include "WebServerModule.h"
#include "WebServerAuth.h"
#include "Config.h"
#include "Security.h"
#include "HardwareManager.h"
#include "DeviceManager.h"
#include "DeviceNetwork.h"
#include "CoinSlotManager.h"
#include <WiFi.h>
#include <WebServer.h>

void handleAddTime() {
    if (!checkAdminAuth()) return;

    String targetIp = webServer.hasArg("target_ip") ? webServer.arg("target_ip") : "ALL";
    targetIp.trim();

    if (!webServer.hasArg("add_minutes")) {
        quickTimeStatusMsg = "<div style='background:#fee2e2;color:#dc2626;padding:10px 14px;border-radius:8px;margin-bottom:12px;font-size:12px;font-weight:700;border:1px solid rgba(239,68,68,0.3);'>❌ Missing minutes parameter.</div>";
        redirectHome();
        return;
    }
    String minStr = webServer.arg("add_minutes");
    minStr.trim();
    if (minStr.length() == 0) {
        quickTimeStatusMsg = "<div style='background:#fee2e2;color:#dc2626;padding:10px 14px;border-radius:8px;margin-bottom:12px;font-size:12px;font-weight:700;border:1px solid rgba(239,68,68,0.3);'>❌ Missing minutes value.</div>";
        redirectHome();
        return;
    }
    for (size_t i = 0; i < minStr.length(); i++) {
        if (!isDigit(minStr[i])) {
            quickTimeStatusMsg = "<div style='background:#fee2e2;color:#dc2626;padding:10px 14px;border-radius:8px;margin-bottom:12px;font-size:12px;font-weight:700;border:1px solid rgba(239,68,68,0.3);'>❌ Invalid minutes: Must be a positive integer.</div>";
            redirectHome();
            return;
        }
    }
    if (minStr.length() > 9) {
        quickTimeStatusMsg = "<div style='background:#fee2e2;color:#dc2626;padding:10px 14px;border-radius:8px;margin-bottom:12px;font-size:12px;font-weight:700;border:1px solid rgba(239,68,68,0.3);'>❌ Value too large: Minutes value exceeds maximum limit.</div>";
        redirectHome();
        return;
    }
    int64_t minutesVal = minStr.toInt();
    if (minutesVal <= 0) {
        quickTimeStatusMsg = "<div style='background:#fee2e2;color:#dc2626;padding:10px 14px;border-radius:8px;margin-bottom:12px;font-size:12px;font-weight:700;border:1px solid rgba(239,68,68,0.3);'>❌ Minutes must be greater than zero.</div>";
        redirectHome();
        return;
    }
    int64_t rawSeconds = minutesVal * 60LL;
    if (rawSeconds > 2147483647LL) {
        quickTimeStatusMsg = "<div style='background:#fee2e2;color:#dc2626;padding:10px 14px;border-radius:8px;margin-bottom:12px;font-size:12px;font-weight:700;border:1px solid rgba(239,68,68,0.3);'>❌ Seconds overflow: Value exceeds protocol limits.</div>";
        redirectHome();
        return;
    }
    String action = webServer.hasArg("adjust_action") ? webServer.arg("adjust_action") : "add";
    int64_t signedSeconds = (action == "subtract") ? -rawSeconds : rawSeconds;

    AddTimeSummary summary = sendAddTime(signedSeconds, targetIp);

    if (summary.matchedRecipients == 0) {
        quickTimeStatusMsg = "<div style='background:#fee2e2;color:#dc2626;padding:10px 14px;border-radius:8px;margin-bottom:12px;font-size:12px;font-weight:700;border:1px solid rgba(239,68,68,0.3);'>❌ No matching target device found for " + targetIp + ".</div>";
    } else if (summary.queuedRequests == 0) {
        if (summary.skippedInactive > 0) {
            quickTimeStatusMsg = "<div style='background:#fee2e2;color:#dc2626;padding:10px 14px;border-radius:8px;margin-bottom:12px;font-size:12px;font-weight:700;border:1px solid rgba(239,68,68,0.3);'>❌ Adjustment Blocked: Target device " + targetIp + " is INACTIVE or UNLICENSED.</div>";
        } else if (summary.failedSubmissions > 0) {
            quickTimeStatusMsg = "<div style='background:#fee2e2;color:#dc2626;padding:10px 14px;border-radius:8px;margin-bottom:12px;font-size:12px;font-weight:700;border:1px solid rgba(239,68,68,0.3);'>❌ Failed to queue adjustment: Auth queue full or network unavailable.</div>";
        } else {
            quickTimeStatusMsg = "<div style='background:#fee2e2;color:#dc2626;padding:10px 14px;border-radius:8px;margin-bottom:12px;font-size:12px;font-weight:700;border:1px solid rgba(239,68,68,0.3);'>❌ No eligible devices found to adjust.</div>";
        }
    } else {
        if (summary.skippedInactive > 0 || summary.failedSubmissions > 0) {
            quickTimeStatusMsg = "<div style='background:#fef3c7;color:#b45309;padding:10px 14px;border-radius:8px;margin-bottom:12px;font-size:12px;font-weight:700;border:1px solid rgba(245,158,11,0.3);'>⚠️ Adjustment queued: " + String(action == "subtract" ? "-" : "+") + String((long)minutesVal) + "m (" + String(summary.queuedRequests) + " queued, " + String(summary.skippedInactive) + " inactive skipped, " + String(summary.failedSubmissions) + " failed).</div>";
        } else {
            quickTimeStatusMsg = "<div style='background:#e8f5e9;color:#2e7d32;padding:10px 14px;border-radius:8px;margin-bottom:12px;font-size:12px;font-weight:700;border:1px solid rgba(16,185,129,0.3);'>✅ Adjustment queued: " + String(action == "subtract" ? "-" : "+") + String((long)minutesVal) + "m for " + (targetIp == "ALL" ? "All Active Devices" : targetIp) + ".</div>";
        }
    }
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
    String reqIp = webServer.hasArg("ip") ? webServer.arg("ip") : webServer.client().remoteIP().toString();
    String devId = webServer.hasArg("device_id") ? webServer.arg("device_id") : (webServer.hasArg("id") ? webServer.arg("id") : "");
    String devName = webServer.hasArg("name") ? webServer.arg("name") : "PisoPhone Terminal";
    int battery = webServer.hasArg("battery") ? webServer.arg("battery").toInt() : -1;
    bool charging = webServer.hasArg("charging") ? (webServer.arg("charging").toInt() == 1 || webServer.arg("charging") == "true") : false;

    // Filter out coinslot-only requests and non-app clients
    bool isCoinslotOnly = (webServer.hasArg("mode") && webServer.arg("mode") == "coinslot") ||
                          (webServer.hasArg("coinslot") && (webServer.arg("coinslot") == "1" || webServer.arg("coinslot") == "true")) ||
                          (webServer.hasArg("type") && (webServer.arg("type") == "controller" || webServer.arg("type") == "coinslot")) ||
                          (webServer.hasArg("client") && (webServer.arg("client") == "controller" || webServer.arg("client") == "coinslot")) ||
                          (webServer.hasArg("op_kind") && webServer.arg("op_kind") == "5");
    if (isCoinslotOnly) {
        webServer.send(200, "application/json", "{\"success\":false,\"error\":\"COINSLOT_ONLY_NOT_PAIRABLE\"}");
        return;
    }

    bool isAppClient = (webServer.hasArg("app") && (webServer.arg("app") == "1" || webServer.arg("app") == "true")) ||
                       (webServer.hasArg("client") && webServer.arg("client") == "pisophone_app") ||
                       (webServer.hasArg("source") && webServer.arg("source") == "app");
    if (!isAppClient) {
        webServer.send(403, "application/json", "{\"success\":false,\"error\":\"APP_CLIENT_REQUIRED\"}");
        return;
    }

    if (devId.length() == 0 && reqIp.length() > 0 && reqIp != "127.0.0.1" && reqIp != "0.0.0.0") {
        devId = "DEV_" + reqIp;
    }
    if (devId.length() > 0 || reqIp.length() > 0) {
        updateDynamicDeviceList(devId, reqIp);
        updateDeviceTelemetry(devId, reqIp, -1, 0, battery, charging, 0, true);
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
        webServer.send(409, "application/json", "{\"success\":false,\"error\":\"BUSY: Device owns active session or unresolved payments\"}");
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
        if (!trackedDevices[i].isApp) continue; // Only show pairing requests that come from the app
        String dId = trackedDevices[i].deviceId;
        if (dId.length() == 0) dId = trackedDevices[i].lastKnownIp;
        if (findSlotIndexForDevice(dId, trackedDevices[i].lastKnownIp) >= 0) continue;
        
        bool isOnline = (nowMs - trackedDevices[i].lastSeenMs < 30000);
        if (nowMs - trackedDevices[i].lastSeenMs > 300000) continue;
        
        if (!firstUnassigned) json += ",";
        firstUnassigned = false;
        
        String dName = getDeviceNameByIpOrId(trackedDevices[i].lastKnownIp, dId);
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
    String reqIp = webServer.hasArg("ip") ? webServer.arg("ip") : webServer.client().remoteIP().toString();
    String devId = webServer.hasArg("device_id") ? webServer.arg("device_id") : (webServer.hasArg("id") ? webServer.arg("id") : "");
    if (devId.length() == 0 && reqIp.length() > 0 && reqIp != "127.0.0.1" && reqIp != "0.0.0.0") {
        devId = "DEV_" + reqIp;
    }
    String devName = getDeviceNameByIpOrId(reqIp, devId);
    int slotIdx = findSlotIndexForDevice(devId, reqIp);
    String secKey = (sharedSecret.length() > 0) ? sharedSecret : String(MASTER_CRYPTO_SECRET);
    String sig = calculateHMAC("DISCOVERY:" + macAddressStr + ":" + WiFi.localIP().toString(), secKey);
    String json = "{\"device\":\"HARDWARE_kiosk\",\"mac\":\"" + macAddressStr + "\",\"ip\":\"" + WiFi.localIP().toString() + "\",\"sig\":\"" + sig + "\",\"version\":\"3.0\",\"minutes\":" + String(minutesPerCoin) + ",\"price\":1.0";
    if (devName.length() > 0) {
        json += ",\"device_name\":\"" + devName + "\"";
    }
    json += "}";
    webServer.send(200, "application/json", json);
}

void handleCoinslotActivate() {
    String clientIp = webServer.client().remoteIP().toString();
    String sessionId = webServer.hasArg("session_id") ? webServer.arg("session_id") : ("client_" + clientIp);
    sessionId.trim();
    
    unsigned long timeoutSec = 60;
    if (webServer.hasArg("timeout")) {
        timeoutSec = (unsigned long)webServer.arg("timeout").toInt();
        if (timeoutSec < 5) timeoutSec = 5;
        if (timeoutSec > 3600) timeoutSec = 3600;
    }

    if (isMaintenanceMode()) {
        webServer.send(503, "application/json", "{\"status\":\"error\",\"error\":\"MAINTENANCE_MODE\"}");
        return;
    }

    if (isCoinSlotBusy(sessionId, CoinSlotOwnerType::ANY)) {
        String active = getActiveCoinSessionId();
        webServer.send(409, "application/json", "{\"status\":\"error\",\"error\":\"SLOT_BUSY\",\"active_session\":\"" + active + "\"}");
        return;
    }

    bool ok = reserveCoinSlot(sessionId, CoinSlotOwnerType::ANY, timeoutSec * 1000UL);
    if (!ok) {
        webServer.send(500, "application/json", "{\"status\":\"error\",\"error\":\"ARM_FAILED\"}");
        return;
    }

    String json = "{\"status\":\"ok\",\"state\":\"ARMED\",\"is_armed\":true,\"session_id\":\"" + sessionId +
                  "\",\"timeout_seconds\":" + String(timeoutSec) +
                  ",\"pulses\":" + String(getSessionAccumulatedPulses()) +
                  ",\"total_pulses\":" + String((int)totalCoinsLifetime) + "}";
    webServer.send(200, "application/json", json);
}

void handleCoinslotStatus() {
    CoinSlotState st = getCoinSlotState();
    String stateStr = "IDLE";
    if (st == CoinSlotState::ARMED) stateStr = "ARMED";
    else if (st == CoinSlotState::DRAINING) stateStr = "DRAINING";
    else if (st == CoinSlotState::RESERVED_ARMING) stateStr = "RESERVED_ARMING";
    else if (st == CoinSlotState::FAULT_MAINTENANCE) stateStr = "FAULT_MAINTENANCE";

    String json = "{\"status\":\"ok\",\"state\":\"" + stateStr +
                  "\",\"is_armed\":" + String(isCoinSlotArmed() ? "true" : "false") +
                  ",\"active_session\":\"" + getActiveCoinSessionId() +
                  "\",\"pulses\":" + String(getSessionAccumulatedPulses()) +
                  ",\"total_pulses\":" + String((int)totalCoinsLifetime) + "}";
    webServer.send(200, "application/json", json);
}

void handleCoinslotDeactivate() {
    String sessionId = webServer.hasArg("session_id") ? webServer.arg("session_id") : getActiveCoinSessionId();
    int finalPulses = getSessionAccumulatedPulses();
    releaseCoinSlot(sessionId, CoinSlotOwnerType::ANY, true, "API_DEACTIVATE");

    String json = "{\"status\":\"ok\",\"state\":\"IDLE\",\"is_armed\":false" +
                  ",\"pulses\":" + String(finalPulses) +
                  ",\"total_pulses\":" + String((int)totalCoinsLifetime) + "}";
    webServer.send(200, "application/json", json);
}

