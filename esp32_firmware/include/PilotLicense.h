#pragma once

#if defined(PISO_WORKSHOP_PILOT)
#include <Arduino.h>

void startPilotLicense();
int pilotLicensedSlots();
bool provisionPilotCredential(const String& credential);
const char* pilotLicenseState();
#endif
