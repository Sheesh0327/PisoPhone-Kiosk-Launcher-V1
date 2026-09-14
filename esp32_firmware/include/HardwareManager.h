#ifndef HARDWARE_MANAGER_H
#define HARDWARE_MANAGER_H

#include <Arduino.h>

enum LedState {
    LED_STATE_OFF,
    LED_STATE_CONNECTED,
    LED_STATE_BLINK_FAST,
    LED_STATE_BLINK_SLOW
};

extern LedState currentLedState;
extern unsigned long lastLedBlinkMs;
extern bool ledBlinkPhase;

void initHardwarePins();
void setLedHardware(bool on);
void setLedState(LedState state);
void setRelayHardware(bool active);
void processRelayState();
void processLedBlinkLoop();

#endif // HARDWARE_MANAGER_H
