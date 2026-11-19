#ifndef OTA_ROLLBACK_H
#define OTA_ROLLBACK_H

#include <Arduino.h>

void otaRollbackBegin();
void otaRollbackLoop();
void otaNoteUploadStart(bool wifiConnected);
bool otaImageOnTrial();
const char* otaImageStateName();
bool otaLastUpdateWasRolledBack();

#endif // OTA_ROLLBACK_H
