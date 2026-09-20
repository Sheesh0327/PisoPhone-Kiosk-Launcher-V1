#ifndef HARDWARE_MANAGER_H
#define HARDWARE_MANAGER_H

#include <Arduino.h>

enum LedSystemState {
    LED_STATE_CONNECTING,
    LED_STATE_FAILED,
    LED_STATE_CONNECTED
};

extern LedSystemState currentLedState;

void setLedHardware(bool on);
void triggerLedBlink(int blinkCount = 2);
void processLedBlink();

void setRelayHardware(bool active);
bool isRelayHardwareActive();
bool isSlotArmed();
void processRelayState();

void IRAM_ATTR universalCoinIsr();
void resetCoinDetectorStates();
void applyCoinSlotHardwareConfig();
extern volatile int isrUniversalPulseCount;
extern volatile unsigned long isrLastPulseTimeMs;
extern volatile unsigned long isrLastPulseTimeUs;

void processHardwareResetPin();

#endif // HARDWARE_MANAGER_H
