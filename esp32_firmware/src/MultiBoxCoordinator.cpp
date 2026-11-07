#include "MultiBoxCoordinator.h"
#include "BoxPersona.h"
#include "Config.h"
#include "GatewayAuth.h"
#include "GatewayCoinslot.h"
#include "CredCrypto.h"
#include <ArduinoJson.h>
#include <HTTPClient.h>
#include <WiFi.h>

static BoxInfo boxes[4];
static unsigned long lastHealthPollMs = 0;
static const unsigned long HEALTH_POLL_INTERVAL_MS = 15000;

static std::string sha256Hex(const std::string& input) {
    const mbedtls_md_info_t* info = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
    if (!info) return "";
    uint8_t digest[32];
    if (mbedtls_md(info, (const unsigned char*)input.data(), input.size(), digest) != 0) return "";
    return credcrypto::hexEncode(digest, sizeof(digest));
}

void initMultiBoxCoordinator() {
    initBoxPersona();
    uint8_t localSlot = getLocalSlot();

    for (uint8_t s = 1; s <= 4; s++) {
        boxes[s - 1].slot = s;
        strncpy(boxes[s - 1].name, getPersonaForSlot(s), sizeof(boxes[s - 1].name) - 1);
        boxes[s - 1].name[sizeof(boxes[s - 1].name) - 1] = '\0';
        strncpy(boxes[s - 1].ip, getDefaultIpForSlot(s), sizeof(boxes[s - 1].ip) - 1);
        boxes[s - 1].ip[sizeof(boxes[s - 1].ip) - 1] = '\0';
        strncpy(boxes[s - 1].role, (s == 1) ? "primary" : "standby", sizeof(boxes[s - 1].role) - 1);
        boxes[s - 1].role[sizeof(boxes[s - 1].role) - 1] = '\0';
        
        if (s == localSlot) {
            strncpy(boxes[s - 1].name, getLocalPersonaName(), sizeof(boxes[s - 1].name) - 1);
            strncpy(boxes[s - 1].role, getLocalRole(), sizeof(boxes[s - 1].role) - 1);
            boxes[s - 1].online = true;
            boxes[s - 1].lastSeenMs = millis();
        } else {
            boxes[s - 1].online = false;
            boxes[s - 1].lastSeenMs = 0;
        }
    }
    Serial.println("[+] MultiBox Coordinator initialized for 4 slots.");
}

const BoxInfo* getBoxInfo(uint8_t slot) {
    if (slot >= 1 && slot <= 4) {
        return &boxes[slot - 1];
    }
    return nullptr;
}

int getOnlineBoxCount() {
    int count = 0;
    for (int i = 0; i < 4; i++) {
        if (boxes[i].online) count++;
    }
    return count;
}

String getBoxesJson() {
    StaticJsonDocument<1024> doc;
    JsonArray arr = doc.to<JsonArray>();
    for (int i = 0; i < 4; i++) {
        JsonObject obj = arr.createNestedObject();
        obj["slot"] = boxes[i].slot;
        obj["name"] = boxes[i].name;
        obj["ip"] = boxes[i].ip;
        obj["role"] = boxes[i].role;
        obj["online"] = boxes[i].online;
        obj["is_local"] = (boxes[i].slot == getLocalSlot());
    }
    String out;
    serializeJson(doc, out);
    return out;
}

static bool fetchRemoteChallenge(const char* ip, String& out_nonce) {
    if (!WiFi.isConnected()) return false;
    HTTPClient http;
    String url = String("http://") + ip + "/api/gateway/challenge";
    http.begin(url);
    http.setTimeout(300);
    int code = http.GET();
    if (code == 200) {
        StaticJsonDocument<256> doc;
        DeserializationError err = deserializeJson(doc, http.getString());
        if (!err && doc.containsKey("nonce")) {
            out_nonce = doc["nonce"].as<String>();
            http.end();
            return true;
        }
    }
    http.end();
    return false;
}

void multiBoxCoordinatorLoop() {
    // If the gateway is not configured yet (e.g. during initial pairing/setup), do not poll remote boxes.
    // Doing so blocks the single core CPU on offline IPs and starves router pairing requests.
    if (!gatewayConfigured()) return;

    uint32_t now = millis();
    if (now - lastHealthPollMs < HEALTH_POLL_INTERVAL_MS) return;
    lastHealthPollMs = now;

    if (!WiFi.isConnected()) return;

    uint8_t localSlot = getLocalSlot();
    boxes[localSlot - 1].online = true;
    boxes[localSlot - 1].lastSeenMs = now;

    // Poll round-robin: only one remote slot per poll interval, so loop() is never blocked
    static uint8_t nextPollSlot = 1;
    for (uint8_t attempt = 0; attempt < 4; attempt++) {
        nextPollSlot = (nextPollSlot % 4) + 1;
        if (nextPollSlot != localSlot) break;
    }
    if (nextPollSlot == localSlot) return;

    uint8_t s = nextPollSlot;
    // If the remote slot has never been seen (lastSeenMs == 0), only poll it once every 60 seconds
    static uint32_t lastUnseenPollMs[4] = {0, 0, 0, 0};
    if (boxes[s - 1].lastSeenMs == 0 && (now - lastUnseenPollMs[s - 1] < 60000)) {
        return;
    }
    lastUnseenPollMs[s - 1] = now;

    yield();
    String nonce;
    if (fetchRemoteChallenge(boxes[s - 1].ip, nonce)) {
        boxes[s - 1].online = true;
        boxes[s - 1].lastSeenMs = now;
    } else {
        // Check if 45 seconds have passed without answer
        if (now - boxes[s - 1].lastSeenMs > 45000) {
            boxes[s - 1].online = false;
        }
    }
    yield();
}

bool armSlot(uint8_t slot, const String& session, int duration, String& out_err) {
    if (slot < 1 || slot > 4) {
        out_err = "INVALID_SLOT";
        return false;
    }
    uint8_t localSlot = getLocalSlot();
    if (slot == localSlot) {
        GatewayArmResult res = gatewayArm(session, duration);
        switch (res) {
        case GatewayArmResult::Ok:
            return true;
        case GatewayArmResult::Busy:
            out_err = "SLOT_BUSY";
            return false;
        case GatewayArmResult::StorageUnavailable:
            out_err = "STORAGE_UNAVAILABLE";
            return false;
        default:
            out_err = "ARM_FAILED";
            return false;
        }
    }

    // Remote box arming over TCP
    const char* ip = boxes[slot - 1].ip;
    String nonce;
    if (!fetchRemoteChallenge(ip, nonce)) {
        out_err = "BOX_OFFLINE";
        return false;
    }

    std::string key = gatewayKey().c_str();
    std::string sig = gatewayauth::signRequest(key, "arm", session.c_str(), nonce.c_str());

    HTTPClient http;
    String url = String("http://") + ip + "/api/gateway/arm";
    http.begin(url);
    http.setTimeout(4000);
    http.addHeader("Content-Type", "application/x-www-form-urlencoded");

    String body = "session=" + session + "&duration=" + String(duration) +
                  "&nonce=" + nonce + "&sig=" + sig.c_str();

    int code = http.POST(body);
    if (code == 200) {
        StaticJsonDocument<512> doc;
        DeserializationError err = deserializeJson(doc, http.getString());
        http.end();
        if (!err && doc["success"] == true) {
            return true;
        }
        out_err = "ARM_PARSE_ERROR";
        return false;
    }
    http.end();
    out_err = (code == 409) ? "SLOT_BUSY" : "REMOTE_ARM_FAILED";
    return false;
}

bool getSlotStatus(uint8_t slot, const String& session, int& out_pulses, int& out_remaining, String& out_state, String& out_err) {
    if (slot < 1 || slot > 4) {
        out_err = "INVALID_SLOT";
        return false;
    }
    uint8_t localSlot = getLocalSlot();
    if (slot == localSlot) {
        GatewayStatus st = gatewayStatus(session);
        out_pulses = st.pulses;
        out_remaining = st.armedRemainingSec;
        out_state = st.state;
        return true;
    }

    const char* ip = boxes[slot - 1].ip;
    String nonce;
    if (!fetchRemoteChallenge(ip, nonce)) {
        out_err = "BOX_OFFLINE";
        return false;
    }

    std::string key = gatewayKey().c_str();
    std::string sig = gatewayauth::signRequest(key, "status", session.c_str(), nonce.c_str());

    HTTPClient http;
    String url = String("http://") + ip + "/api/gateway/status?session=" + session +
                 "&nonce=" + nonce + "&sig=" + sig.c_str();
    http.begin(url);
    http.setTimeout(3000);
    int code = http.GET();
    if (code == 200) {
        StaticJsonDocument<512> doc;
        DeserializationError err = deserializeJson(doc, http.getString());
        http.end();
        if (!err && doc["success"] == true) {
            out_pulses = doc["pulses"] | 0;
            out_remaining = doc["armed_remaining"] | 0;
            out_state = doc["state"].as<String>();
            return true;
        }
    }
    http.end();
    out_err = "STATUS_FAILED";
    return false;
}

bool releaseSlot(uint8_t slot, const String& session) {
    if (slot < 1 || slot > 4) return false;
    uint8_t localSlot = getLocalSlot();
    if (slot == localSlot) {
        gatewayRelease(session);
        return true;
    }

    const char* ip = boxes[slot - 1].ip;
    String nonce;
    if (!fetchRemoteChallenge(ip, nonce)) return false;

    std::string key = gatewayKey().c_str();
    std::string sig = gatewayauth::signRequest(key, "release", session.c_str(), nonce.c_str());

    HTTPClient http;
    String url = String("http://") + ip + "/api/gateway/release";
    http.begin(url);
    http.setTimeout(3000);
    http.addHeader("Content-Type", "application/x-www-form-urlencoded");
    String body = "session=" + session + "&nonce=" + nonce + "&sig=" + sig.c_str();
    int code = http.POST(body);
    http.end();
    return (code == 200);
}

int ackSlot(uint8_t slot, const String& session) {
    if (slot < 1 || slot > 4) return -1;
    uint8_t localSlot = getLocalSlot();
    if (slot == localSlot) {
        return gatewayAcknowledge(session);
    }

    const char* ip = boxes[slot - 1].ip;
    String nonce;
    if (!fetchRemoteChallenge(ip, nonce)) return -1;

    std::string key = gatewayKey().c_str();
    std::string sig = gatewayauth::signRequest(key, "ack", session.c_str(), nonce.c_str());

    HTTPClient http;
    String url = String("http://") + ip + "/api/gateway/ack";
    http.begin(url);
    http.setTimeout(4000);
    http.addHeader("Content-Type", "application/x-www-form-urlencoded");
    String body = "session=" + session + "&nonce=" + nonce + "&sig=" + sig.c_str();
    int code = http.POST(body);
    if (code == 200) {
        StaticJsonDocument<256> doc;
        DeserializationError err = deserializeJson(doc, http.getString());
        http.end();
        if (!err && doc["success"] == true) {
            return doc["acknowledged_pulses"] | 0;
        }
    }
    http.end();
    return -1;
}

bool grantRouterAccess(const String& mac, int minutes, uint32_t upKbps, uint32_t downKbps, String& out_err) {
    if (!WiFi.isConnected()) {
        out_err = "WIFI_DISCONNECTED";
        return false;
    }

    IPAddress gw = WiFi.gatewayIP();
    String routerIp = (gw[0] != 0) ? gw.toString() : "10.0.0.1";

    String clientMac = mac;
    clientMac.trim();
    if (clientMac.indexOf(':') < 0 && clientMac.length() > 0) {
        HTTPClient arpHttp;
        String cUrl = String("http://") + routerIp + "/cgi-bin/piso-nds?action=challenge";
        arpHttp.begin(cUrl);
        arpHttp.setTimeout(2500);
        int cCode = arpHttp.GET();
        String aNonce = "";
        if (cCode == 200) {
            StaticJsonDocument<256> cDoc;
            if (!deserializeJson(cDoc, arpHttp.getString()) && cDoc.containsKey("nonce")) {
                aNonce = cDoc["nonce"].as<String>();
            }
        }
        arpHttp.end();

        if (aNonce.length() > 0) {
            std::string key = gatewayKey().c_str();
            std::string aPayload = aNonce.c_str() + std::string("|arp|") + clientMac.c_str() + "|" + key;
            std::string aSig = sha256Hex(aPayload);

            String aUrl = String("http://") + routerIp + "/cgi-bin/piso-nds?action=arp&ip=" + clientMac +
                          "&nonce=" + aNonce + "&sig=" + aSig.c_str();
            arpHttp.begin(aUrl);
            arpHttp.setTimeout(2500);
            int aCode = arpHttp.GET();
            if (aCode == 200) {
                StaticJsonDocument<256> aDoc;
                if (!deserializeJson(aDoc, arpHttp.getString()) && aDoc.containsKey("mac")) {
                    clientMac = aDoc["mac"].as<String>();
                }
            }
            arpHttp.end();
        }
    }

    // 1. Fetch challenge nonce from router uhttpd CGI
    HTTPClient http;
    String cUrl = String("http://") + routerIp + "/cgi-bin/piso-nds?action=challenge";
    http.begin(cUrl);
    http.setTimeout(3000);
    int cCode = http.GET();
    String nonce = "";
    if (cCode == 200) {
        StaticJsonDocument<256> cDoc;
        DeserializationError err = deserializeJson(cDoc, http.getString());
        if (!err && cDoc.containsKey("nonce")) {
            nonce = cDoc["nonce"].as<String>();
        }
    }
    http.end();

    if (nonce.length() == 0) {
        out_err = "ROUTER_CHALLENGE_FAILED";
        return false;
    }

    // 2. Sign canonical payload: nonce|action|param|key
    // For grant action, param is mac
    std::string key = gatewayKey().c_str();
    std::string payload = nonce.c_str() + std::string("|grant|") + clientMac.c_str() + "|" + key;
    std::string sig = sha256Hex(payload);

    // 3. POST grant request
    String gUrl = String("http://") + routerIp + "/cgi-bin/piso-nds";
    http.begin(gUrl);
    http.setTimeout(4000);
    http.addHeader("Content-Type", "application/x-www-form-urlencoded");

    String postBody = "action=grant&mac=" + clientMac + "&mins=" + String(minutes) +
                      "&up=" + String(upKbps) + "&down=" + String(downKbps) +
                      "&nonce=" + nonce + "&sig=" + sig.c_str();

    int gCode = http.POST(postBody);
    if (gCode == 200) {
        StaticJsonDocument<256> gDoc;
        DeserializationError err = deserializeJson(gDoc, http.getString());
        http.end();
        if (!err && gDoc["ok"] == true) {
            return true;
        }
        out_err = "ROUTER_GRANT_REJECTED";
        return false;
    }
    http.end();
    out_err = "HTTP_" + String(gCode);
    return false;
}

bool deauthRouterAccess(const String& mac, String& out_err) {
    if (!WiFi.isConnected()) return false;
    IPAddress gw = WiFi.gatewayIP();
    String routerIp = (gw[0] != 0) ? gw.toString() : "10.0.0.1";

    HTTPClient http;
    String cUrl = String("http://") + routerIp + "/cgi-bin/piso-nds?action=challenge";
    http.begin(cUrl);
    http.setTimeout(3000);
    int cCode = http.GET();
    String nonce = "";
    if (cCode == 200) {
        StaticJsonDocument<256> cDoc;
        DeserializationError err = deserializeJson(cDoc, http.getString());
        if (!err && cDoc.containsKey("nonce")) {
            nonce = cDoc["nonce"].as<String>();
        }
    }
    http.end();

    if (nonce.length() == 0) return false;

    std::string key = gatewayKey().c_str();
    std::string payload = nonce.c_str() + std::string("|deauth|") + mac.c_str() + "|" + key;
    std::string sig = sha256Hex(payload);

    String gUrl = String("http://") + routerIp + "/cgi-bin/piso-nds";
    http.begin(gUrl);
    http.setTimeout(3000);
    http.addHeader("Content-Type", "application/x-www-form-urlencoded");
    String postBody = "action=deauth&mac=" + mac + "&nonce=" + nonce + "&sig=" + sig.c_str();
    int gCode = http.POST(postBody);
    http.end();
    return (gCode == 200);
}
