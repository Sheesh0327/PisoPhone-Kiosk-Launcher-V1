#ifndef OTA_SECURITY_H
#define OTA_SECURITY_H

#include <Arduino.h>

// Signed firmware updates (see FwManifest.h). With an owner public key built in (LicensePubKey.h) only an
// image matching a signed manifest can be flashed. Without a key the box still takes unsigned images and
// says so in the log (the same transition rule as licenses).

bool otaSigningRequired();

// POST /api/ota/manifest: version, chip, sha256, size, sig (+ allow_downgrade for the super-admin).
void handleApiOtaManifest();

// Called by the /update upload: start, each chunk, finish. On false, `err` says why and nothing may be committed.
bool otaStartImage(String& err);
bool otaFeedImage(const uint8_t* data, size_t len, String& err);
bool otaFinishImage(String& err);
void otaAbortImage();

#endif // OTA_SECURITY_H
