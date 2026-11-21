#if defined(PISO_WORKSHOP_PILOT)
#include "PilotLicense.h"

#include "Config.h"
#include "CredCrypto.h"
#include "PilotTrust.h"
#include <ArduinoJson.h>
#include <HTTPClient.h>
#include <Preferences.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <array>
#include <atomic>
#include <climits>

namespace {

constexpr const char* endpoint = "https://pisophone-pilot-api-test.evankhell897.workers.dev/v1/check-in";
constexpr const char* store = "piso_pilot";
constexpr const char* credentialKey = "credential";
constexpr const char* licenseKey = "license";
constexpr const char* offerKey = "offer";
constexpr int maximumResponseBytes = 896;
enum class State { Waiting, Active, Retired };
std::atomic<State> state{State::Waiting};
std::atomic<int> slotLimit{0};
String boardMac;
String savedLicenseMessage;
std::vector<uint8_t> publicKey;

bool lowercaseHex(const String& value, unsigned int size) {
    if (value.length() != size) return false;
    for (unsigned int i = 0; i < size; ++i) {
        const char c = value[i];
        if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) return false;
    }
    return true;
}

bool decimal(const String& text, int maximum, int& value) {
    if (text.isEmpty() || text.length() > 10 || text[0] == '0') return false;
    value = 0;
    for (unsigned int i = 0; i < text.length(); ++i) {
        char c = text[i];
        if (c < '0' || c > '9' || value > (maximum - (c - '0')) / 10) return false;
        value = value * 10 + c - '0';
    }
    return value <= maximum;
}

bool grant(JsonVariantConst doc, const char* expectedKind, int& revision, int& slots) {
    const String message = doc["message"] | "";
    const String signature = doc["signature"] | "";
    if (message.length() > 120 || signature.length() > 104) return false;
    std::array<String, 6> parts;
    int start = 0;
    for (unsigned int i = 0; i < parts.size() - 1; ++i) {
        int separator = message.indexOf('|', start);
        if (separator < 0) return false;
        parts[i] = message.substring(start, separator);
        start = separator + 1;
    }
    parts.back() = message.substring(start);
    String kit = parts[2];
    kit.replace("-", "");
    if (parts[0] != "piso-pilot-v1" || parts[1] != expectedKind ||
        parts[2].length() != 36 || !lowercaseHex(kit, 32) || parts[3] != boardMac ||
        !decimal(parts[4], INT_MAX, revision) || !decimal(parts[5], 12, slots) || slots < 2) return false;
    return credcrypto::verifySignature(publicKey.data(), publicKey.size(), message.c_str(), signature.c_str());
}

bool applySavedLicense(const String& json) {
    StaticJsonDocument<512> saved;
    if (deserializeJson(saved, json)) return false;
    int revision, slots;
    if (grant(saved.as<JsonVariantConst>(), "retired", revision, slots)) {
        slotLimit.store(0);
        state.store(State::Retired);
        savedLicenseMessage = saved["message"].as<const char*>();
        return true;
    }
    if (state.load() != State::Retired && grant(saved.as<JsonVariantConst>(), "active", revision, slots)) {
        slotLimit.store(slots);
        state.store(State::Active);
        savedLicenseMessage = saved["message"].as<const char*>();
        return true;
    }
    return false;
}

bool request(const String& credential, StaticJsonDocument<1024>& doc,
             int savedRevision = 0, const String& receipt = "") {
    WiFiClientSecure client;
    client.setCACert(pilotCa);
    HTTPClient http;
    if (!http.begin(client, endpoint)) return false;
    http.setConnectTimeout(5000);
    http.setTimeout(4500);
    http.addHeader("Authorization", "Bearer " + credential);
    int code;
    if (savedRevision > 0) {
        http.addHeader("X-Pilot-Saved-Revision", String(savedRevision));
        http.addHeader("X-Pilot-Grant-Receipt", receipt);
        code = http.POST(String());
    } else {
        code = http.GET();
    }
    bool received = false;
    if (code == HTTP_CODE_OK && http.getSize() <= maximumResponseBytes) {
        const String body = http.getString();
        received = body.length() <= maximumResponseBytes && !deserializeJson(doc, body);
    }
    http.end();
    return received;
}

void saveLicense(Preferences& prefs, JsonVariantConst value, const char* kind) {
    int revision, slots;
    const String message = value["message"] | "";
    if (message == savedLicenseMessage || state.load() == State::Retired ||
        !grant(value, kind, revision, slots)) return;
    String out;
    serializeJson(value, out);
    if (prefs.getString(licenseKey, "") == out || prefs.putString(licenseKey, out) == out.length()) {
        applySavedLicense(out);
    } else if (strcmp(kind, "retired") == 0) {
        // Fail closed for this boot while the task retries saving the revocation.
        slotLimit.store(0);
    }
}

void checkIn() {
    if (state.load() == State::Retired || WiFi.status() != WL_CONNECTED || time(nullptr) < 1700000000) return;
    Preferences prefs;
    if (!prefs.begin(store, false)) return;
    const String credential = prefs.getString(credentialKey, "");
    if (!lowercaseHex(credential, 64)) return;

    StaticJsonDocument<1024> doc;
    if (!request(credential, doc)) return;
    const String status = doc["status"] | "";
    if (status == "retired" || status == "active") {
        saveLicense(prefs, doc["grant"].as<JsonVariantConst>(), status.c_str());
    } else if (status == "grant_available") {
        int revision, slots;
        const String receipt = doc["receipt"] | "";
        if (!lowercaseHex(receipt, 64) || !grant(doc["grant"].as<JsonVariantConst>(), "offer", revision, slots)) return;
        const String offerMessage = doc["grant"]["message"] | "";
        StaticJsonDocument<1024> previous;
        const bool sameOffer = !deserializeJson(previous, prefs.getString(offerKey, "")) &&
            previous["receipt"] == receipt && previous["grant"]["message"] == offerMessage;
        if (!sameOffer) {
            String offer;
            serializeJson(doc, offer);
            if (prefs.putString(offerKey, offer) != offer.length()) return;
        }
        doc.clear();
        if (!request(credential, doc, revision, receipt)) return;
        const String confirmed = doc["status"] | "";
        if (confirmed == "active" || confirmed == "retired") {
            saveLicense(prefs, doc["grant"].as<JsonVariantConst>(), confirmed.c_str());
        }
    }
}

void licenseTask(void*) {
    while (true) {
        checkIn();
        vTaskDelay(pdMS_TO_TICKS(15000));
    }
}

} // namespace

void startPilotLicense() {
    boardMac = macAddressStr;
    boardMac.replace(":", "");
    boardMac.toLowerCase();
    if (!lowercaseHex(boardMac, 12) || !credcrypto::base64Decode(pilotGrantPublicKey, publicKey)) return;

    Preferences prefs;
    if (prefs.begin(store, true)) applySavedLicense(prefs.getString(licenseKey, ""));
    xTaskCreate(licenseTask, "pilot-license", 12288, nullptr, 1, nullptr);
}

int pilotLicensedSlots() {
    return slotLimit.load();
}

const char* pilotLicenseState() {
    if (state.load() == State::Retired) return "retired";
    return pilotLicensedSlots() > 0 ? "active" : "awaiting_license";
}

bool provisionPilotCredential(const String& credential) {
    if (!lowercaseHex(credential, 64)) return false;
    Preferences prefs;
    if (!prefs.begin(store, false)) return false;
    String existing = prefs.getString(credentialKey, "");
    if (existing.length() > 0) return existing == credential;
    return prefs.putString(credentialKey, credential) == credential.length();
}
#endif
