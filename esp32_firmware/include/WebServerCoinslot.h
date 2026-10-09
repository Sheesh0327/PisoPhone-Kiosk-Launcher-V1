#ifndef WEB_SERVER_COINSLOT_H
#define WEB_SERVER_COINSLOT_H

#include <Arduino.h>

void handleApiCoinslotArm();
void handleApiCoinslotUnarm();
void handleApiCoinslotStatus();
void handleApiCoinslotAck();

// Checks the signature of an account call (see AccountProtocol.h); on failure it has already answered 403.
bool accountRequestAuthorized(const char* op, const String& devId, const String& bound);

// Remembers a coin credited during the current session so /api/coinslot/status can report it
// until the phone acknowledges it.
void recordSessionCoinTx(const String& devId, const String& txId, int pulses, int seconds, double amount);

#endif // WEB_SERVER_COINSLOT_H
