// Glue between the /update upload and FwManifest.h: approves a signed manifest, then checks the streamed image.

#include "OtaSecurity.h"
#include "FwManifest.h"
#include "FirmwareVersion.h"
#include "LicensePubKey.h"
#include "WebServerAuth.h"
#include "SuperAdminManager.h"
#include "Diagnostics.h"
#include "WebServerModule.h"
#include <WebServer.h>

static const unsigned long APPROVAL_VALID_MS = 15UL * 60UL * 1000UL;

#if CONFIG_IDF_TARGET_ESP32C3
static const char* const THIS_CHIP = "esp32c3";
#else
static const char* const THIS_CHIP = "esp32";
#endif

static fwmanifest::Manifest approved;
static bool haveApproval = false;
static unsigned long approvedAtMs = 0;
static fwmanifest::ImageVerifier verifier;
static bool verifying = false;

bool otaSigningRequired() {
    return LICENSE_PUBKEY_LEN > 0;
}

static const char* describe(fwmanifest::Check c) {
    switch (c) {
    case fwmanifest::Check::Ok:
        return "ok";
    case fwmanifest::Check::NoKey:
        return "no signing key in this firmware";
    case fwmanifest::Check::BadFormat:
        return "malformed manifest";
    case fwmanifest::Check::WrongChip:
        return "this image is for a different chip";
    case fwmanifest::Check::BadSignature:
        return "signature is not valid";
    case fwmanifest::Check::NotNewer:
        return "not newer than the running version";
    }
    return "unknown";
}

void handleApiOtaManifest() {
    if (!checkAdminAuth()) return;
    if (!otaSigningRequired()) {
        webServer.send(200, "application/json", "{\"status\":\"ok\",\"enforced\":false}");
        return;
    }

    fwmanifest::Manifest m;
    m.chip = webServer.arg("chip").c_str();
    m.version = webServer.arg("version").c_str();
    m.sha256Hex = webServer.arg("sha256").c_str();
    m.size = (uint32_t)webServer.arg("size").toInt();
    String sig = webServer.arg("sig");

    bool downgrade = false;
    if (webServer.arg("allow_downgrade") == "1") {
        if (!authenticateSuperAdmin()) {
            webServer.send(
                403, "application/json",
                "{\"status\":\"error\",\"message\":\"Installing an older version needs the super-admin password.\"}");
            return;
        }
        downgrade = true;
    }

    fwmanifest::Check r = fwmanifest::checkManifest(m, sig.c_str(), LICENSE_PUBKEY_DER, LICENSE_PUBKEY_LEN, THIS_CHIP,
                                                    PISO_FW_VERSION, downgrade);
    if (r != fwmanifest::Check::Ok) {
        haveApproval = false;
        diagLog("[OTA] Manifest refused: %s\n", describe(r));
        webServer.send(400, "application/json",
                       String("{\"status\":\"error\",\"message\":\"Update refused: ") + describe(r) + ".\"}");
        return;
    }
    approved = m;
    haveApproval = true;
    approvedAtMs = millis();
    diagLog("[OTA] Signed manifest accepted for v%s (%u bytes)\n", m.version.c_str(), (unsigned)m.size);
    webServer.send(200, "application/json", "{\"status\":\"ok\",\"enforced\":true}");
}

bool otaStartImage(String& err) {
    verifying = false;
    if (!otaSigningRequired()) {
        diagLog("[OTA] WARNING: no signing key in this firmware, accepting an unsigned image.\n");
        return true;
    }
    if (!haveApproval || millis() - approvedAtMs > APPROVAL_VALID_MS) {
        haveApproval = false;
        err = "OTA rejected: a signed manifest is required (use the dashboard's update page or sign_firmware.py).";
        return false;
    }
    haveApproval = false; // one manifest, one upload
    if (!verifier.begin(approved)) {
        err = "OTA rejected: could not start image verification.";
        return false;
    }
    verifying = true;
    return true;
}

bool otaFeedImage(const uint8_t* data, size_t len, String& err) {
    if (!verifying) return true;
    if (!verifier.update(data, len)) {
        verifying = false;
        verifier.end();
        err = "OTA rejected: the image is larger than the signed manifest says.";
        return false;
    }
    return true;
}

bool otaFinishImage(String& err) {
    if (!verifying) return true;
    verifying = false;
    if (!verifier.finish()) {
        err = "OTA rejected: the image does not match its signed manifest.";
        return false;
    }
    return true;
}

void otaAbortImage() {
    verifying = false;
    verifier.end();
}
