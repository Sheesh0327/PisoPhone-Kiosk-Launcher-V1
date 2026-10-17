#ifndef HARDWARE_MANAGER_H
#define HARDWARE_MANAGER_H

#include <Arduino.h>
#include "PulseGuard.h"

enum LedSystemState { LED_STATE_CONNECTING, LED_STATE_FAILED, LED_STATE_CONNECTED };

extern LedSystemState currentLedState;

void setLedHardware(bool on);
void triggerLedBlink(int blinkCount = 2);
void processLedBlink();

void setRelayHardware(bool active);
void processRelayState();
// the dashboard's relay test (an "on" is switched off again after 10 s unless a session is open)
void relayManualTest(bool on);

void IRAM_ATTR universalCoinIsr();
void resetCoinDetectorStates();
void applyCoinSlotHardwareConfig();
extern volatile int isrUniversalPulseCount;
extern volatile unsigned long isrLastPulseTimeMs;
extern volatile unsigned long isrLastPulseTimeUs;
// the measured widths of the coin pulses (a copy) and a reset (see PulseGuard.h)
PulseStats coinPulseStats();
void coinPulseStatsReset();
// the pulses counted since the last take (zeroed), or a look without zeroing; both under the ISR's lock
int coinPulsesTake(unsigned long* lastPulseMs = nullptr);
int coinPulsesPeek(unsigned long* lastPulseMs = nullptr);

void processHardwareResetPin();

#endif // HARDWARE_MANAGER_H
