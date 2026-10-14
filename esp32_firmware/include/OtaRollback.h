#ifndef OTA_ROLLBACK_H
#define OTA_ROLLBACK_H

// Makes the bootloader's automatic rollback real. See OtaConfirm.h for the rule and OtaRollback.cpp for how.

#include <Arduino.h>

void otaRollbackBegin();                     // call once from setup(), after diagInit()
void otaRollbackLoop();                      // call from loop(): confirms the new firmware, or rolls it back
void otaNoteUploadStart(bool wifiConnected); // call when an update upload is accepted (OTA handler)
bool otaImageOnTrial();            // the running firmware has not been confirmed yet (a minute or so after an update)
const char* otaImageStateName();   // the running image's state, for the diagnostics page
bool otaLastUpdateWasRolledBack(); // the previous update failed its start-up check and was reverted

#endif // OTA_ROLLBACK_H
