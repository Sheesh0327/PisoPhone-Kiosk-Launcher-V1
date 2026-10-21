#ifndef WEB_SERVER_API_H
#define WEB_SERVER_API_H

#include <Arduino.h>
#include "Product.h"

#if PISO_HAS_PHONES
void handleAddTime();
void handleQueryTime();
void handleApiLocate();

void handleApiSlots();
void handleApiSlotPair();
void handleApiSlotPairRequest();
void handleApiSlotUnpair();
void handleApiSlotCloudSync();
void handleApiSecuritySwitchKey();
void handleApiLicense();
void handleApiLicenseSet();
#endif
void handleApiStatus();
#if PISO_HAS_PHONES
void handleIdentify();
#endif

#endif // WEB_SERVER_API_H
