// HTTP server and gateway endpoints for the headless PisoWiFi coin box.

#include "WebServerModule.h"
#include "CoinCredit.h"
#include "CoinSlotManager.h"
#include "Config.h"
#include "Diagnostics.h"
#include "FirmwareVersion.h"
#include "GatewayAuth.h"
#include "GatewayCoinslot.h"
#include "GatewayEvent.h"
#include "GatewaySessionStore.h"
#include "HardwareManager.h"
#include "InputSafety.h"
#include "OtaCheck.h"
#include "OtaRollback.h"
#include "OtaSecurity.h"
#include "PinRules.h"
#include "PulseGuard.h"
#include "SetupGate.h"
#include "WebServerAuth.h"
#include <Update.h>
#include <WiFi.h>
#include <esp_random.h>
#include <esp_task_wdt.h>
#include "esp_wifi.h"

WebServer webServer(80);

static gatewayauth::NonceStore nonceStore;

static bool otaUpdateSuccess = false;
static bool otaFirstChunkReceived = false;
static bool otaIsValidBinary = true;
static String otaErrorMsg = "";

static void sendJson(int code, const String& body) {
    webServer.sendHeader("Cache-Control", "no-store");
    webServer.send(code, "application/json", body);
}

static void sendError(int code, const char* error) {
    sendJson(code, String("{\"success\":false,\"error\":\"") + error + "\"}");
}

static void refuseSave(const char* code, const char* message) {
    sendJson(400, String("{\"success\":false,\"error\":\"") + code + "\",\"message\":\"" + message + "\"}");
}

static const char* slotStateLabel(CoinSlotState state) {
    switch (state) {
    case CoinSlotState::IDLE:
        return "IDLE";
    case CoinSlotState::ARMED:
        return "ARMED";
    case CoinSlotState::DRAINING:
        return "DRAINING";
    }
    return "UNKNOWN";
}

// clang-format off
static const char UPGRADE_BANNER_HTML[] PROGMEM =
    "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
    "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
    "<meta name=\"color-scheme\" content=\"light dark\">"
    "<title>TechNet PisoWiFi · Upgrade</title><style>"
    ":root{color-scheme:light dark;--bg:#fafafa;--s1:#fff;--s2:#f4f4f5;--tx:#09090b;--mut:#6a6a73;"
    "--ln:#e4e4e7;--ln2:#d4d4d8;--pri:#09090b;--prifg:#fafafa;--bfg:#27272a;--teal:#0f9f7a;"
    "--gold:#8a5a00;--goldf:#d9a21a;--ok:#15803d}"
    "@media(prefers-color-scheme:dark){:root:not([data-theme=light]){--bg:#09090b;--s1:#0f0f11;"
    "--s2:#18181b;--tx:#fafafa;--mut:#8b8b94;--ln:#232327;--ln2:#33333a;--pri:#fafafa;"
    "--prifg:#09090b;--bfg:#e4e4e7;--teal:#2dd4a4;--gold:#e7b84a;--goldf:#5b4713;--ok:#4ade80}}"
    ":root[data-theme=dark]{--bg:#09090b;--s1:#0f0f11;--s2:#18181b;--tx:#fafafa;--mut:#8b8b94;"
    "--ln:#232327;--ln2:#33333a;--pri:#fafafa;--prifg:#09090b;--bfg:#e4e4e7;--teal:#2dd4a4;"
    "--gold:#e7b84a;--goldf:#5b4713;--ok:#4ade80}"
    "*{box-sizing:border-box}"
    "body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:24px;"
    "background:var(--bg);color:var(--tx);font:14px/21px system-ui,-apple-system,\"Segoe UI\",Roboto,sans-serif}"
    ".wrap{width:100%;max-width:740px}"
    ".banner{background:var(--s1);border:1px solid var(--ln);border-radius:16px;padding:32px;"
    "box-shadow:0 1px 2px rgba(0,0,0,.04)}"
    ".badge{display:inline-flex;align-items:center;gap:6px;padding:3px 11px;border-radius:99px;"
    "border:1px solid var(--goldf);color:var(--gold);font-size:12px;font-weight:600;margin-bottom:14px}"
    "h1{margin:0 0 10px;font-size:24px;line-height:30px;font-weight:650;letter-spacing:-.02em}"
    ".lead{margin:0 0 24px;color:var(--mut);font-size:15px;line-height:22px;max-width:62ch}"
    ".lead b{color:var(--tx);font-weight:600}"
    ".feats{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:12px;margin:0 0 26px}"
    ".feat{background:var(--s2);border:1px solid var(--ln);border-radius:12px;padding:16px}"
    ".feat b{display:flex;align-items:center;gap:8px;font-size:13px;line-height:18px;font-weight:600;margin-bottom:6px}"
    ".feat svg{width:16px;height:16px;flex:none;stroke:var(--teal);fill:none;stroke-width:1.8;"
    "stroke-linecap:round;stroke-linejoin:round}"
    ".feat p{margin:0;color:var(--mut);font-size:12.5px;line-height:18px}"
    ".cta{display:flex;flex-wrap:wrap;align-items:center;gap:14px}"
    ".go{display:inline-flex;align-items:center;gap:8px;min-height:42px;padding:0 20px;border-radius:10px;"
    "background:var(--pri);color:var(--prifg);font:inherit;font-size:14px;font-weight:650;"
    "text-decoration:none;transition:opacity .15s}"
    ".go:hover{opacity:.88}.go:focus-visible{outline:2px solid var(--bfg);outline-offset:2px}"
    ".go svg{width:16px;height:16px;stroke:currentColor;fill:none;stroke-width:2;"
    "stroke-linecap:round;stroke-linejoin:round}"
    ".sub{color:var(--mut);font-size:12px;line-height:17px}"
    ".foot{margin-top:24px;padding-top:16px;border-top:1px solid var(--ln);display:flex;flex-wrap:wrap;"
    "align-items:center;justify-content:space-between;gap:8px;color:var(--mut);font-size:12px}"
    ".dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:var(--ok);margin-right:6px}"
    "@media(max-width:560px){body{padding:14px}.banner{padding:22px 18px}h1{font-size:20px;line-height:26px}}"
    "</style><script>"
    "(function(){var r=document.documentElement;function ap(t){if(t==='dark'||t==='light')r.setAttribute('data-theme',t)}"
    "try{var q=new URLSearchParams(location.search);ap(q.get('theme'))}catch(e){}"
    "window.addEventListener('message',function(e){"
    "if(window.parent!==window&&e.source!==window.parent)return;"
    "try{if(new URL(e.origin).hostname!==location.hostname)return}catch(x){return}"
    "if(e.data&&e.data.piso==='theme')ap(e.data.theme)});})();"
    "</script></head><body><main class=\"wrap\"><section class=\"banner\">"
    "<div class=\"badge\">TechNet PisoHub Upgrade</div>"
    "<h1>Expand Your Shop with TechNet Solutions</h1>"
    "<p class=\"lead\">This coin box is currently running standalone <b>TechNet PisoWiFi</b> firmware "
    "(v" PISO_FW_VERSION "). Upgrade to the full <b>TechNet PisoHub</b> package to unlock additional "
    "revenue streams, multi-unit management, and turnkey hardware expansion built for your shop.</p>"
    "<div class=\"feats\">"
    "<div class=\"feat\"><b><svg viewBox=\"0 0 24 24\"><path d=\"M12 2v20M17 5H9.5a3.5 3.5 0 0 0 0 7h5"
    "a3.5 3.5 0 0 1 0 7H6\"/></svg>Extra Daily Revenue</b>"
    "<p>Add a second income stream alongside your PisoWiFi hotspot using your existing coin box.</p></div>"
    "<div class=\"feat\"><b><svg viewBox=\"0 0 24 24\"><rect x=\"3\" y=\"3\" width=\"7\" height=\"9\" rx=\"1\"/>"
    "<rect x=\"14\" y=\"3\" width=\"7\" height=\"5\" rx=\"1\"/><rect x=\"14\" y=\"12\" width=\"7\" height=\"9\" rx=\"1\"/>"
    "<rect x=\"3\" y=\"16\" width=\"7\" height=\"5\" rx=\"1\"/></svg>Unified Console Control</b>"
    "<p>Monitor coin collections, manage connected units, and push updates from one dashboard.</p></div>"
    "<div class=\"feat\"><b><svg viewBox=\"0 0 24 24\"><path d=\"M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z\"/>"
    "<path d=\"m9 12 2 2 4-4\"/></svg>Turnkey Setup &amp; Support</b>"
    "<p>Direct assistance, ready-to-deploy expansion kits, and genuine signed upgrades from TechNet.</p></div>"
    "</div>"
    "<div class=\"cta\">"
    "<a class=\"go\" href=\"https://www.facebook.com/pro"
    "file.php?id=61596047946772\" target=\"_blank\" rel=\"noopener noreferrer\">"
    "Contact TechNet Solutions"
    "<svg viewBox=\"0 0 24 24\"><path d=\"M14 4h6v6M20 4l-9 9M18 13v5a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h5\"/>"
    "</svg></a>"
    "<span class=\"sub\">Message TechNet Solutions on Facebook to ask about full PisoHub upgrades.</span>"
    "</div>"
    "<div class=\"foot\">"
    "<span><span class=\"dot\"></span>Coin Box Online &middot; Firmware v" PISO_FW_VERSION "</span>"
    "<span>TechNet PisoWiFi Edition</span>"
    "</div>"
    "</section></main></body></html>";
// clang-format on

static void handleRoot() {
    if (!checkAdminAuth()) return;
    webServer.sendHeader("Cache-Control", "no-store");
    webServer.send_P(200, "text/html; charset=utf-8", UPGRADE_BANNER_HTML);
}

static void handleApiStatus() {
    if (!checkAdminAuth()) return;

    int rssi = WiFi.RSSI();
    int quality = 0;
    bool isConnected = (WiFi.status() == WL_CONNECTED);
    if (isConnected) {
        if (rssi <= -100)
            quality = 0;
        else if (rssi >= -50)
            quality = 100;
        else
            quality = 2 * (rssi + 100);
    }

    String qualityStatus = "Disconnected";
    if (isConnected) {
        if (quality >= 75)
            qualityStatus = "Excellent";
        else if (quality >= 50)
            qualityStatus = "Good";
        else if (quality >= 25)
            qualityStatus = "Fair";
        else
            qualityStatus = "Weak";
    }

    static String json;
    json = "{";
    json.reserve(512);
    json += "\"ok\":true,";
    json += "\"firmware\":\"" PISO_FW_VERSION "\",";
    json += "\"default_credentials\":" + String(defaultCredentialsActive() ? "true" : "false") + ",";
    json += "\"wifi\":{";
    json += "\"rssi\":" + String(rssi) + ",";
    json += "\"quality\":" + String(quality) + ",";
    json += "\"status\":\"" + qualityStatus + "\",";
    json += "\"ssid\":\"" + jsonEsc(wifiSsid) + "\",";
    json += "\"ip\":\"" + jsonEsc(WiFi.localIP().toString()) + "\",";
    json += "\"connected\":" + String(isConnected ? "true" : "false");
    json += "},";
    json += "\"coin_slot\":{";
    json += "\"state\":\"" + String(slotStateLabel(getCoinSlotState())) + "\",";
    json += "\"pending_sessions\":" + String(getPendingPaymentCount()) + ",";
    json += "\"storage_ready\":" + String(isPaymentStorageReady() ? "true" : "false");
    json += "}";
    json += "}";
    sendJson(200, json);
}

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
    if (webServer.hasArg("ts")) {
        uint32_t ts = (uint32_t)webServer.arg("ts").toInt();
        if (ts >= 1700000000UL) {
            updateMasterTime((uint64_t)ts * 1000ULL, "GATEWAY_ROUTER");
        }
    }
    return session;
}

static void handleGatewayChallenge() {
    if (!gatewayConfigured()) {
        sendError(503, "GATEWAY_DISABLED");
        return;
    }
    uint8_t random[gatewayauth::NONCE_BYTES];
    for (size_t i = 0; i < sizeof(random); i += 4) {
        uint32_t r = esp_random();
        for (size_t j = 0; j < 4 && i + j < sizeof(random); j++)
            random[i + j] = (uint8_t)(r >> (8 * j));
    }
    std::string nonce = nonceStore.issue(random, (uint32_t)millis());
    sendJson(200, String("{\"nonce\":\"") + nonce.c_str() + "\"}");
}

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
    String lifetime = String(gatewayStore().lifetimePulses());
    String slotFree = st.slotFree ? "true" : "false";
    String readyIn = String(st.readyInMs);
    String perCoin = String(st.minutesPerCoin);
    String facts =
        state + ":" + armedRemaining + ":" + pulses + ":" + lifetime + ":" + slotFree + ":" + readyIn + ":" + perCoin;
    return String("{\"success\":true,\"session\":\"") + jsonEsc(session) + "\",\"state\":\"" + state +
           "\",\"armed_remaining\":" + armedRemaining + ",\"pulses\":" + pulses + ",\"minutes_per_coin\":" + perCoin +
           ",\"ready_in_ms\":" + readyIn + ",\"slot_free\":" + slotFree +
           ",\"events\":true,\"lifetime_pulses\":" + lifetime + ",\"lifetime_wifi_pulses\":" + lifetime +
           ",\"rsig\":\"" + responseSignature(action, session, facts) + "\"}";
}

static void handleGatewayArm() {
    String session = authorizedSession("arm");
    if (session.length() == 0) return;
    int duration = webServer.hasArg("duration") ? webServer.arg("duration").toInt() : GATEWAY_DEFAULT_ARM_SECONDS;
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

static void handleGatewayStatus() {
    String session = authorizedSession("status");
    if (session.length() == 0) return;
    sendJson(200, statusJson("status", session));
}

static void handleGatewayRelease() {
    String session = authorizedSession("release");
    if (session.length() == 0) return;
    gatewayRelease(session);
    sendJson(200, statusJson("release", session));
}

static void handleGatewayAck() {
    String session = authorizedSession("ack");
    if (session.length() == 0) return;
    int acknowledged = 0;
    switch (gatewayAcknowledge(session, acknowledged)) {
    case GatewayAckStatus::Busy:
        sendError(409, "SLOT_BUSY");
        return;
    case GatewayAckStatus::StorageError:
        sendError(503, "ACK_INCOMPLETE");
        return;
    case GatewayAckStatus::Ok: {
        String count = String(acknowledged);
        sendJson(200, String("{\"success\":true,\"session\":\"") + jsonEsc(session) + "\",\"acknowledged_pulses\":" +
                          count + ",\"rsig\":\"" + responseSignature("ack", session, count) + "\"}");
        return;
    }
    }
}

static void handleGatewayConfig() {
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

static void handleSave() {
    if (!checkAdminAuth()) return;

    int newCoinPin = universalCoinPin, newRelayPin = relayPin, newLedPin = ledPin;
    if (webServer.hasArg(NVS_KEY_U_COIN_PIN))
        newCoinPin = pinrules::parsePin(webServer.arg(NVS_KEY_U_COIN_PIN).c_str());
    if (webServer.hasArg(NVS_KEY_RELAY_PIN)) newRelayPin = pinrules::parsePin(webServer.arg(NVS_KEY_RELAY_PIN).c_str());
    if (webServer.hasArg(NVS_KEY_LED_PIN)) newLedPin = pinrules::parsePin(webServer.arg(NVS_KEY_LED_PIN).c_str());

    const char* pinProblem =
        pinrules::problem(pinrules::THIS_CHIP, newCoinPin, newRelayPin, newLedPin, HARDWARE_RESET_PIN);
    if (pinProblem) {
        refuseSave("BAD_PIN", pinProblem);
        return;
    }

    if (webServer.hasArg(NVS_KEY_ADMIN_PW)) {
        String newPw = webServer.arg(NVS_KEY_ADMIN_PW);
        if (newPw != webPassword && !setupgate::passwordAcceptable(newPw.c_str())) {
            refuseSave("WEAK_PASSWORD", "Use at least 8 characters, and not the default password.");
            return;
        }
    }

    const int oldCoinPin = universalCoinPin;
    const bool oldPulseGuard = coinPulseGuard;
    const int oldPulseMinMs = coinPulseMinMs;

    prefs.begin(NVS_NAMESPACE, false);
    if (webServer.hasArg(NVS_KEY_WIFI_SSID)) {
        wifiSsid = webServer.arg(NVS_KEY_WIFI_SSID);
        prefs.putString(NVS_KEY_WIFI_SSID, wifiSsid);
    }
    if (webServer.hasArg(NVS_KEY_WIFI_PASS)) {
        wifiPass = webServer.arg(NVS_KEY_WIFI_PASS);
        prefs.putString(NVS_KEY_WIFI_PASS, wifiPass);
    }
    if (webServer.hasArg(NVS_KEY_U_COIN_PIN)) {
        universalCoinPin = newCoinPin;
        prefs.putInt(NVS_KEY_U_COIN_PIN, universalCoinPin);
    }
    if (webServer.hasArg(NVS_KEY_LED_PIN)) {
        ledPin = newLedPin;
        prefs.putInt(NVS_KEY_LED_PIN, ledPin);
    }
    if (webServer.hasArg(NVS_KEY_LED_ACTIVE_LOW)) {
        ledActiveLow = (webServer.arg(NVS_KEY_LED_ACTIVE_LOW) == "1");
        prefs.putBool(NVS_KEY_LED_ACTIVE_LOW, ledActiveLow);
    }
    if (webServer.hasArg(NVS_KEY_RELAY_PIN)) {
        relayPin = newRelayPin;
        prefs.putInt(NVS_KEY_RELAY_PIN, relayPin);
    }
    if (webServer.hasArg(NVS_KEY_ADMIN_PW)) {
        String newPw = webServer.arg(NVS_KEY_ADMIN_PW);
        if (newPw != webPassword) {
            webPassword = newPw;
            adminPwChanged = true;
            prefs.putString(NVS_KEY_ADMIN_PW, webPassword);
            prefs.putBool(NVS_KEY_ADMIN_PW_CHANGED, true);
        }
    }
    if (webServer.hasArg("minutes_per_coin")) {
        int m = webServer.arg("minutes_per_coin").toInt();
        if (m >= 1) {
            minutesPerCoin = clampMinutesPerCoin(m);
            prefs.putInt(NVS_KEY_MINS_PER_COIN, minutesPerCoin);
        }
    }
    if (webServer.hasArg(NVS_KEY_RELAY_ACTIVE_LOW)) {
        relayActiveLow =
            (webServer.arg(NVS_KEY_RELAY_ACTIVE_LOW) == "1" || webServer.arg(NVS_KEY_RELAY_ACTIVE_LOW) == "true");
        prefs.putBool(NVS_KEY_RELAY_ACTIVE_LOW, relayActiveLow);
    }
    if (webServer.hasArg(NVS_KEY_PULSE_GUARD)) {
        coinPulseGuard = (webServer.arg(NVS_KEY_PULSE_GUARD) == "1" || webServer.arg(NVS_KEY_PULSE_GUARD) == "true");
        prefs.putBool(NVS_KEY_PULSE_GUARD, coinPulseGuard);
    }
    if (webServer.hasArg(NVS_KEY_PULSE_MIN_MS)) {
        coinPulseMinMs = pulseMinMsClamp(webServer.arg(NVS_KEY_PULSE_MIN_MS).toInt());
        prefs.putInt(NVS_KEY_PULSE_MIN_MS, coinPulseMinMs);
    }
    prefs.end();

    requestCoinSlotHardwareConfig(universalCoinPin != oldCoinPin, coinPulseGuard != oldPulseGuard,
                                  coinPulseMinMs != oldPulseMinMs);
    setLedHardware(currentLedState == LED_STATE_CONNECTED);
    processRelayState();

    Serial.println("\n[+] Config updated and saved.");
    webServer.send(200, "text/plain", "OK");
}

static void handleReboot() {
    if (!checkAdminAuth()) return;
    Serial.println("\n[HTTP API] Reboot request received.");
    if (!canPerformRebootOrOta()) {
        webServer.send(409, "text/plain", "BUSY: Unpersisted transactions in RAM");
        return;
    }
    webServer.send(200, "text/plain", "REBOOTING");
    delay(500);
    diagNoteRestartReason("admin-reboot");
    ESP.restart();
}

static void handleFactoryReset() {
    if (!checkAdminAuth()) return;
    Serial.println("\n[HTTP API] Factory reset request received.");
    if (!canPerformRebootOrOta()) {
        webServer.send(409, "text/plain", "BUSY: Unpersisted transactions in RAM");
        return;
    }
    factoryResetDefaults();
    webServer.send(200, "text/plain", "OK");
    delay(1000);
    diagNoteRestartReason("factory-reset");
    ESP.restart();
}

void setupWebServer() {
    webServer.on("/", HTTP_GET, handleRoot);
    webServer.on("/api/status", HTTP_GET, handleApiStatus);
    webServer.on("/save", HTTP_POST, handleSave);
    webServer.on("/reboot", HTTP_POST, handleReboot);
    webServer.on("/factory_reset", HTTP_POST, handleFactoryReset);

    webServer.on("/api/gateway/challenge", HTTP_GET, handleGatewayChallenge);
    webServer.on("/api/gateway/arm", HTTP_ANY, handleGatewayArm);
    webServer.on("/api/gateway/status", HTTP_ANY, handleGatewayStatus);
    webServer.on("/api/gateway/release", HTTP_ANY, handleGatewayRelease);
    webServer.on("/api/gateway/ack", HTTP_ANY, handleGatewayAck);
    webServer.on("/api/gateway/config", HTTP_ANY, handleGatewayConfig);

    webServer.on("/api/relay", HTTP_ANY, []() {
        if (!checkAdminAuth()) return;
        if ((webServer.hasArg("state") || webServer.hasArg("invert")) && webServer.method() != HTTP_POST) {
            webServer.send(405, "application/json", "{\"status\":\"error\",\"message\":\"Use POST\"}");
            return;
        }
        if (isCoinSlotBusy("")) {
            webServer.send(
                409, "application/json",
                "{\"status\":\"error\",\"message\":\"Coin slot is currently active or draining. Manual relay override rejected.\"}");
            return;
        }
        bool hasInvert = webServer.hasArg("invert");
        if (hasInvert) {
            prefs.begin(NVS_NAMESPACE, false);
            relayActiveLow = (webServer.arg("invert") == "1" || webServer.arg("invert") == "true");
            prefs.putBool(NVS_KEY_RELAY_ACTIVE_LOW, relayActiveLow);
            prefs.end();
        }
        if (webServer.hasArg("state")) {
            bool state = (webServer.arg("state") == "1" || webServer.arg("state") == "true");
            relayManualTest(state);
            webServer.send(200, "application/json",
                           "{\"status\":\"ok\",\"relay_pin\":" + String(relayPin) + ",\"state\":" +
                               String(state ? 1 : 0) + ",\"active_low\":" + String(relayActiveLow ? 1 : 0) + "}");
            return;
        }
        webServer.send(200, "application/json",
                       "{\"status\":\"ok\",\"relay_pin\":" + String(relayPin) +
                           ",\"active_low\":" + String(relayActiveLow ? 1 : 0) + "}");
    });

    webServer.on("/api/diagnostics", HTTP_GET, handleApiDiagnostics);
    webServer.on("/api/ota/manifest", HTTP_POST, handleApiOtaManifest);
    webServer.on("/update", HTTP_GET, []() {
        if (!checkAdminAuth()) return;
#if CONFIG_IDF_TARGET_ESP32C3
        const char* chip = "esp32c3";
#else
        const char* chip = "esp32";
#endif
        sendJson(200, String("{\"ok\":true,\"firmware\":\"") + PISO_FW_VERSION + "\",\"chip\":\"" + chip +
                          "\",\"ota_signing_required\":" + (otaSigningRequired() ? "true" : "false") + "}");
    });
    webServer.on(
        "/update", HTTP_POST,
        []() {
            if (!checkAdminAuth()) return;
            webServer.sendHeader("Connection", "close");
            if (!otaIsValidBinary || Update.hasError() || !otaUpdateSuccess) {
                String errStr = otaErrorMsg.length() > 0
                                    ? otaErrorMsg
                                    : ("Flash write failed (Error Code " + String(Update.getError()) + ")");
                webServer.send(400, "text/plain", errStr);
            } else {
                unsigned long waitStart = millis();
                while (hasUnpersistedPayments() && millis() - waitStart < 10000) {
                    processGatewaySessionStore();
                    esp_task_wdt_reset();
                    delay(100);
                }
                webServer.send(200, "text/plain", "SUCCESS");
                delay(1000);
                diagNoteRestartReason("firmware-update");
                ESP.restart();
            }
        },
        []() {
            if (!checkAdminAuth()) return;
            HTTPUpload& upload = webServer.upload();

            if (upload.status == UPLOAD_FILE_START) {
                otaUpdateSuccess = false;
                otaFirstChunkReceived = false;
                otaIsValidBinary = true;
                otaErrorMsg = "";
                Update.clearError();

                if (!canPerformRebootOrOta()) {
                    otaIsValidBinary = false;
                    otaErrorMsg = "OTA blocked: unpersisted transactions in RAM";
                    diagLog("[OTA] Aborted: unpersisted transactions in RAM");
                    return;
                }

                if (otaImageOnTrial()) {
                    otaIsValidBinary = false;
                    otaErrorMsg =
                        "OTA refused: the box is still checking the firmware it was just updated to (about a minute). Try again shortly.";
                    diagLog("[OTA] %s\n", otaErrorMsg.c_str());
                    return;
                }

                diagCount(DiagCounter::OtaAttempts);
                diagLog("[OTA] Starting firmware flash: %s\n", upload.filename.c_str());

                String signErr;
                if (!otaStartImage(signErr)) {
                    otaIsValidBinary = false;
                    otaErrorMsg = signErr;
                    diagLog("[OTA] %s\n", otaErrorMsg.c_str());
                    return;
                }

                otaNoteUploadStart(WiFi.status() == WL_CONNECTED);
                if (!Update.begin(UPDATE_SIZE_UNKNOWN, U_FLASH)) {
                    otaIsValidBinary = false;
                    otaErrorMsg = "Failed to begin flash partition write (Error: " + String(Update.getError()) + ")";
                    diagLog("[OTA] Error: %s\n", otaErrorMsg.c_str());
                }
            } else if (upload.status == UPLOAD_FILE_WRITE) {
                esp_task_wdt_reset();
                if (!otaIsValidBinary) return;

                if (!otaFirstChunkReceived && upload.currentSize > 0) {
                    otaFirstChunkReceived = true;
#if CONFIG_IDF_TARGET_ESP32C3
                    const uint16_t expectedChip = otacheck::CHIP_ESP32_C3;
#else
                    const uint16_t expectedChip = otacheck::CHIP_ESP32;
#endif
                    otacheck::Result headerCheck =
                        otacheck::checkImageHeader(upload.buf, upload.currentSize, expectedChip);
                    if (headerCheck != otacheck::OK) {
                        otaIsValidBinary = false;
                        otaErrorMsg = String("OTA rejected: ") + otacheck::describe(headerCheck);
                        diagLog("[OTA] %s\n", otaErrorMsg.c_str());
                        return;
                    }
                }

                if (upload.currentSize > 0) {
                    String signErr;
                    if (!otaFeedImage(upload.buf, upload.currentSize, signErr)) {
                        otaIsValidBinary = false;
                        otaErrorMsg = signErr;
                        diagLog("[OTA] %s\n", otaErrorMsg.c_str());
                        return;
                    }
                    if (Update.write(upload.buf, upload.currentSize) != upload.currentSize) {
                        otaIsValidBinary = false;
                        otaErrorMsg = "Flash write failed at offset " + String(Update.progress()) +
                                      " (Error: " + String(Update.getError()) + ")";
                        diagLog("[OTA] Error: %s\n", otaErrorMsg.c_str());
                    } else {
                        Serial.print(".");
                    }
                }
            } else if (upload.status == UPLOAD_FILE_END) {
                esp_task_wdt_reset();
                Serial.println();
                if (otaIsValidBinary && !canPerformRebootOrOta()) {
                    otaIsValidBinary = false;
                    otaErrorMsg = "OTA blocked: unpersisted transactions in RAM";
                    diagLog("[OTA] Aborted at finalize: unpersisted transactions in RAM");
                }
                if (otaIsValidBinary) {
                    String signErr;
                    if (!otaFinishImage(signErr)) {
                        otaIsValidBinary = false;
                        otaErrorMsg = signErr;
                        diagLog("[OTA] %s\n", otaErrorMsg.c_str());
                    }
                }
                if (otaIsValidBinary) {
                    if (Update.end(true)) {
                        diagLog("[OTA] Firmware flashing verified & completed successfully: %u bytes\n",
                                upload.totalSize);
                        otaUpdateSuccess = true;
                    } else {
                        otaIsValidBinary = false;
                        otaErrorMsg =
                            "Firmware verification failed after write (Error: " + String(Update.getError()) + ")";
                        diagLog("[OTA] Error: %s\n", otaErrorMsg.c_str());
                    }
                } else {
                    Update.abort();
                }
            } else if (upload.status == UPLOAD_FILE_ABORTED) {
                Update.abort();
                otaAbortImage();
                otaIsValidBinary = false;
                otaErrorMsg = "Upload connection was aborted prematurely.";
                Serial.println("[OTA] Upload aborted by client.");
            }
        });

    static const char* kCollectedHeaders[] = {"Authorization"};
    webServer.collectHeaders(kCollectedHeaders, 1);
    webServer.begin();

    if (WiFi.status() == WL_CONNECTED) {
        Serial.printf("[!] Port 80: Gateway daemon at http://%s:80\n", WiFi.localIP().toString().c_str());
    } else {
        Serial.printf("[!] Wi-Fi disconnected. Waiting for '%s'...\n", wifiSsid.c_str());
    }
}
