#ifndef DIAGNOSTICS_H
#define DIAGNOSTICS_H

#include <Arduino.h>

// Field diagnostics: a ring buffer of recent log lines, event counters and the reset history, all
// readable at GET /api/diagnostics (admin login) without a serial cable.

enum class DiagCounter : uint8_t {
    CoinEvents,
    CoinPulses,
    PaymentsQueued,
    PaymentsAcked,
    PaymentsEvicted,
    PersistFailures,
    SlotReservations,
    WsConnects,
    WifiReconnects,
    OtaAttempts,
    Count
};

// Call once at the very start of setup(): records why the board restarted and bumps the boot count.
void diagInit();

// printf-style. Prints to the serial console exactly like Serial.printf and also keeps the line in
// the ring buffer. Safe to call from any task (never from an ISR).
void diagLog(const char* fmt, ...) __attribute__((format(printf, 1, 2)));

void diagCount(DiagCounter counter, uint32_t amount = 1);

// Saves why the board is about to restart; the next boot shows it in the diagnostics (reset.cause).
// Call right before ESP.restart().
void diagNoteRestartReason(const char* why);

String diagBuildJson();
void handleApiDiagnostics();

#endif // DIAGNOSTICS_H
