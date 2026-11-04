#include "WebServerChores.h"
#if PISO_IS_HOME

#include <Preferences.h>
#include <WebServer.h>
#include <WiFi.h>
#include <time.h>
#include "Config.h"
#include "DeviceManager.h"
#include "Diagnostics.h"
#include "WebServerAuth.h"
#include "WebDashboardHtml.h"

extern WebServer webServer;

static choreboard::ChoreBoard gChoreBoard;
static curfewrules::CurfewConfig gCurfewConfig;
static uint32_t gPiggyCoinsBase = 0;
static bool gInitialized = false;

static void loadHomeChoresFromNvs() {
    Preferences prefs;
    if (prefs.begin("home_chores", true)) {
        String ser = prefs.getString("board", "");
        if (ser.length() > 0) {
            choreboard::deserializeBoard(&gChoreBoard, ser.c_str());
        } else {
            choreboard::initBoard(&gChoreBoard);
        }

        gCurfewConfig.bedtimeEnabled = prefs.getBool("bed_en", true);
        gCurfewConfig.bedtimeStartMin = prefs.getInt("bed_s", 1260); // 21:00
        gCurfewConfig.bedtimeEndMin = prefs.getInt("bed_e", 420);    // 07:00
        gCurfewConfig.studyEnabled = prefs.getBool("std_en", false);
        gCurfewConfig.studyStartMin = prefs.getInt("std_s", 960); // 16:00
        gCurfewConfig.studyEndMin = prefs.getInt("std_e", 1080);  // 18:00

        gPiggyCoinsBase = prefs.getULong("piggy_base", 0);
        prefs.end();
    } else {
        choreboard::initBoard(&gChoreBoard);
    }
}

static void saveHomeChoresToNvs() {
    Preferences prefs;
    if (prefs.begin("home_chores", false)) {
        char serBuf[1024];
        choreboard::serializeBoard(&gChoreBoard, serBuf, sizeof(serBuf));
        prefs.putString("board", serBuf);

        prefs.putBool("bed_en", gCurfewConfig.bedtimeEnabled);
        prefs.putInt("bed_s", gCurfewConfig.bedtimeStartMin);
        prefs.putInt("bed_e", gCurfewConfig.bedtimeEndMin);
        prefs.putBool("std_en", gCurfewConfig.studyEnabled);
        prefs.putInt("std_s", gCurfewConfig.studyStartMin);
        prefs.putInt("std_e", gCurfewConfig.studyEndMin);

        prefs.putULong("piggy_base", gPiggyCoinsBase);
        prefs.end();
    }
}

void initHomeChores() {
    if (!gInitialized) {
        loadHomeChoresFromNvs();
        gInitialized = true;
    }
}

choreboard::ChoreBoard& getChoreBoard() {
    initHomeChores();
    return gChoreBoard;
}

curfewrules::CurfewConfig& getCurfewConfig() {
    initHomeChores();
    return gCurfewConfig;
}

uint32_t getPiggyCoins() {
    initHomeChores();
    if (totalCoinsLifetime >= gPiggyCoinsBase) {
        return totalCoinsLifetime - gPiggyCoinsBase;
    }
    return 0;
}

bool isCurfewActiveNow(curfewrules::CurfewReason* outReason) {
    initHomeChores();
    time_t now = time(nullptr);
    struct tm ti;
    localtime_r(&now, &ti);
    // If clock is not synchronized (year < 2024), default to not active
    if (ti.tm_year < 124) {
        if (outReason) *outReason = curfewrules::CURFEW_NONE;
        return false;
    }
    int currentMins = ti.tm_hour * 60 + ti.tm_min;
    return curfewrules::isAnyCurfewActive(gCurfewConfig, currentMins, outReason);
}

void handleApiPhoneChores() {
    initHomeChores();
    String json = "{\"status\":\"ok\",\"chores\":[";
    for (size_t i = 0; i < gChoreBoard.count; i++) {
        if (i > 0) json += ",";
        const choreboard::ChoreItem& c = gChoreBoard.chores[i];
        json += "{\"id\":" + String(c.id) + ",";
        json += "\"title\":\"" + escapeHtmlText(String(c.title)) + "\",";
        json += "\"reward_type\":\"" + String(c.rewardType == choreboard::REWARD_PESOS ? "pesos" : "minutes") + "\",";
        json += "\"reward_value\":" + String(c.rewardValue) + ",";
        json +=
            "\"status\":\"" +
            String(c.status == choreboard::STATUS_APPROVED ? "approved"
                                                           : (c.status == choreboard::STATUS_DONE ? "done" : "todo")) +
            "\",";
        json += "\"repeat_daily\":" + String(c.repeatDaily ? "true" : "false") + ",";
        json += "\"target_slot\":" + String(c.targetSlot) + "}";
    }
    json += "],\"piggy_goal\":{";
    json += "\"target_pesos\":" + String(gChoreBoard.piggyGoal.targetPesos) + ",";
    json += "\"title\":\"" + escapeHtmlText(String(gChoreBoard.piggyGoal.title)) + "\",";
    json += "\"total_saved\":" + String(getPiggyCoins());
    json += "}}";
    webServer.send(200, "application/json", json);
}

void handleApiPhoneChoreDone() {
    initHomeChores();
    if (!webServer.hasArg("id")) {
        webServer.send(400, "application/json", "{\"error\":\"missing id\"}");
        return;
    }
    uint8_t id = (uint8_t)webServer.arg("id").toInt();
    if (choreboard::markChoreDone(&gChoreBoard, id)) {
        saveHomeChoresToNvs();
        webServer.send(200, "application/json",
                       "{\"status\":\"ok\",\"id\":" + String(id) + ",\"chore_status\":\"done\"}");
    } else {
        webServer.send(404, "application/json", "{\"error\":\"chore not found or already done\"}");
    }
}

void handleApiChoreApprove() {
    if (!checkAdminAuth()) return;
    initHomeChores();
    if (!webServer.hasArg("id")) {
        webServer.send(400, "application/json", "{\"error\":\"missing id\"}");
        return;
    }
    uint8_t id = (uint8_t)webServer.arg("id").toInt();
    choreboard::ChoreItem* item = choreboard::findChore(&gChoreBoard, id);
    if (!item) {
        webServer.send(404, "application/json", "{\"error\":\"chore not found\"}");
        return;
    }

    if (item->status == choreboard::STATUS_DONE) {
        choreboard::approveChore(&gChoreBoard, id);
        saveHomeChoresToNvs();

        // If reward is minutes, push credit to assigned phone (or all)
        if (item->rewardType == choreboard::REWARD_MINUTES && item->rewardValue > 0) {
            String targetIp = "ALL";
            if (item->targetSlot > 0) {
                for (int i = 0; i < MAX_SUPPORTED_SLOTS; i++) {
                    if (phoneSlots[i].slotNum == item->targetSlot && phoneSlots[i].ip.length() > 0) {
                        targetIp = phoneSlots[i].ip;
                        break;
                    }
                }
            }
            sendAddTime(item->rewardValue, targetIp);
        }

        webServer.send(200, "application/json",
                       "{\"status\":\"ok\",\"id\":" + String(id) + ",\"chore_status\":\"approved\"}");
    } else {
        webServer.send(400, "application/json", "{\"error\":\"chore not in done state\"}");
    }
}

void handleApiChoreAdd() {
    if (!checkAdminAuth()) return;
    initHomeChores();
    String title = webServer.hasArg("title") ? webServer.arg("title") : "";
    uint8_t rewType = (webServer.hasArg("reward_type") && webServer.arg("reward_type") == "pesos")
                          ? choreboard::REWARD_PESOS
                          : choreboard::REWARD_MINUTES;
    uint16_t rewVal = webServer.hasArg("reward_val") ? (uint16_t)webServer.arg("reward_val").toInt() : 15;
    bool repeatDaily = !webServer.hasArg("repeat_daily") || webServer.arg("repeat_daily") == "1" ||
                       webServer.arg("repeat_daily") == "true";
    uint8_t targetSlot = webServer.hasArg("target_slot") ? (uint8_t)webServer.arg("target_slot").toInt() : 0;

    int id = choreboard::addChore(&gChoreBoard, title.c_str(), rewType, rewVal, repeatDaily, targetSlot);
    if (id > 0) {
        saveHomeChoresToNvs();
        if (webServer.hasArg("redirect")) {
            redirectHome();
        } else {
            webServer.send(200, "application/json", "{\"status\":\"ok\",\"id\":" + String(id) + "}");
        }
    } else {
        webServer.send(400, "application/json", "{\"error\":\"failed to add chore (board full?)\"}");
    }
}

void handleApiChoreDelete() {
    if (!checkAdminAuth()) return;
    initHomeChores();
    if (!webServer.hasArg("id")) {
        webServer.send(400, "application/json", "{\"error\":\"missing id\"}");
        return;
    }
    uint8_t id = (uint8_t)webServer.arg("id").toInt();
    if (choreboard::deleteChore(&gChoreBoard, id)) {
        saveHomeChoresToNvs();
        if (webServer.hasArg("redirect")) {
            redirectHome();
        } else {
            webServer.send(200, "application/json", "{\"status\":\"ok\"}");
        }
    } else {
        webServer.send(404, "application/json", "{\"error\":\"chore not found\"}");
    }
}

void handleApiChoreResetDaily() {
    if (!checkAdminAuth()) return;
    initHomeChores();
    choreboard::resetDailyChores(&gChoreBoard);
    saveHomeChoresToNvs();
    if (webServer.hasArg("redirect")) {
        redirectHome();
    } else {
        webServer.send(200, "application/json", "{\"status\":\"ok\"}");
    }
}

void handleApiPiggyGoal() {
    if (!checkAdminAuth()) return;
    initHomeChores();
    uint32_t target = webServer.hasArg("target") ? (uint32_t)webServer.arg("target").toInt() : 500;
    String title = webServer.hasArg("title") ? webServer.arg("title") : "Savings Goal";
    choreboard::setPiggyGoal(&gChoreBoard, target, title.c_str());
    saveHomeChoresToNvs();
    if (webServer.hasArg("redirect")) {
        redirectHome();
    } else {
        webServer.send(200, "application/json", "{\"status\":\"ok\"}");
    }
}

void handleResetPiggy() {
    if (!checkAdminAuth()) return;
    initHomeChores();
    gPiggyCoinsBase = totalCoinsLifetime;
    saveHomeChoresToNvs();
    redirectHome();
}

void handleSaveCurfew() {
    if (!checkAdminAuth()) return;
    initHomeChores();
    gCurfewConfig.bedtimeEnabled = (webServer.hasArg("bedtime_enabled") && (webServer.arg("bedtime_enabled") == "1" ||
                                                                            webServer.arg("bedtime_enabled") == "on"));
    if (webServer.hasArg("bedtime_start")) {
        int m = curfewrules::parseMinutes(webServer.arg("bedtime_start").c_str());
        if (m >= 0) gCurfewConfig.bedtimeStartMin = m;
    }
    if (webServer.hasArg("bedtime_end")) {
        int m = curfewrules::parseMinutes(webServer.arg("bedtime_end").c_str());
        if (m >= 0) gCurfewConfig.bedtimeEndMin = m;
    }
    gCurfewConfig.studyEnabled = (webServer.hasArg("study_enabled") &&
                                  (webServer.arg("study_enabled") == "1" || webServer.arg("study_enabled") == "on"));
    if (webServer.hasArg("study_start")) {
        int m = curfewrules::parseMinutes(webServer.arg("study_start").c_str());
        if (m >= 0) gCurfewConfig.studyStartMin = m;
    }
    if (webServer.hasArg("study_end")) {
        int m = curfewrules::parseMinutes(webServer.arg("study_end").c_str());
        if (m >= 0) gCurfewConfig.studyEndMin = m;
    }
    saveHomeChoresToNvs();
    redirectHome();
}

void handleApiCurfew() {
    initHomeChores();
    curfewrules::CurfewReason reason = curfewrules::CURFEW_NONE;
    bool active = isCurfewActiveNow(&reason);

    char bStart[8], bEnd[8], sStart[8], sEnd[8];
    curfewrules::formatMinutes(gCurfewConfig.bedtimeStartMin, bStart, sizeof(bStart));
    curfewrules::formatMinutes(gCurfewConfig.bedtimeEndMin, bEnd, sizeof(bEnd));
    curfewrules::formatMinutes(gCurfewConfig.studyStartMin, sStart, sizeof(sStart));
    curfewrules::formatMinutes(gCurfewConfig.studyEndMin, sEnd, sizeof(sEnd));

    String json = "{";
    json += "\"active\":" + String(active ? "true" : "false") + ",";
    json += "\"reason\":\"" +
            String(reason == curfewrules::CURFEW_BEDTIME ? "bedtime"
                                                         : (reason == curfewrules::CURFEW_STUDY ? "study" : "none")) +
            "\",";
    json += "\"bedtime\":{\"enabled\":" + String(gCurfewConfig.bedtimeEnabled ? "true" : "false") + ",\"start\":\"" +
            String(bStart) + "\",\"end\":\"" + String(bEnd) + "\"},";
    json += "\"study\":{\"enabled\":" + String(gCurfewConfig.studyEnabled ? "true" : "false") + ",\"start\":\"" +
            String(sStart) + "\",\"end\":\"" + String(sEnd) + "\"}";
    json += "}";
    webServer.send(200, "application/json", json);
}

#endif // PISO_IS_HOME
