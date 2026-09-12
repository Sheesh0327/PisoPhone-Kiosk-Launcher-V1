#include "HardwareManager.h"
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
    interrupts();
    pulseTrainStartTime = 0;
}

void setRelayHardware(bool active) {
    pinMode(relayPin, OUTPUT);
    if (active) {
        if (!isRelayCurrentlyActive) {
            isRelayCurrentlyActive = true;
            resetCoinDetectorStates();
            Serial.printf("[⚡ RELAY] Coin slot powered ON (Pin %d, ActiveLow=%s).\n", relayPin, relayActiveLow ? "true" : "false");
        }
        digitalWrite(relayPin, relayActiveLow ? LOW : HIGH);
    } else {
        if (isRelayCurrentlyActive) {
            isRelayCurrentlyActive = false;
            resetCoinDetectorStates();
            Serial.println("[⚡ RELAY] Coin slot powered down into standby mode.");
        }
        digitalWrite(relayPin, relayActiveLow ? HIGH : LOW);
    }
}

bool isSlotArmed() {
    return (armedIp.length() > 0 && millis() < armedUntil);
}

void processRelayState() {
    bool shouldBeOn = isSlotArmed();
    static int lastAppliedRelayState = -1;
    int cur = shouldBeOn ? 1 : 0;
    if (cur != lastAppliedRelayState) {
        lastAppliedRelayState = cur;
        setRelayHardware(shouldBeOn);
        Serial.printf("[⚡ RELAY] Pin %d set to %s (ActiveLow=%s, SlotArmed=%s)\n",
            relayPin, shouldBeOn ? "ON (POWERED)" : "OFF (STANDBY)",
            relayActiveLow ? "true" : "false", isSlotArmed() ? "true" : "false");
    }
}

// ============================================================================
// HARDWARE PULSE ISR & DEBOUNCER - UNIVERSAL MULTI-COIN ACCEPTOR (GPIO 3)
// ============================================================================
volatile int isrUniversalPulseCount = 0;
volatile unsigned long isrLastPulseTimeMs = 0;
static const unsigned long U_MIN_PULSE_DEBOUNCE_MS = 30; // Reject spikes shorter than 30ms
static const unsigned long U_INTER_PULSE_TIMEOUT_MS = 280;

void IRAM_ATTR universalCoinIsr() {
    unsigned long now = millis();
    if (now - isrLastPulseTimeMs >= U_MIN_PULSE_DEBOUNCE_MS) {
        isrUniversalPulseCount++;
        isrLastPulseTimeMs = now;
    }
}

void applyCoinSlotHardwareConfig() {
    detachInterrupt(digitalPinToInterrupt(universalCoinPin));
    pinMode(universalCoinPin, INPUT_PULLUP);
    attachInterrupt(digitalPinToInterrupt(universalCoinPin), universalCoinIsr, FALLING);
    Serial.printf("[+] Universal Multi-Coin Slot active on GPIO %d (Interrupt Active)\n", universalCoinPin);
    resetCoinDetectorStates();
}

void processUniversalCoinDetector() {
    unsigned long now = millis();
    if (now < 3000) {
        if (isrUniversalPulseCount > 0) {
            noInterrupts();
            isrUniversalPulseCount = 0;
            interrupts();
        }
        return;
    }

    noInterrupts();
    int count = isrUniversalPulseCount;
    unsigned long lastPulseTime = isrLastPulseTimeMs;
    interrupts();

    if (count > 0 && pulseTrainStartTime == 0) {
        pulseTrainStartTime = lastPulseTime;
        pulseTrainDeviceId = (armedIp.length() > 0) ? armedIp : lastArmedDeviceId;
        pulseTrainDeviceIp = (armedIp.length() > 0) ? getIpFromDeviceId(armedIp) : lastArmedIp;
    }

    if (count > 0 && (now - lastPulseTime >= U_INTER_PULSE_TIMEOUT_MS)) {
        noInterrupts();
        int finalPulses = isrUniversalPulseCount;
        isrUniversalPulseCount = 0;
        interrupts();

        pulseTrainStartTime = 0;

        if (finalPulses > 0) {
            Serial.printf("[⚡ UNIVERSAL COIN] Detected %d pulse(s) on GPIO %d! Triggering coin event...\n", finalPulses, universalCoinPin);
            triggerUniversalCoinEvent(finalPulses);
        }

        if (pendingWsGracefulClose) {
            pendingWsGracefulClose = false;
            if (isWsConnected && wsClient.connected()) {
                wsClient.stop();
            }
            isWsConnected = false;
            armedIp = "";
            armedUntil = 0;
            sessionStartTime = 0;
            Serial.println("[*] Graceful WS session close completed after delivering final coin pulses.");
        }
    }
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
            delay(1000);
            ESP.restart();
        }
    } else {
        if (resetPinLowStart != 0) {
            Serial.println("[*] GPIO 2 released before 5 seconds. Reset cancelled.");
            resetPinLowStart = 0;
        }
    }
}
