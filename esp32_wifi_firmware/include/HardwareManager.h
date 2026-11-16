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
void relayManualTest(bool on);

void IRAM_ATTR universalCoinIsr();
void resetCoinDetectorStates();
void applyCoinSlotHardwareConfig();
void requestCoinSlotHardwareConfig(bool pinChanged, bool guardChanged, bool minMsChanged);
void serviceCoinSlotHardwareConfig();

extern volatile int isrUniversalPulseCount;
extern volatile unsigned long isrLastPulseTimeMs;
extern volatile unsigned long isrLastPulseTimeUs;

PulseStats coinPulseStats();
void coinPulseStatsReset();
int coinPulsesTake(unsigned long* lastPulseMs = nullptr);
int coinPulsesPeek(unsigned long* lastPulseMs = nullptr);

void processHardwareResetPin();

#endif // HARDWARE_MANAGER_H
