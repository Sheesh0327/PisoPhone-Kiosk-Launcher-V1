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

void handleApiCoinslotArm();
void handleApiCoinslotUnarm();
void handleApiCoinslotStatus();
void handleApiCoinslotAck();

void recordSessionCoinTx(const String& devId, const String& txId, int pulses, int seconds, double amount);

#endif // WEB_SERVER_API_H
