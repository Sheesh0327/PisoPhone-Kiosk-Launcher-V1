#ifndef WEB_SERVER_API_H
#define WEB_SERVER_API_H

#include <Arduino.h>

void handleAddTime();
void handleQueryTime();
void handleApiLocate();

void handleApiSlots();
void handleApiSlotPair();
void handleApiSlotPairRequest();
void handleApiSlotUnpair();
void handleApiSlotCloudSync();
void handleApiSecuritySwitchKey();
void handleApiStatus();
void handleIdentify();

#endif // WEB_SERVER_API_H
