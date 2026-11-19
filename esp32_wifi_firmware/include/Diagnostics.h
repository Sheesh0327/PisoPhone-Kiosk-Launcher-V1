#ifndef DIAGNOSTICS_H
#define DIAGNOSTICS_H

#include <Arduino.h>

enum class DiagCounter : uint8_t { CoinEvents, CoinPulses, SlotReservations, WifiReconnects, OtaAttempts, Count };

void diagInit();
void diagLog(const char* fmt, ...) __attribute__((format(printf, 1, 2)));
void diagCount(DiagCounter counter, uint32_t amount = 1);
void diagNoteRestartReason(const char* why);
String diagBuildJson();
void handleApiDiagnostics();

#endif // DIAGNOSTICS_H
