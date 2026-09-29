#ifndef WEB_SERVER_CONFIG_H
#define WEB_SERVER_CONFIG_H

#include <Arduino.h>

extern bool otaUpdateSuccess;
extern bool otaFirstChunkReceived;
extern bool otaIsValidBinary;
extern String otaErrorMsg;

void handlePortalRoot();
void handleReboot();
void handleFactoryReset();
void handleResetVault();
void handleSave();
void handleOtaForm();

#endif // WEB_SERVER_CONFIG_H
