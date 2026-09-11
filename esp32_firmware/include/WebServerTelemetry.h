#ifndef WEB_SERVER_TELEMETRY_H
#define WEB_SERVER_TELEMETRY_H

#include <Arduino.h>

void handleHeartbeat();
void handleGetConfig();
void handleCrashReport();
void handleCheckQualification();
void handleOneVsOne();

#endif // WEB_SERVER_TELEMETRY_H
