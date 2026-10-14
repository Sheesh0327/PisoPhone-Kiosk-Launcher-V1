// HTTP API for the network coin-slot gateway (e.g. an OpenNDS router).
//
//   GET  /api/gateway/challenge                       -> {"nonce": "..."}   (one-time, 30 s)
//   POST /api/gateway/arm      session, duration, nonce, sig   reserve the slot and power the acceptor
//                              [wid, evport]                   also push this window's coin events (GatewayEvent.h)
//   GET  /api/gateway/status   session, nonce, sig             state + coins received so far
//   POST /api/gateway/release  session, nonce, sig             stop accepting; returns coins received
//   POST /api/gateway/ack      session, nonce, sig             coins used; removes them from the box
//   POST /api/gateway/config   key (admin login)               set/clear the shared key (feature off until set)
//
// sig = HMAC-SHA256(key, "gw1:<action>:<session>:<nonce>") where action is arm/status/release/ack.
// A successful answer carries "rsig" = HMAC-SHA256(key, "gw1r:<action>:<session>:<nonce>:<facts>"), see GatewayAuth.h.
#include "WebServerGateway.h"
#include "Config.h"
#include "GatewayAuth.h"
#include "GatewayCoinslot.h"
#include "GatewayEvent.h"
#include "InputSafety.h"
#include "WebServerAuth.h"
#include "WebServerModule.h"
#include <WebServer.h>

static gatewayauth::NonceStore nonceStore;

static void sendJson(int code, const String& body) {
    webServer.sendHeader("Cache-Control", "no-store");
    webServer.send(code, "application/json", body);
}

static void sendError(int code, const char* error) {
    sendJson(code, String("{\"success\":false,\"error\":\"") + error + "\"}");
}

// Common guard for the signed calls. Returns the session id, or "" after having answered.
static String authorizedSession(const char* action) {
    if (!gatewayConfigured()) {
        sendError(503, "GATEWAY_DISABLED");
        return "";
    }
    String session = webServer.arg("session");
    session.trim();
    if (!gatewayauth::validSessionId(session.c_str())) {
        sendError(400, "INVALID_SESSION");
        return "";
    }
    bool ok = gatewayauth::verifyRequest(gatewayKey().c_str(), action, session.c_str(), webServer.arg("nonce").c_str(),
                                         webServer.arg("sig").c_str(), nonceStore, (uint32_t)millis());
    if (!ok) {
        sendError(403, "AUTH_FAILED");
        return "";
    }
    return session;
}

void handleGatewayChallenge() {
    if (!gatewayConfigured()) {
        sendError(503, "GATEWAY_DISABLED");
        return;
    }
    uint8_t random[gatewayauth::NONCE_BYTES];
    for (size_t i = 0; i < sizeof(random); i += 4) {
        uint32_t r = esp_random(); // hardware RNG
        for (size_t j = 0; j < 4 && i + j < sizeof(random); j++)
            random[i + j] = (uint8_t)(r >> (8 * j));
    }
    std::string nonce = nonceStore.issue(random, (uint32_t)millis());
    sendJson(200, String("{\"nonce\":\"") + nonce.c_str() + "\"}");
}

// The router checks "rsig" on every successful answer, so whoever poses as the box on the network cannot report coins.
static String responseSignature(const char* action, const String& session, const String& facts) {
    return String(gatewayauth::signResponse(gatewayKey().c_str(), action, session.c_str(),
                                            webServer.arg("nonce").c_str(), facts.c_str())
                      .c_str());
}

static String statusJson(const char* action, const String& session) {
    GatewayStatus st = gatewayStatus(session);
    String state = String(st.state);
    String armedRemaining = String(st.armedRemainingSec);
    String pulses = String(st.pulses);
    String lifetime = String(totalCoinsLifetime);
    String slotFree = st.slotFree ? "true" : "false";
    String readyIn = String(st.readyInMs);
    String perCoin = String(st.minutesPerCoin);
    // the numbers the signature covers, in this order (tools/pisoportal/src/boxlink.rs reads them back the same way)
    String facts =
        state + ":" + armedRemaining + ":" + pulses + ":" + lifetime + ":" + slotFree + ":" + readyIn + ":" + perCoin;
    return String("{\"success\":true,\"session\":\"") + jsonEsc(session) + "\",\"state\":\"" + state +
           "\",\"armed_remaining\":" + armedRemaining + ",\"pulses\":" + pulses + ",\"minutes_per_coin\":" + perCoin +
           ",\"ready_in_ms\":" + readyIn + ",\"slot_free\":" + slotFree +
           ",\"events\":true,\"lifetime_pulses\":" + lifetime + ",\"rsig\":\"" +
           responseSignature(action, session, facts) + "\"}";
}

void handleGatewayArm() {
    String session = authorizedSession("arm");
    if (session.length() == 0) return;
    int duration = webServer.hasArg("duration") ? webServer.arg("duration").toInt() : GATEWAY_DEFAULT_ARM_SECONDS;
    // Coin events: both wid and evport, valid, or neither. Checked before arming, so a bad request changes nothing.
    bool wantsEvents = webServer.hasArg("wid") || webServer.hasArg("evport");
    String wid = webServer.arg("wid");
    long evport = webServer.hasArg("evport") ? webServer.arg("evport").toInt() : 0;
    if (wantsEvents && !(gatewayevent::validWindowId(wid.c_str()) && gatewayevent::validPort(evport))) {
        sendError(400, "INVALID_EVENT_TARGET");
        return;
    }
    switch (gatewayArm(session, duration)) {
    case GatewayArmResult::Ok:
        if (wantsEvents) gatewaySetEventTarget(session, wid, webServer.client().remoteIP(), (uint16_t)evport);
        sendJson(200, statusJson("arm", session));
        return;
    case GatewayArmResult::Busy:
        sendError(409, "SLOT_BUSY");
        return;
    case GatewayArmResult::StorageUnavailable:
        sendError(503, "STORAGE_UNAVAILABLE");
        return;
    case GatewayArmResult::InvalidSession:
        sendError(400, "INVALID_SESSION");
        return;
    case GatewayArmResult::SetupRequired:
        sendError(403, "SETUP_REQUIRED");
        return;
    }
}

void handleGatewayStatus() {
    String session = authorizedSession("status");
    if (session.length() == 0) return;
    sendJson(200, statusJson("status", session));
}

void handleGatewayRelease() {
    String session = authorizedSession("release");
    if (session.length() == 0) return;
    gatewayRelease(session);
    sendJson(200, statusJson("release", session));
}

void handleGatewayAck() {
    String session = authorizedSession("ack");
    if (session.length() == 0) return;
    int acknowledged = gatewayAcknowledge(session);
    if (acknowledged < 0) {
        sendError(503, "ACK_INCOMPLETE"); // some coins could not be removed from flash: the gateway retries
        return;
    }
    String count = String(acknowledged);
    sendJson(200, String("{\"success\":true,\"session\":\"") + jsonEsc(session) + "\",\"acknowledged_pulses\":" +
                      count + ",\"rsig\":\"" + responseSignature("ack", session, count) + "\"}");
}

void handleGatewayConfig() {
    if (!checkAdminAuth()) return;
    if (webServer.hasArg("key")) {
        String key = webServer.arg("key");
        key.trim();
        if (!gatewaySetKey(key)) {
            sendError(400, "KEY_TOO_SHORT");
            return;
        }
    }
    sendJson(200, String("{\"success\":true,\"configured\":") + (gatewayConfigured() ? "true" : "false") + "}");
}
