#ifndef WEB_SERVER_API_H
#define WEB_SERVER_API_H

#include <Arduino.h>

void handleAddTime();
void handleQueryTime();

void handleApiSlots();
void handleApiSlotPair();
void handleApiSlotPairRequest();
void handleApiSlotUnpair();
void handleApiSlotApplyToken();
void handleApiSlotCloudSync();
void handleApiStatus();
void handleIdentify();

void handleCoinslotActivate();
void handleCoinslotStatus();
void handleCoinslotDeactivate();

#endif // WEB_SERVER_API_H
