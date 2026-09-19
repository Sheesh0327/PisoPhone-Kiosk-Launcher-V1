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
                        String currentTxId = txId;
                        if (currentTxId.length() == 0) {
                            currentTxId = "adj-" + cfg.id + "-" + String(millis()) + "-" + String(random(1000, 9999));
                        }
                        String params = "device_id=" + cfg.id + "&tx_id=" + currentTxId + "&seconds=" + String(minutes * 60) + "&amount=0";
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

    int safeSeconds = creditSeconds > 0
        ? creditSeconds
        : pulses * max(minutesPerCoin, 1) * 60;
    int addedMinutes = safeSeconds / 60;
    uint64_t retryTs = getCurrentMasterTimeMs();

    bool dispatched = false;

    // 1. Dispatch over WebSocket if client is connected for targetDeviceId
    if (isWsConnected && wsClient.connected() && wsSessionDeviceId == targetDeviceId) {
        String innerJson = "{\"seconds\":" + String(safeSeconds) + ",\"minutes\":" + String(addedMinutes) + ",\"amount\":" + String(pulses) + ",\"tx_id\":\"" + txId + "\",\"ts\":\"" + String(retryTs) + "\",\"device_id\":\"" + targetDeviceId + "\"}";
        String payload = aes_encrypt(innerJson, sharedSecret);
        String vSig = calculateWsPaySignature("COIN_DETECTED", targetDeviceId, txId, String(retryTs), payload, sharedSecret);
        String json = "{\"event\":\"COIN_DETECTED\",\"payload\":\"" + payload + "\",\"seconds\":" + String(safeSeconds) + ",\"amount\":" + String(pulses) + ",\"tx_id\":\"" + txId + "\",\"ts\":\"" + String(retryTs) + "\",\"v_sig\":\"" + vSig + "\"}";
        sendWsText(wsClient, json);
        refreshCoinSlotTtl(targetDeviceId, CoinSlotOwnerType::PHONE, ARM_TTL);
        dispatched = true;
    }

    // 2. Dispatch over HTTP if device IP is resolved
    String targetIp = getIpFromDeviceId(targetDeviceId);
    if (targetIp.length() == 0 || targetIp == "127.0.0.1") {
        int slotIndex = findSlotIndexForDevice(targetDeviceId, "");
        if (slotIndex >= 0 && isSlotActive(slotIndex)) {
            targetIp = licenseSlots[slotIndex].ip;
        }
    }

    if (targetIp.length() > 0 && targetIp != "127.0.0.1") {
        String params = "minutes=" + String(addedMinutes) +
                        "&seconds=" + String(safeSeconds) +
                        "&amount=" + String(pulses) +
                        "&tx_id=" + txId +
                        "&device_id=" + targetDeviceId +
                        "&ts=" + String(retryTs);
        if (sendAuthenticated(targetIp, targetPort, "/add_time", "/challenge", params, 1000)) {
            refreshCoinSlotTtl(targetDeviceId, CoinSlotOwnerType::PHONE, ARM_TTL);
            dispatched = true;
        }
    }

    return dispatched;
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
