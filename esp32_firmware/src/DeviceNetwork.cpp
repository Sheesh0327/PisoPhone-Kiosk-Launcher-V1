#include "DeviceNetwork.h"
#include "PaymentQueueManager.h"
#include "CoinSlotManager.h"
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

void triggerUniversalCoinEvent(int pulses, const String& targetDeviceId) {
    if (pulses <= 0) return;
    Serial.printf("[⚡ UNIVERSAL COIN] %d total pulses accumulated on GPIO %d (₱%d PHP)\n", pulses, universalCoinPin, pulses);

    String targetDev = targetDeviceId;
    if (targetDev.length() == 0) {
        targetDev = getActiveCoinSessionId();
    }

    String targetIp = "";
    if (targetDev.length() > 0) {
        targetIp = getIpFromDeviceId(targetDev);
        if (targetIp.length() == 0 || targetIp == "127.0.0.1") {
            int slotIdx = findSlotIndexForDevice(targetDev, "");
            if (slotIdx >= 0 && isSlotActive(slotIdx)) {
                targetIp = licenseSlots[slotIdx].ip;
            }
        }
    }

    int rate = (minutesPerCoin > 0) ? minutesPerCoin : 1;
    int addedMinutes = pulses * rate;
    int addedSeconds = addedMinutes * 60;

    totalCoinsLifetime += pulses;
    totalCoinsSession += pulses;
    totalEarningsLifetime += (float)pulses;
    totalEarningsSession += (float)pulses;

    revenueDirty = true;
    lastCoinChangeTime = millis();

    triggerLedBlink(pulses > 1 ? 4 : 2);

    unsigned long long ts = (unsigned long long)getCurrentMasterTimeMs();
    String txId = "tx-" + String(ts) + "-" + String(random(10000, 99999));

    bool retained = enqueuePendingPayment(
        txId, targetDev, pulses, CoinSlotOwnerType::PHONE, addedSeconds);
    if (!retained) {
        Serial.printf("[UNIVERSAL COIN] CRITICAL: Could not retain tx_id='%s'.\n",
                      txId.c_str());
    }

    // Require an exact device-ID match before WebSocket delivery. Never transfer pending credit to a replacement phone automatically.
    String vPayload = "v1:" + targetDev + ":" + txId + ":" + String(pulses) + ":" + String(ts);
    String vSig = calculateHMAC(vPayload, sharedSecret);

    if (isWsConnected && wsClient.connected() && targetDev.length() > 0 && wsSessionDeviceId == targetDev) {
        Serial.printf("[⚡] Pushing ₱%d (+%d mins / %d secs) over WebSocket to %s!\n", pulses, addedMinutes, addedSeconds, targetDev.c_str());
        String innerJson = "{\"seconds\":" + String(addedSeconds) + ",\"minutes\":" + String(addedMinutes) + ",\"amount\":" + String(pulses) + ",\"tx_id\":\"" + txId + "\",\"ts\":\"" + String(ts) + "\",\"device_id\":\"" + targetDev + "\",\"v_sig\":\"" + vSig + "\"}";
        String payload = aes_encrypt(innerJson, sharedSecret);
        String json = "{\"event\":\"COIN_DETECTED\",\"payload\":\"" + payload + "\",\"seconds\":" + String(addedSeconds) + ",\"amount\":" + String(pulses) + ",\"tx_id\":\"" + txId + "\"}";
        sendWsText(wsClient, json);
        refreshCoinSlotTtl(targetDev, CoinSlotOwnerType::PHONE, ARM_TTL);
    }

    if (targetIp.length() > 0 && targetIp != "127.0.0.1") {
        Serial.printf("[⚡] Routing universal coin to IP: %s (Device: %s)\n", targetIp.c_str(), targetDev.c_str());
        sendAuthenticated(targetIp, targetPort, "/add_time", "/challenge", "minutes=" + String(addedMinutes) + "&seconds=" + String(addedSeconds) + "&amount=" + String(pulses) + "&tx_id=" + txId + "&device_id=" + targetDev + "&ts=" + String(ts) + "&v_sig=" + vSig, 1000);
        if (targetDev.length() > 0) refreshCoinSlotTtl(targetDev, CoinSlotOwnerType::PHONE, ARM_TTL);
    } else {
        Serial.printf("[⚡] Device '%s' currently offline/unreachable; retained in queue for retry.\n", targetDev.c_str());
    }
}

int getDeviceTimeRemainingSeconds(String targetIp, String* errOut) {
    int rem = getTrackedTimeRemaining(targetIp, 40000);
    if (rem < 0) {
        if (errOut) *errOut = "Device offline or unreachable via telemetry.";
        return -1;
    }
    return rem;
}

void sendCloudSnapshot() {
    // Retiring outbound telemetry to Cloudflare Worker. Strictly Pages-only now.
}

bool retryPhonePayment(const String& targetDeviceId, int pulses, int creditSeconds,
                       const String& txId) {
    if (targetDeviceId.length() == 0) return false;

    // Resolve current IP directly from active telemetry/device tracking, or fall back to slot mapping
    String targetIp = getIpFromDeviceId(targetDeviceId);
    if (targetIp.length() == 0 || targetIp == "127.0.0.1") {
        int slotIndex = findSlotIndexForDevice(targetDeviceId, "");
        if (slotIndex >= 0 && isSlotActive(slotIndex)) {
            targetIp = licenseSlots[slotIndex].ip;
        }
    }
    if (targetIp.length() == 0 || targetIp == "127.0.0.1") return false;

    int safeSeconds = creditSeconds > 0
        ? creditSeconds
        : pulses * max(minutesPerCoin, 1) * 60;
    uint64_t retryTs = getCurrentMasterTimeMs();
    String vPayload = "v1:" + targetDeviceId + ":" + txId + ":" + String(pulses) + ":" + String(retryTs);
    String vSig = calculateHMAC(vPayload, sharedSecret);
    String params = "minutes=" + String(safeSeconds / 60) +
                    "&seconds=" + String(safeSeconds) +
                    "&amount=" + String(pulses) +
                    "&tx_id=" + txId +
                    "&device_id=" + targetDeviceId +
                    "&ts=" + String(retryTs) +
                    "&v_sig=" + vSig;
    return sendAuthenticated(targetIp, targetPort, "/add_time", "/challenge",
                             params, 1000);
}

bool sendAuthenticated(String ip, int port, String actionPath, String challengePath, String params, int timeoutMs) {
    if (WiFi.status() != WL_CONNECTED || authQueue == NULL || ip.length() == 0) return false;
    
    AuthRequest req;
    memset(&req, 0, sizeof(AuthRequest));
    strncpy(req.ip, ip.c_str(), sizeof(req.ip) - 1);
    req.port = port;
    strncpy(req.actionPath, actionPath.c_str(), sizeof(req.actionPath) - 1);
    strncpy(req.challengePath, challengePath.c_str(), sizeof(req.challengePath) - 1);
    strncpy(req.params, params.c_str(), sizeof(req.params) - 1);
    req.timeoutMs = timeoutMs;
    
    if (xQueueSend(authQueue, &req, 0) != pdTRUE) {
        Serial.printf("[AUTH QUEUE] Queue full; could not schedule %s for %s.\n",
                      actionPath.c_str(), ip.c_str());
        return false;
    }
    return true;
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
