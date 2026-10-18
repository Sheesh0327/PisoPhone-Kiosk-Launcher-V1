// Admin API for the rental phone profiles: GET /api/profiles, POST /api/profiles (the whole list), POST /api/slots/profile.
// A change is checked as a whole first: if anything in it is wrong, nothing is saved.

#include "WebServerProfiles.h"

#include <WebServer.h>

#include "Config.h"
#include "PhoneProfileStore.h"
#include "WebServerAuth.h"
#include "WebServerModule.h"

using namespace phoneprofiles;

static void sendError(int code, const String& message) {
    webServer.send(code, "application/json", "{\"success\":false,\"error\":\"" + message + "\"}");
}

static void sendState() {
    String json = phoneProfilesJson();
    json.setCharAt(0, ','); // "{...}" -> ",...}" so "success" can lead
    webServer.send(200, "application/json", "{\"success\":true" + json);
}

static bool sameName(const std::string& a, const std::string& b) {
    if (a.size() != b.size()) return false;
    for (size_t i = 0; i < a.size(); i++) {
        char x = a[i], y = b[i];
        if (x >= 'A' && x <= 'Z') x = (char)(x - 'A' + 'a');
        if (y >= 'A' && y <= 'Z') y = (char)(y - 'A' + 'a');
        if (x != y) return false;
    }
    return true;
}

// The list the dashboard sends: "id|name|minutes;id|name|minutes", an empty or 0 id for a new profile.
static bool readRequestedList(const std::string& raw, Profile* out, int* count, String* why) {
    Profile current[MAX_PROFILES];
    int currentCount = phoneProfileList(current, MAX_PROFILES);
    int n = 0;
    size_t pos = 0;
    while (pos < raw.size()) {
        size_t end = raw.find(';', pos);
        if (end == std::string::npos) end = raw.size();
        std::string item = raw.substr(pos, end - pos);
        pos = end + 1;
        if (item.empty()) continue;
        if (n >= MAX_PROFILES) {
            *why = "TOO_MANY_PROFILES";
            return false;
        }
        size_t p1 = item.find('|');
        size_t p2 = (p1 == std::string::npos) ? std::string::npos : item.find('|', p1 + 1);
        if (p1 == std::string::npos || p2 == std::string::npos) {
            *why = "BAD_PROFILE";
            return false;
        }
        std::string idText = item.substr(0, p1);
        int id = idText.empty() ? 0 : parseNumber(idText);
        std::string name = item.substr(p1 + 1, p2 - p1 - 1);
        int minutes = parseNumber(item.substr(p2 + 1));
        if (id < 0 || id > MAX_PROFILE_ID) {
            *why = "BAD_PROFILE";
            return false;
        }
        if (!validName(name)) {
            *why = "BAD_NAME";
            return false;
        }
        if (!validMinutes(minutes)) {
            *why = "BAD_MINUTES";
            return false;
        }
        if (id != 0 && !hasId(current, currentCount, id)) {
            *why = "CHANGED_ELSEWHERE"; // another browser changed the list: reload first
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (sameName(out[i].name, name)) {
                *why = "NAME_TAKEN";
                return false;
            }
            if (id != 0 && out[i].id == id) {
                *why = "BAD_PROFILE";
                return false;
            }
        }
        out[n].id = id;
        out[n].name = name;
        out[n].minutes = minutes;
        n++;
    }
    for (int i = 0; i < n; i++) { // ids for the new ones, after the existing ones are known
        if (out[i].id == 0) {
            int id = freeId(out, n);
            if (id == 0) {
                *why = "TOO_MANY_PROFILES";
                return false;
            }
            out[i].id = id;
        }
    }
    *count = n;
    return true;
}

void handleApiProfiles() {
    if (webServer.method() == HTTP_GET) {
        if (!checkAdminAuth()) return;
        sendState();
        return;
    }
    if (webServer.method() != HTTP_POST) {
        sendError(405, "USE_POST");
        return;
    }
    if (!checkAdminAuth()) return;
    if (!webServer.hasArg("list")) {
        sendError(400, "NO_LIST");
        return;
    }
    Profile list[MAX_PROFILES];
    int n = 0;
    String why;
    if (!readRequestedList(std::string(webServer.arg("list").c_str()), list, &n, &why)) {
        sendError(400, why);
        return;
    }
    if (!phoneProfilesSave(list, n)) {
        sendError(500, "NOT_SAVED");
        return;
    }
    pushRatesToPhones(); // the phones show the rate of their profile
    sendState();
}

void handleApiSlotProfile() {
    if (webServer.method() != HTTP_POST) {
        sendError(405, "USE_POST");
        return;
    }
    if (!checkAdminAuth()) return;
    int slot = webServer.hasArg("slot") ? webServer.arg("slot").toInt() : 0;
    if (slot < 1 || slot > MAX_SUPPORTED_SLOTS) {
        sendError(400, "BAD_SLOT");
        return;
    }
    int id = webServer.hasArg("profile") ? parseNumber(std::string(webServer.arg("profile").c_str())) : -1;
    if (id < 0 || id > MAX_PROFILE_ID) {
        sendError(400, "BAD_PROFILE");
        return;
    }
    if (!phoneProfileAssign(slot - 1, id)) {
        sendError(400, id == 0 ? "NOT_SAVED" : "UNKNOWN_PROFILE");
        return;
    }
    pushRateToSlotIndex(slot - 1);
    sendState();
}
