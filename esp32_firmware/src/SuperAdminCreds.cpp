#include "SuperAdminCreds.h"
#include "SuperAdminManager.h"
#include "SuperAdminPubKey.h"
#include "CredCrypto.h"
#include "Config.h"
#include "Diagnostics.h"
#include "WebServerModule.h"
#include <ArduinoJson.h>
#include <HTTPClient.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>
#include <freertos/task.h>
#include <mbedtls/md.h>

static const char* K_VER = "sa_ver";
static const char* K_ITER = "sa_iter";
static const char* K_SALT = "sa_salt";
static const char* K_HASH = "sa_hash";

static const unsigned long FIRST_SYNC_DELAY_MS = 60UL * 1000UL;
static const unsigned long SYNC_INTERVAL_MS = 60UL * 60UL * 1000UL;
static const unsigned long RETRY_INTERVAL_MS = 10UL * 60UL * 1000UL;
static const uint32_t MIN_HEAP_FOR_TLS = 60000;
static const size_t MAX_BODY_BYTES = 1024;

// Credentials currently in force. Guarded by credMutex because the web server and the sync
// task both touch them.
static SemaphoreHandle_t credMutex = nullptr;
static uint32_t credVersion = 0;
static uint32_t credIterations = 0;
static String credSaltHex;
static String credHashHex;
static uint8_t cachedOkDigest[32]; // sha256(version || password) of the last accepted password
static bool cachedOkValid = false;

// Result handed from the download task to loop(), which owns flash writes.
static volatile bool syncRunning = false;
static volatile bool pendingReady = false;
static credcrypto::SignedCredentials pendingCreds;
static volatile bool lastSyncOk = false;
static volatile bool lastSyncDone = false;

static unsigned long nextSyncAtMs = 0;
static bool scheduleStarted = false;
static bool keyWarned = false;

static void lockCreds() {
    if (credMutex) xSemaphoreTake(credMutex, portMAX_DELAY);
}
static void unlockCreds() {
    if (credMutex) xSemaphoreGive(credMutex);
}

static void passwordDigest(uint32_t version, const String& pw, uint8_t out[32]) {
    String material = String(version) + ":" + pw;
    mbedtls_md(mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), (const unsigned char*)material.c_str(), material.length(),
               out);
}

void superAdminCredsLoad() {
    if (!credMutex) credMutex = xSemaphoreCreateMutex();
    prefs.begin(NVS_NAMESPACE, false);
    uint32_t ver = prefs.getUInt(K_VER, 0);
    uint32_t iter = prefs.getUInt(K_ITER, 0);
    String salt = prefs.getString(K_SALT, "");
    String hash = prefs.getString(K_HASH, "");
    prefs.end();
    lockCreds();
    if (ver > 0 && iter > 0 && salt.length() > 0 && hash.length() > 0) {
        credVersion = ver;
        credIterations = iter;
        credSaltHex = salt;
        credHashHex = hash;
    } else {
        credVersion = 0;
        credIterations = 0;
        credSaltHex = "";
        credHashHex = "";
    }
    cachedOkValid = false;
    unlockCreds();
    if (ver > 0) diagLog("[CRED] Super-admin password is remotely managed (version %u).\n", (unsigned)ver);
}

bool superAdminCredsManaged() {
    return credVersion > 0;
}
uint32_t superAdminCredsVersion() {
    return credVersion;
}

bool superAdminPasswordOk(const String& candidate) {
    lockCreds();
    uint32_t ver = credVersion, iter = credIterations;
    String salt = credSaltHex, hash = credHashHex;
    bool cacheHit = false;
    if (ver > 0 && cachedOkValid) {
        uint8_t d[32];
        passwordDigest(ver, candidate, d);
        cacheHit = credcrypto::constantTimeEquals(d, cachedOkDigest, 32);
    }
    unlockCreds();

    if (ver == 0) {
        // Not remotely managed yet: only a locally stored password counts; there is no default.
        return candidate.length() > 0 && superAdminPassword.length() > 0 && candidate == superAdminPassword;
    }
    if (cacheHit) return true;
    if (!credcrypto::passwordMatches(candidate.c_str(), salt.c_str(), iter, hash.c_str())) return false;

    lockCreds();
    if (credVersion == ver) {
        passwordDigest(ver, candidate, cachedOkDigest);
        cachedOkValid = true;
    }
    unlockCreds();
    return true;
}

bool superAdminBasicAuthOk() {
    String header = webServer.header("Authorization");
    if (!header.startsWith("Basic ")) return false;
    std::vector<uint8_t> raw;
    if (!credcrypto::base64Decode(header.substring(6).c_str(), raw)) return false;
    String decoded;
    decoded.reserve(raw.size());
    for (uint8_t b : raw)
        decoded += (char)b;
    int colon = decoded.indexOf(':');
    if (colon < 0 || decoded.substring(0, colon) != "superadmin") return false;
    return superAdminPasswordOk(decoded.substring(colon + 1));
}

// ----------------------------------------------------------------------------
// Download (separate task: TLS needs more stack and heap than the main loop should spend)
// ----------------------------------------------------------------------------
static void syncTask(void*) {
    lastSyncOk = false;
    do {
        WiFiClientSecure client;
        // Transport security is not what protects this file; the ECDSA signature is.
        client.setInsecure(); // piso-allow-insecure: the credentials file is verified by its ECDSA signature
        HTTPClient http;
        http.setConnectTimeout(8000);
        http.setTimeout(8000);
        http.setFollowRedirects(HTTPC_STRICT_FOLLOW_REDIRECTS);
        if (!http.begin(client, PISO_CRED_URL)) break;
        int code = http.GET();
        if (code == 404) {
            lastSyncOk = true;
            http.end();
            break;
        } // nothing published yet
        if (code != 200) {
            diagLog("[CRED] Download failed: HTTP %d\n", code);
            http.end();
            break;
        }
        int len = http.getSize();
        if (len > (int)MAX_BODY_BYTES) {
            http.end();
            break;
        }
        String body = http.getString();
        http.end();
        if (body.length() == 0 || body.length() > MAX_BODY_BYTES) break;

        StaticJsonDocument<768> doc;
        if (deserializeJson(doc, body) != DeserializationError::Ok) {
            diagLog("[CRED] credentials.json is not valid JSON.\n");
            break;
        }
        credcrypto::SignedCredentials c;
        c.version = doc["version"] | 0u;
        c.iterations = doc["iter"] | 0u;
        c.saltHex = (const char*)(doc["salt"] | "");
        c.hashHex = (const char*)(doc["hash"] | "");
        std::string sig = (const char*)(doc["sig"] | "");

        credcrypto::CredCheck r =
            credcrypto::checkCredentials(c, sig, SUPER_ADMIN_PUBKEY_DER, SUPER_ADMIN_PUBKEY_LEN, credVersion);
        if (r == credcrypto::CredCheck::Ok) {
            pendingCreds = c;
            pendingReady = true;
            lastSyncOk = true;
        } else if (r == credcrypto::CredCheck::NotNewer) {
            lastSyncOk = true; // already up to date
        } else if (r == credcrypto::CredCheck::BadSignature) {
            diagLog("[CRED] REJECTED credentials.json: signature does not match the built-in key.\n");
        } else {
            diagLog("[CRED] credentials.json has an invalid format.\n");
        }
    } while (false);
    lastSyncDone = true;
    syncRunning = false;
    vTaskDelete(nullptr);
}

static void applyPending() {
    credcrypto::SignedCredentials c = pendingCreds;
    pendingReady = false;
    if (c.version <= credVersion) return;

    prefs.begin(NVS_NAMESPACE, false);
    prefs.putUInt(K_ITER, c.iterations);
    prefs.putString(K_SALT, c.saltHex.c_str());
    prefs.putString(K_HASH, c.hashHex.c_str());
    prefs.putUInt(K_VER, c.version); // written last: marks the set as complete
    prefs.remove("super_admin_pw");  // the plaintext password is no longer kept
    prefs.end();

    lockCreds();
    credVersion = c.version;
    credIterations = c.iterations;
    credSaltHex = c.saltHex.c_str();
    credHashHex = c.hashHex.c_str();
    cachedOkValid = false;
    unlockCreds();
    superAdminPassword = "";
    diagLog("[CRED] Super-admin password updated remotely to version %u.\n", (unsigned)c.version);
}

void superAdminSyncLoop() {
    if (pendingReady) applyPending();

    if (syncRunning) return;
    if (lastSyncDone) {
        lastSyncDone = false;
        nextSyncAtMs = millis() + (lastSyncOk ? SYNC_INTERVAL_MS : RETRY_INTERVAL_MS);
        return;
    }
    if (SUPER_ADMIN_PUBKEY_LEN == 0) {
        if (!keyWarned) {
            keyWarned = true;
            diagLog("[CRED] No signing public key in firmware; remote password sync disabled.\n");
        }
        return;
    }
    if (WiFi.status() != WL_CONNECTED) {
        scheduleStarted = false; // restart the short first-sync delay after each reconnect
        return;
    }
    if (!scheduleStarted) {
        scheduleStarted = true;
        nextSyncAtMs = millis() + FIRST_SYNC_DELAY_MS;
        return;
    }
    if ((long)(millis() - nextSyncAtMs) < 0) return;
    if (ESP.getFreeHeap() < MIN_HEAP_FOR_TLS) {
        nextSyncAtMs = millis() + 60000UL;
        return;
    }
    syncRunning = true;
    if (xTaskCreate(syncTask, "credSync", 10240, nullptr, 1, nullptr) != pdPASS) {
        syncRunning = false;
        nextSyncAtMs = millis() + RETRY_INTERVAL_MS;
    }
}
