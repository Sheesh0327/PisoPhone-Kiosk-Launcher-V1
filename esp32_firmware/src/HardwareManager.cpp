// GPIO layer: coin acceptor interrupt (pulse counting), relay that powers the acceptor, status
// LED and the hardware reset pin. The ISR only counts pulses; everything else runs from loop().

#include "Diagnostics.h"
#include "HardwareManager.h"
#include "CoinSlotManager.h"
#include "Config.h"
#include "DeviceManager.h"
#include "PulseGuard.h"
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

static volatile bool isrPulseOpen = false; // a pulse has begun and the line has not gone high again

void resetCoinDetectorStates() {
    noInterrupts();
    isrUniversalPulseCount = 0;
    isrLastPulseTimeMs = 0;
    isrLastPulseTimeUs = 0;
    isrPulseOpen = false;
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

// The dashboard's relay test: "on" with no session open is switched off again after this long (it used to stay on for good,
// and coins inserted then were accepted by the acceptor and dropped by the box with no record).
static const unsigned long RELAY_TEST_MAX_MS = 10000;
static unsigned long relayTestOnSinceMs = 0;
static bool relayTestOn = false;

void relayManualTest(bool on) {
    setRelayHardware(on);
    relayTestOn = on;
    relayTestOnSinceMs = millis();
}

void processRelayState() {
    bool shouldBeOn = isCoinSlotArmed();
    if (relayTestOn && (shouldBeOn || millis() - relayTestOnSinceMs >= RELAY_TEST_MAX_MS)) {
        relayTestOn = false;
        if (!shouldBeOn) {
            setRelayHardware(false);
            Serial.println("[⚡ RELAY] Relay test ended: the coin slot is off again.");
        }
    }
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

// Fake-pulse protection (include/PulseGuard.h). Every pulse is measured from its falling edge to its rising edge. With the guard OFF
// (the default) a pulse is counted at its falling edge exactly as it always was and the measurement is only kept for the dashboard.
// With the guard ON a pulse is counted at its rising edge, and only when the line stayed low for at least the owner's limit.
volatile bool isrPulseGuardOn = false;
volatile uint32_t isrPulseMinUs = (uint32_t)PULSE_MIN_MS_DEFAULT * 1000UL;
static volatile unsigned long isrPulseStartUs = 0;
static PulseStats isrStats; // written by the ISR and read with the lock below
static portMUX_TYPE pulseMux = portMUX_INITIALIZER_UNLOCKED;

PulseStats coinPulseStats() {
    PulseStats copy;
    portENTER_CRITICAL(&pulseMux);
    copy = isrStats;
    portEXIT_CRITICAL(&pulseMux);
    return copy;
}

void coinPulseStatsReset() {
    portENTER_CRITICAL(&pulseMux);
    memset(&isrStats, 0, sizeof isrStats);
    portEXIT_CRITICAL(&pulseMux);
}

void IRAM_ATTR universalCoinIsr() {
    unsigned long nowUs = micros();
    bool lineLow = gpio_ll_get_level(&GPIO, (gpio_num_t)universalCoinPin) == 0;
    if (!lineLow) {
        // The line is high: a pulse that had begun has ended. Measure it (and, with the guard on, count it if it was long enough).
        if (isrPulseOpen) {
            isrPulseOpen = false;
            uint32_t widthUs = (uint32_t)(nowUs - isrPulseStartUs);
            portENTER_CRITICAL_ISR(&pulseMux);
            pulseStatsAdd(isrStats, widthUs, isrPulseMinUs);
            portEXIT_CRITICAL_ISR(&pulseMux);
            if (isrPulseGuardOn && pulseIsReal(widthUs, isrPulseMinUs)) {
                portENTER_CRITICAL_ISR(&pulseMux);
                isrUniversalPulseCount++;
                isrLastPulseTimeUs = nowUs;
                isrLastPulseTimeMs = millis();
                portEXIT_CRITICAL_ISR(&pulseMux);
            }
        }
        return;
    }
    if (millis() - relayPowerOnMs < RELAY_POWER_ON_BLANKING_MS) return;
    if (isrPulseOpen) return; // still the same pulse (the line never went high in between)
    // A real pulse holds the line low for 20+ ms; an edge that is already high again is noise (handled above).
    isrPulseOpen = true;
    isrPulseStartUs = nowUs;
    if (isrPulseGuardOn) return; // counted when it ends, if it was long enough
    unsigned long elapsedUs = nowUs - isrLastPulseTimeUs;
    if (elapsedUs >= U_MIN_PULSE_DEBOUNCE_US) {
        portENTER_CRITICAL_ISR(&pulseMux);
        isrUniversalPulseCount++;
        isrLastPulseTimeUs = nowUs;
        isrLastPulseTimeMs = millis();
        portEXIT_CRITICAL_ISR(&pulseMux);
    }
}

// The loop's side of the pulse counter. Read-and-zero under the same lock the ISR counts under: noInterrupts() is not that lock,
// and a pulse counted between the read and the zeroing was lost (a coin one peso short).
int coinPulsesTake(unsigned long* lastPulseMs) {
    portENTER_CRITICAL(&pulseMux);
    int pulses = isrUniversalPulseCount;
    isrUniversalPulseCount = 0;
    if (lastPulseMs) *lastPulseMs = isrLastPulseTimeMs;
    portEXIT_CRITICAL(&pulseMux);
    return pulses;
}

int coinPulsesPeek(unsigned long* lastPulseMs) {
    portENTER_CRITICAL(&pulseMux);
    int pulses = isrUniversalPulseCount;
    if (lastPulseMs) *lastPulseMs = isrLastPulseTimeMs;
    portEXIT_CRITICAL(&pulseMux);
    return pulses;
}

void applyCoinSlotHardwareConfig() {
    detachInterrupt(digitalPinToInterrupt(universalCoinPin));

    // Universal Multi-Coin Pulse Slot (Allan 124A/616A)
    pinMode(universalCoinPin, INPUT_PULLUP);
    // both edges: the width of each pulse is measured (see the fake-pulse protection above)
    isrPulseGuardOn = coinPulseGuard;
    isrPulseMinUs = (uint32_t)pulseMinMsClamp(coinPulseMinMs) * 1000UL;
    isrPulseOpen = false;
    coinPulseStatsReset();
    attachInterrupt(digitalPinToInterrupt(universalCoinPin), universalCoinIsr, CHANGE);
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
