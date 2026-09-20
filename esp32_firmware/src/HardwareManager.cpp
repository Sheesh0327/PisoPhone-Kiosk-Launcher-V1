#include "HardwareManager.h"
#include "CoinSlotManager.h"
#include "PaymentQueueManager.h"
#include "Config.h"
#include "DeviceManager.h"
#include <WiFi.h>

// Forward declarations of WebSocket client from WebServerModule
extern WiFiClient wsClient;
extern bool isWsConnected;

// ============================================================================
// NON-BLOCKING LED INDICATOR STATE MACHINE
// ============================================================================
LedSystemState currentLedState = LED_STATE_CONNECTING;

static unsigned long lastLedToggleTime = 0;
static const unsigned long LED_RAPID_TOGGLE_MS = 100;
static const unsigned long LED_SLOW_TOGGLE_MS  = 500;
static const unsigned long LED_COIN_PULSE_MS   = 60;
static int ledBlinksRemaining = 0;
static bool ledState = false;

void setLedHardware(bool on) {
    pinMode(ledPin, OUTPUT);
    digitalWrite(ledPin, (on ^ ledActiveLow) ? HIGH : LOW);
}

void triggerLedBlink(int blinkCount) {
    ledBlinksRemaining = blinkCount * 2 - 1;
    ledState = false;
    setLedHardware(false);
    lastLedToggleTime = millis();
}

void processLedBlink() {
    unsigned long now = millis();

    // 1. Transient Coin Insertion Double Blink Override
    if (ledBlinksRemaining > 0) {
        if (now - lastLedToggleTime >= LED_COIN_PULSE_MS) {
            lastLedToggleTime = now;
            ledBlinksRemaining--;
            ledState = !ledState;
            setLedHardware(ledState);
            if (ledBlinksRemaining == 0) {
                if (currentLedState == LED_STATE_CONNECTED) {
                    setLedHardware(true);
                    ledState = true;
                }
            }
        }
        return;
    }

    // 2. Wi-Fi Status LED Indicator Patterns
    switch (currentLedState) {
        case LED_STATE_CONNECTING:
            if (now - lastLedToggleTime >= LED_RAPID_TOGGLE_MS) {
                lastLedToggleTime = now;
                ledState = !ledState;
                setLedHardware(ledState);
            }
            break;

        case LED_STATE_FAILED:
            if (now - lastLedToggleTime >= LED_SLOW_TOGGLE_MS) {
                lastLedToggleTime = now;
                ledState = !ledState;
                setLedHardware(ledState);
            }
            break;

        case LED_STATE_CONNECTED:
            if (!ledState) {
                setLedHardware(true);
                ledState = true;
            }
            break;
    }
}

// ============================================================================
// RELAY POWER CONTROLLER
// ============================================================================
static bool isRelayCurrentlyActive = false;

void resetCoinDetectorStates() {
    noInterrupts();
    isrUniversalPulseCount = 0;
    isrLastPulseTimeMs = 0;
    isrLastPulseTimeUs = 0;
    interrupts();
}

void setRelayHardware(bool active) {
    // Startup suppression hard-lock: do not power acceptor until startup suppression finishes
    if (active && isStartupSuppressionActive()) {
        active = false;
    }

    if (active) {
        pinMode(relayPin, OUTPUT);
        digitalWrite(relayPin, relayActiveLow ? LOW : HIGH);
        if (!isRelayCurrentlyActive) {
            isRelayCurrentlyActive = true;
            Serial.printf("[⚡ RELAY] Coin slot powered ON (Pin %d, Mode=OUTPUT, ActiveLow=%s).\n", relayPin, relayActiveLow ? "true" : "false");
        }
    } else {
        pinMode(relayPin, INPUT);
        if (isRelayCurrentlyActive) {
            isRelayCurrentlyActive = false;
            Serial.printf("[⚡ RELAY] Coin slot powered down into hi-Z standby (Pin %d, Mode=INPUT).\n", relayPin);
        }
    }
}

bool isSlotArmed() {
    return isCoinSlotArmed() && !isStartupSuppressionActive();
}

void processRelayState() {
    bool shouldBeOn = isCoinSlotArmed() && !isStartupSuppressionActive();
    static int lastAppliedRelayState = -1;
    int cur = shouldBeOn ? 1 : 0;
    if (cur != lastAppliedRelayState) {
        lastAppliedRelayState = cur;
        setRelayHardware(shouldBeOn);
        Serial.printf("[⚡ RELAY] Pin %d set to %s (ActiveLow=%s, SlotArmed=%s, Suppressed=%s)\n",
            relayPin, shouldBeOn ? "ON (POWERED)" : "OFF (STANDBY)",
            relayActiveLow ? "true" : "false", isCoinSlotArmed() ? "true" : "false",
            isStartupSuppressionActive() ? "true" : "false");
    }
}

// ============================================================================
// HARDWARE PULSE ISR & DEBOUNCER - UNIVERSAL MULTI-COIN ACCEPTOR (GPIO 3)
// ============================================================================
volatile int isrUniversalPulseCount = 0;
volatile unsigned long isrLastPulseTimeMs = 0;
volatile unsigned long isrLastPulseTimeUs = 0;
// Debounce threshold: 10ms (10,000us) ensures 20ms FAST coin pulses are cleanly captured
// while mechanical noise spikes (< 10ms) are strictly filtered out.
static const unsigned long U_MIN_PULSE_DEBOUNCE_US = 10000;

void IRAM_ATTR universalCoinIsr() {
    unsigned long nowUs = micros();
    unsigned long elapsedUs = nowUs - isrLastPulseTimeUs;
    if (elapsedUs >= U_MIN_PULSE_DEBOUNCE_US) {
        isrUniversalPulseCount++;
        isrLastPulseTimeUs = nowUs;
        isrLastPulseTimeMs = millis();
    }
}

void applyCoinSlotHardwareConfig() {
    detachInterrupt(digitalPinToInterrupt(universalCoinPin));
    
    // Universal Multi-Coin Pulse Slot (Allan 124A/616A)
    pinMode(universalCoinPin, INPUT_PULLUP);
    attachInterrupt(digitalPinToInterrupt(universalCoinPin), universalCoinIsr, FALLING);
    Serial.printf("[+] Active Coin Mode: UNIVERSAL MULTI-COIN (GPIO %d, Interrupt Active).\n", universalCoinPin);

    resetCoinDetectorStates();
}

// ============================================================================
// HARDWARE RESET PIN SUPERVISOR (GPIO 2 -> GND for 5 seconds)
// ============================================================================
static unsigned long resetPinLowStart = 0;

void processHardwareResetPin() {
    if (digitalRead(HARDWARE_RESET_PIN) == LOW) {
        if (resetPinLowStart == 0) {
            resetPinLowStart = millis();
            Serial.println("[⚠️] GPIO 2 connected to GND. Hold for 5 seconds to factory reset...");
        } else if (millis() - resetPinLowStart >= 5000) {
            Serial.println("\n[⚠️ RESET] GPIO 2 held to GND for > 5 seconds! Triggering Factory Reset...");
            factoryResetDefaults();
            delay(500);
            requestSystemRestart("Hardware Pin 2 Factory Reset");
        }
    } else {
        if (resetPinLowStart != 0) {
            Serial.println("[*] GPIO 2 released before 5 seconds. Reset cancelled.");
            resetPinLowStart = 0;
        }
    }
}
