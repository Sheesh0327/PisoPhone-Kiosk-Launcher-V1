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
bool isSlotArmed();
void processRelayState();

void processCoinDetector();
void IRAM_ATTR universalCoinIsr();
void processUniversalCoinDetector();
extern volatile int isrUniversalPulseCount;
extern volatile unsigned long isrLastPulseTimeMs;
extern volatile bool coinSlotWarmupActive;

void processHardwareResetPin();

#endif // HARDWARE_MANAGER_H
