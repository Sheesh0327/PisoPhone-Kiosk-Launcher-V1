// Phone-facing telemetry endpoints: the heartbeat that carries time, state and battery,
// config retrieval, crash reports and the one-vs-one match qualification check.

#include "InputSafety.h"
#include "WebServerTelemetry.h"
#include "WebServerModule.h"
#include "WebServerAuth.h"
#include "Config.h"
#include "WebDashboardHtml.h"
#include "Security.h"
#include "DeviceManager.h"
#include "DeviceNetwork.h"
#include "PaymentQueueManager.h"
#include <WiFi.h>
#include <WebServer.h>

void handleHeartbeat() {
    String deviceId = webServer.hasArg("device_id") ? webServer.arg("device_id")
                                                    : (webServer.hasArg("id") ? webServer.arg("id") : "");
    String reqIp = webServer.hasArg("ip") ? webServer.arg("ip") : "";
    if (reqIp.length() == 0 || reqIp == "127.0.0.1" || reqIp == "0.0.0.0")
        reqIp = webServer.client().remoteIP().toString();
    String tsStr = webServer.hasArg("ts") ? webServer.arg("ts") : "0";
    String sig = webServer.hasArg("sig") ? webServer.arg("sig") : "";
    unsigned long long ts = strtoull(tsStr.c_str(), NULL, 10);

    int timeRem = webServer.hasArg("time") ? webServer.arg("time").toInt() : -1; // -1 = not reported
    int state = webServer.hasArg("state") ? webServer.arg("state").toInt() : 0;
    int battery = webServer.hasArg("battery") ? webServer.arg("battery").toInt() : -1;
    bool charging = webServer.hasArg("charging")
                        ? (webServer.arg("charging").toInt() == 1 || webServer.arg("charging") == "true")
                        : false;

    if (deviceId.length() == 0 && reqIp.length() > 0 && reqIp != "127.0.0.1" && reqIp != "0.0.0.0") {
        deviceId = "DEV_" + reqIp;
    }

    bool isAuth = verifyTelemetryAuth(deviceId, tsStr, sig);
    int slotIdx = findSlotIndexForDevice(deviceId, reqIp);

    if (!isAuth && slotIdx >= 0) {
        webServer.send(403, "application/json", "{\"error\":\"AUTH_FAILED_OR_REPLAY\"}");
        return;
    }

    // An acknowledgement removes a paid coin from the retry queue for good: only on a signed heartbeat (it used to be
    // honoured before any check, so anyone on the kiosk network could clear a phone's queued coins).
    if (isAuth && webServer.hasArg("ack_tx") && deviceId.length() > 0) {
        String ackTx = webServer.arg("ack_tx");
        ackTx.trim();
        if (ackTx.length() > 0) {
            acknowledgePhonePayment(deviceId, ackTx);
        }
    }

    bool isAppReq = (webServer.hasArg("app") && (webServer.arg("app") == "1" || webServer.arg("app") == "true")) ||
                    (webServer.hasArg("client") && webServer.arg("client") == "pisophone_app") ||
                    (webServer.hasArg("source") && webServer.arg("source") == "app");

    bool fromApp = isAppReq || (isAuth && webServer.hasArg("battery") && webServer.hasArg("charging")) ||
                   (deviceId.length() > 0 && !deviceId.startsWith("DEV_"));
    String devName = webServer.hasArg("name") ? cleanName(webServer.arg("name")) : "";
    devName.trim();

    if (deviceId.length() > 0 || reqIp.length() > 0) {
        updateDeviceTelemetry(deviceId, reqIp, timeRem, state, battery, charging, ts, fromApp, devName);
    }

    if (slotIdx < 0 || !isAuth) {
        if (devName.length() == 0 || devName == deviceId) {
            devName = getDeviceNameByIpOrId(reqIp, deviceId);
        }
        if (devName.length() == 0 || devName == deviceId) devName = "PisoPhone Terminal";

        String json = "{\"status\":\"unassigned\",\"device\":\"HARDWARE_kiosk\",\"mac\":\"" + macAddressStr + "\"";
        json +=
            ",\"slot_num\":0,\"is_paired\":false,\"slot_expired\":true,\"slot_status\":\"unassigned\",\"slot_warning\":false";
        json += ",\"message\":\"Connected to ESP32: Awaiting Slot Assignment in Admin Portal.\"";
        json += ",\"device_name\":\"" + jsonEsc(devName) + "\"}";
        webServer.send(200, "application/json", json);
        return;
    }

    if (ts > 0) updateMasterTime(ts, deviceId);
    bool isActive = isSlotActive(slotIdx);

    String status = (!isActive) ? "slot_expired" : "ok";
    if (devName.length() == 0 || devName == deviceId) {
        devName = getDeviceNameByIpOrId(reqIp, deviceId);
    }
    if (devName.length() == 0 || devName == deviceId) {
        devName = "PisoPhone Terminal";
    }
    String json = "{\"status\":\"" + status + "\",\"device\":\"HARDWARE_kiosk\",\"mac\":\"" + macAddressStr + "\"";
    if (slotIdx >= 0) {
        String encPin = aes_encrypt("PIN:" + webPassword, getSharedSecret());
        json += ",\"admin_pin\":\"" + jsonEsc(encPin) + "\"";
    }
    if (devName.length() > 0) {
        json += ",\"device_name\":\"" + jsonEsc(devName) + "\"";
    }
    if (slotIdx >= 0) {
        json += ",\"slot_num\":" + String(licenseSlots[slotIdx].slotNum);
    }
    if (!isActive) {
        json += ",\"is_paired\":true,\"slot_expired\":true,\"slot_status\":\"expired\",\"slot_warning\":false";
        json += ",\"message\":\"Device Inactive: Please activate device slot on ESP32 Portal.\"";
    } else {
        json += ",\"is_paired\":true,\"slot_expired\":false,\"slot_status\":\"active\",\"slot_warning\":false";
    }
    if (matchActive) {
        bool isP1 = (p1Ip.length() > 0 && (p1Ip == reqIp || p1Ip == deviceId));
        bool isP2 = (p2Ip.length() > 0 && (p2Ip == reqIp || p2Ip == deviceId));
        if (isP1) {
            json += ",\"arena_active\":true,\"arena_role\":1,\"arena_stake\":" + String(matchMinutes);
        } else if (isP2) {
            json += ",\"arena_active\":true,\"arena_role\":2,\"arena_stake\":" + String(matchMinutes);
        } else {
            json += ",\"arena_active\":false";
        }
    } else {
        json += ",\"arena_active\":false";
    }

    String pendingCoinsJson = "";
    int pendingCount = getPendingPhonePaymentsJson(deviceId, pendingCoinsJson);
    if (pendingCount > 0) {
        json += ",\"unclaimed_coins\":" + pendingCoinsJson;
    }

    json += "}";
    webServer.send(200, "application/json", json);
}

void handleGetConfig() {
    String json = "{\"device\":\"HARDWARE_kiosk\",\"relay_pin\":" + String(relayPin) + "}";
    webServer.send(200, "application/json", json);
}

void handleCrashReport() {
    if (!checkAdminAuth()) return;
    String body = webServer.arg("plain");
    Serial.printf("\n[⚠️ CRASH REPORT FROM CLIENT]\n%s\n", body.c_str());
    webServer.send(200, "text/plain", "OK");
}

void handleCheckQualification() {
    if (!checkAuth()) return;
    String p1 = webServer.hasArg("p1")
                    ? webServer.arg("p1")
                    : (webServer.hasArg("p1_ip") ? webServer.arg("p1_ip")
                                                 : (webServer.hasArg(NVS_KEY_P1) ? webServer.arg(NVS_KEY_P1) : ""));
    String p2 = webServer.hasArg("p2")
                    ? webServer.arg("p2")
                    : (webServer.hasArg("p2_ip") ? webServer.arg("p2_ip")
                                                 : (webServer.hasArg(NVS_KEY_P2) ? webServer.arg(NVS_KEY_P2) : ""));
    int mins = webServer.hasArg("minutes")
                   ? webServer.arg("minutes").toInt()
                   : (webServer.hasArg("match_minutes") ? webServer.arg("match_minutes").toInt() : 15);
    if (mins <= 0) mins = 1;

    p1.trim();
    p2.trim();

    if (p1.length() == 0 || p2.length() == 0) {
        webServer.send(200, "application/json",
                       "{\"success\":false,\"error\":\"Please select both Player 1 and Player 2.\"}");
        return;
    }
    if (p1 == p2) {
        webServer.send(
            200, "application/json",
            "{\"success\":true,\"qualified\":false,\"error\":\"Player 1 and Player 2 cannot be the same device!\"}");
        return;
    }

    String p1Err = "", p2Err = "";
    int p1Sec = getDeviceTimeRemainingSeconds(p1, &p1Err);
    int p2Sec = getDeviceTimeRemainingSeconds(p2, &p2Err);

    int stakeSec = mins * 60;
    bool p1Ok = (p1Sec >= stakeSec);
    bool p2Ok = (p2Sec >= stakeSec);
    bool bothQualified = (p1Ok && p2Ok);

    int p1M = (p1Sec >= 0) ? (p1Sec / 60) : 0;
    int p1S = (p1Sec >= 0) ? (p1Sec % 60) : 0;
    int p2M = (p2Sec >= 0) ? (p2Sec / 60) : 0;
    int p2S = (p2Sec >= 0) ? (p2Sec % 60) : 0;

    char p1FmtBuf[32], p2FmtBuf[32];
    if (p1Sec >= 0)
        snprintf(p1FmtBuf, sizeof(p1FmtBuf), "%dm %ds", p1M, p1S);
    else
        strncpy(p1FmtBuf, "Offline", sizeof(p1FmtBuf));

    if (p2Sec >= 0)
        snprintf(p2FmtBuf, sizeof(p2FmtBuf), "%dm %ds", p2M, p2S);
    else
        strncpy(p2FmtBuf, "Offline", sizeof(p2FmtBuf));

    char msgBuf[128];
    if (bothQualified) {
        snprintf(msgBuf, sizeof(msgBuf), "Both devices meet the %dm stake requirement.", mins);
    } else if (p1Sec < 0 || p2Sec < 0) {
        strncpy(msgBuf, "One or both devices cannot be reached or have no telemetry.", sizeof(msgBuf));
    } else if (!p1Ok && !p2Ok) {
        snprintf(msgBuf, sizeof(msgBuf), "Both players need more time to meet the %dm stake.", mins);
    } else if (!p1Ok) {
        snprintf(msgBuf, sizeof(msgBuf), "Player 1 needs at least %dm more active time.", max(1, mins - p1M));
    } else {
        snprintf(msgBuf, sizeof(msgBuf), "Player 2 needs at least %dm more active time.", max(1, mins - p2M));
    }

    char jsonBuf[512];
    snprintf(jsonBuf, sizeof(jsonBuf),
             "{\"success\":true,\"qualified\":%s,\"stake_minutes\":%d,"
             "\"p1_ip\":\"%s\",\"p1_seconds\":%d,\"p1_formatted\":\"%s\",\"p1_ok\":%s,\"p1_err\":\"%s\","
             "\"p2_ip\":\"%s\",\"p2_seconds\":%d,\"p2_formatted\":\"%s\",\"p2_ok\":%s,\"p2_err\":\"%s\","
             "\"message\":\"%s\"}",
             bothQualified ? "true" : "false", mins, p1.c_str(), p1Sec, p1FmtBuf, p1Ok ? "true" : "false",
             p1Err.c_str(), p2.c_str(), p2Sec, p2FmtBuf, p2Ok ? "true" : "false", p2Err.c_str(), msgBuf);

    webServer.send(200, "application/json", jsonBuf);
}

void handleOneVsOne() {
    if (!checkAdminAuth()) return;
    p1Ip = webServer.hasArg("p1_ip") ? webServer.arg("p1_ip") : (webServer.hasArg("p1") ? webServer.arg("p1") : "");
    p2Ip = webServer.hasArg("p2_ip") ? webServer.arg("p2_ip") : (webServer.hasArg("p2") ? webServer.arg("p2") : "");
    if (webServer.hasArg("match_minutes")) {
        matchMinutes = webServer.arg("match_minutes").toInt();
    }

    prefs.begin(NVS_NAMESPACE, false);
    prefs.putString(NVS_KEY_P1, p1Ip);
    prefs.putString(NVS_KEY_P2, p2Ip);
    prefs.putInt(NVS_KEY_MATCH, matchMinutes);
    prefs.end();

    String action = webServer.hasArg("action") ? webServer.arg("action") : "";
    String winner = webServer.hasArg("winner") ? webServer.arg("winner") : "";

    if (action == "cancel" || action == "end") {
        matchActive = false;
        if (p1Ip.length() > 0) {
            sendAuthenticated(p1Ip, targetPort, "/trigger_action", "/challenge", "action=arena_mode_deactivate", 1000);
        }
        if (p2Ip.length() > 0) {
            sendAuthenticated(p2Ip, targetPort, "/trigger_action", "/challenge", "action=arena_mode_deactivate", 1000);
        }
        matchStatusMsg = noteHtml("warn", "info", "<b>Match ended.</b> Nobody won, so no time was moved.");
        redirectHome();
        return;
    }

    if (action == "activate") {
        if (p1Ip == "" || p2Ip == "") {
            matchStatusMsg = noteHtml("bad", "alert", "<b>Can't start.</b> Choose both players.");
            redirectHome();
            return;
        }
        if (p1Ip == p2Ip) {
            matchStatusMsg =
                noteHtml("bad", "alert", "<b>Can't start.</b> Player 1 and Player 2 must be different phones.");
            redirectHome();
            return;
        }
        if (matchMinutes <= 0) {
            matchStatusMsg = noteHtml("bad", "alert", "<b>Can't start.</b> The stake must be at least 1 minute.");
            redirectHome();
            return;
        }

        matchActive = true;
        matchStatusMsg = noteHtml("warn", "alert",
                                  "<b>A match is on.</b> Both players staked " + String(matchMinutes) +
                                      " minutes. The loser's stake goes to the winner when the match ends.");

        sendAuthenticated(p1Ip, targetPort, "/trigger_action", "/challenge",
                          "action=arena_mode_activate_p1&role=1&stake=" + String(matchMinutes), 1000);
        sendAuthenticated(p2Ip, targetPort, "/trigger_action", "/challenge",
                          "action=arena_mode_activate_p2&role=2&stake=" + String(matchMinutes), 1000);

        redirectHome();
        return;
    }

    if (winner != "" && p1Ip != "" && p2Ip != "") {
        if (p1Ip == p2Ip) {
            matchStatusMsg = noteHtml("bad", "alert",
                                      "<b>Can't count this match.</b> Player 1 and Player 2 must be different phones.");
            redirectHome();
            return;
        }

        if (matchMinutes <= 0) {
            matchStatusMsg =
                noteHtml("bad", "alert", "<b>Can't count this match.</b> The stake must be at least 1 minute.");
            redirectHome();
            return;
        }

        int reqStakeSeconds = matchMinutes * 60;
        String p1Err = "", p2Err = "";
        int p1Sec = getDeviceTimeRemainingSeconds(p1Ip, &p1Err);
        int p2Sec = getDeviceTimeRemainingSeconds(p2Ip, &p2Err);

        if (p1Sec < 0 || p2Sec < 0) {
            String detail = "";
            if (p1Sec < 0) detail += "<br>Player 1 (" + escapeHtmlText(p1Ip) + "): " + escapeHtmlText(p1Err);
            if (p2Sec < 0) detail += "<br>Player 2 (" + escapeHtmlText(p2Ip) + "): " + escapeHtmlText(p2Err);
            matchStatusMsg = noteHtml("bad", "alert", "<b>Could not check the phones:</b>" + detail);
            redirectHome();
            return;
        }

        int p1Mins = p1Sec / 60;
        int p2Mins = p2Sec / 60;

        if (p1Sec < reqStakeSeconds || p2Sec < reqStakeSeconds) {
            matchStatusMsg = noteHtml("bad", "alert",
                                      "<b>Not enough time to stake.</b> Both phones need at least " +
                                          String(matchMinutes) + " minutes.<br>Player 1 (" + escapeHtmlText(p1Ip) +
                                          "): <b>" + String(p1Mins) + " min left</b><br>Player 2 (" +
                                          escapeHtmlText(p2Ip) + "): <b>" + String(p2Mins) + " min left</b>");
            redirectHome();
            return;
        }

        matchActive = false;

        if (winner == "p1" || winner == NVS_KEY_P1) {
            sendAddTime(matchMinutes, p1Ip);
            yield();
            sendAddTime(-matchMinutes, p2Ip);
            matchStatusMsg =
                noteHtml("ok", "trophy",
                         "<b>Player 1 won.</b> +" + String(matchMinutes) + " min to Player 1 (" + escapeHtmlText(p1Ip) +
                             "), -" + String(matchMinutes) + " min from Player 2 (" + escapeHtmlText(p2Ip) + ").");
        } else if (winner == "p2" || winner == NVS_KEY_P2) {
            sendAddTime(matchMinutes, p2Ip);
            yield();
            sendAddTime(-matchMinutes, p1Ip);
            matchStatusMsg =
                noteHtml("ok", "trophy",
                         "<b>Player 2 won.</b> +" + String(matchMinutes) + " min to Player 2 (" + escapeHtmlText(p2Ip) +
                             "), -" + String(matchMinutes) + " min from Player 1 (" + escapeHtmlText(p1Ip) + ").");
        }

        sendAuthenticated(p1Ip, targetPort, "/trigger_action", "/challenge", "action=arena_mode_deactivate", 1000);
        sendAuthenticated(p2Ip, targetPort, "/trigger_action", "/challenge", "action=arena_mode_deactivate", 1000);
    }

    redirectHome();
}
