// GPIO layer: coin acceptor interrupt (pulse counting), relay that powers the acceptor, status
// LED and the hardware reset pin. The ISR only counts pulses; everything else runs from loop().

#include "Diagnostics.h"
#include "HardwareManager.h"
#include "CoinSlotManager.h"
#include "Config.h"
#include "DeviceManager.h"
#include <WiFi.h>
#include "hal/gpio_ll.h"

// Forward declarations of WebSocket client from WebServerModule
extern WiFiClient wsClient;
extern bool isWsConnected;

// ============================================================================
// NON-BLOCKING LED INDICATOR STATE MACHINE
// ============================================================================
LedSystemState currentLedState = LED_STATE_CONNECTING;

static unsigned long lastLedToggleTime = 0;
static const unsigned long LED_RAPID_TOGGLE_MS = 100;
static const unsigned long LED_SLOW_TOGGLE_MS = 500;
static const unsigned long LED_COIN_PULSE_MS = 60;
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
// Powering the acceptor through the relay makes its output line glitch, which the ISR would
// otherwise count as a coin. Pulses are ignored for a short window after power-on.
static const unsigned long RELAY_POWER_ON_BLANKING_MS = 400;
static volatile unsigned long relayPowerOnMs = 0;

void resetCoinDetectorStates() {
    noInterrupts();
    isrUniversalPulseCount = 0;
    isrLastPulseTimeMs = 0;
    isrLastPulseTimeUs = 0;
    interrupts();
}

void setRelayHardware(bool active) {
    if (active) {
        if (!isRelayCurrentlyActive) {
            relayPowerOnMs = millis();
        }
        pinMode(relayPin, OUTPUT);
        digitalWrite(relayPin, relayActiveLow ? LOW : HIGH);
        if (!isRelayCurrentlyActive) {
            isRelayCurrentlyActive = true;
            Serial.printf("[⚡ RELAY] Coin slot powered ON (Pin %d, Mode=OUTPUT, ActiveLow=%s).\n", relayPin,
                          relayActiveLow ? "true" : "false");
        }
    } else {
        // At idle, set pin to high-impedance INPUT mode so sensitive 5V optocoupled relay modules won't false trigger
        digitalWrite(relayPin, relayActiveLow ? HIGH : LOW);
        pinMode(relayPin, INPUT);
        if (isRelayCurrentlyActive) {
            isRelayCurrentlyActive = false;
            Serial.printf("[⚡ RELAY] Coin slot powered down into standby (Pin %d, Mode=INPUT, ActiveLow=%s).\n",
                          relayPin, relayActiveLow ? "true" : "false");
        }
    }
}

void processRelayState() {
    bool shouldBeOn = isCoinSlotArmed();
    static int lastAppliedRelayState = -1;
    int cur = shouldBeOn ? 1 : 0;
    if (cur != lastAppliedRelayState) {
        lastAppliedRelayState = cur;
        setRelayHardware(shouldBeOn);
        Serial.printf("[⚡ RELAY] Pin %d set to %s (Mode=%s, ActiveLow=%s, SlotArmed=%s)\n", relayPin,
                      shouldBeOn ? "ON (POWERED)" : "OFF (STANDBY/INPUT)", shouldBeOn ? "OUTPUT" : "INPUT",
                      relayActiveLow ? "true" : "false", shouldBeOn ? "true" : "false");
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
    if (millis() - relayPowerOnMs < RELAY_POWER_ON_BLANKING_MS) return;
    // A real pulse holds the line low for 20+ ms; an edge that is already high again is noise.
    if (gpio_ll_get_level(&GPIO, (gpio_num_t)universalCoinPin) != 0) return;
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
// HARDWARE RESET PIN SUPERVISOR (reset pin -> GND for 5 seconds)
// ============================================================================
static unsigned long resetPinLowStart = 0;
// The pin may already be low at power-up (a strapping pin, a stuck BOOT button or a shorted
// header), which must never count as a deliberate 5 s hold and wipe the config. The hold only
// counts once the pin has been seen released (stably high) after boot.
static const unsigned long RESET_PIN_RELEASED_STABLE_MS = 200;
static bool resetPinArmed = false;
static unsigned long resetPinHighSince = 0;

void processHardwareResetPin() {
    if (!resetPinArmed) {
        if (digitalRead(HARDWARE_RESET_PIN) == HIGH) {
            if (resetPinHighSince == 0) resetPinHighSince = millis();
            if (millis() - resetPinHighSince >= RESET_PIN_RELEASED_STABLE_MS) resetPinArmed = true;
        } else {
            resetPinHighSince = 0;
        }
        return;
    }

    if (digitalRead(HARDWARE_RESET_PIN) == LOW) {
        if (resetPinLowStart == 0) {
            resetPinLowStart = millis();
            Serial.printf("[⚠️] GPIO %d connected to GND. Hold for 5 seconds to factory reset...\n", HARDWARE_RESET_PIN);
        } else if (millis() - resetPinLowStart >= 5000) {
            Serial.printf("\n[⚠️ RESET] GPIO %d held to GND for > 5 seconds! Triggering Factory Reset...\n",
                          HARDWARE_RESET_PIN);
            factoryResetDefaults();
            delay(1000);
            diagNoteRestartReason("factory-reset-button");
            ESP.restart();
        }
    } else {
        if (resetPinLowStart != 0) {
            Serial.printf("[*] GPIO %d released before 5 seconds. Reset cancelled.\n", HARDWARE_RESET_PIN);
            resetPinLowStart = 0;
        }
    }
}
