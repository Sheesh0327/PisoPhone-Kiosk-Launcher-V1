#ifndef OTA_SECURITY_H
#define OTA_SECURITY_H

#include <Arduino.h>

bool otaSigningRequired();
void handleApiOtaManifest();
bool otaStartImage(String& err);
bool otaFeedImage(const uint8_t* data, size_t len, String& err);
bool otaFinishImage(String& err);
void otaAbortImage();

#endif // OTA_SECURITY_H
