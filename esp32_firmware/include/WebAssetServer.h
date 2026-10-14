#ifndef WEB_ASSET_SERVER_H
#define WEB_ASSET_SERVER_H

#include <Arduino.h>

// Static dashboard files (CSS and JavaScript) served gzip-compressed straight from flash.
// See scripts/embed_web.py; the sources live in esp32_firmware/web/.

// Registers GET /assets/<name> for every embedded asset.
void registerWebAssetRoutes();

// Version tag of an asset (changes when its content changes), for the ?v= cache-busting parameter.
String webAssetVersion(const char* name);

#endif // WEB_ASSET_SERVER_H
