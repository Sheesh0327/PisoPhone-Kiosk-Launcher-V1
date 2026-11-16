// GPIO layer: coin acceptor interrupt (pulse counting), relay that powers the acceptor, status
// LED and the hardware reset pin.

#include "HardwareManager.h"
#include "CoinHwApply.h"
#include "CoinSlotManager.h"
#include "Config.h"
#include "Diagnostics.h"
#include "GatewaySessionStore.h"
#include "PulseGuard.h"
#include "RestartGuard.h"
#include <WiFi.h>
#include "hal/gpio_ll.h"

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

    if (ledBlinksRemaining > 0) {
        if (now - lastLedToggleTime >= LED_COIN_PULSE_MS) {
            lastLedToggleTime = now;
            ledBlinksRemaining--;
            ledState = !ledState;
            setLedHardware(ledState);
            if (ledBlinksRemaining == 0 && currentLedState == LED_STATE_CONNECTED) {
                setLedHardware(true);
                ledState = true;
            }
        }
        return;
    }

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

static bool isRelayCurrentlyActive = false;
static const unsigned long RELAY_POWER_ON_BLANKING_MS = 400;
static volatile unsigned long relayPowerOnMs = 0;

static volatile bool isrPulseOpen = false;
static portMUX_TYPE pulseMux = portMUX_INITIALIZER_UNLOCKED;

void resetCoinDetectorStates() {
    portENTER_CRITICAL(&pulseMux);
    isrUniversalPulseCount = 0;
    isrLastPulseTimeMs = 0;
    isrLastPulseTimeUs = 0;
    isrPulseOpen = false;
    portEXIT_CRITICAL(&pulseMux);
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
            Serial.printf("[RELAY] Coin slot powered ON (Pin %d, Mode=OUTPUT, ActiveLow=%s).\n", relayPin,
                          relayActiveLow ? "true" : "false");
        }
    } else {
        digitalWrite(relayPin, relayActiveLow ? HIGH : LOW);
        pinMode(relayPin, INPUT);
        if (isRelayCurrentlyActive) {
            isRelayCurrentlyActive = false;
            Serial.printf("[RELAY] Coin slot powered down into standby (Pin %d, Mode=INPUT, ActiveLow=%s).\n", relayPin,
                          relayActiveLow ? "true" : "false");
        }
    }
}

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
            Serial.println("[RELAY] Relay test ended: the coin slot is off again.");
        }
    }
    static int lastAppliedRelayState = -1;
    int cur = shouldBeOn ? 1 : 0;
    if (cur != lastAppliedRelayState) {
        lastAppliedRelayState = cur;
        setRelayHardware(shouldBeOn);
        Serial.printf("[RELAY] Pin %d set to %s (Mode=%s, ActiveLow=%s, SlotArmed=%s)\n", relayPin,
                      shouldBeOn ? "ON (POWERED)" : "OFF (STANDBY/INPUT)", shouldBeOn ? "OUTPUT" : "INPUT",
                      relayActiveLow ? "true" : "false", shouldBeOn ? "true" : "false");
    }
}

volatile int isrUniversalPulseCount = 0;
volatile unsigned long isrLastPulseTimeMs = 0;
volatile unsigned long isrLastPulseTimeUs = 0;
static const unsigned long U_MIN_PULSE_DEBOUNCE_US = 10000;

volatile bool isrPulseGuardOn = true;
volatile uint32_t isrPulseMinUs = (uint32_t)PULSE_MIN_MS_DEFAULT * 1000UL;
static volatile unsigned long isrPulseStartUs = 0;
static PulseStats isrStats;

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
        if (isrPulseOpen) {
            isrPulseOpen = false;
            uint32_t widthUs = (uint32_t)(nowUs - isrPulseStartUs);
            portENTER_CRITICAL_ISR(&pulseMux);
            pulseStatsAdd(isrStats, widthUs, isrPulseMinUs);
            portEXIT_CRITICAL_ISR(&pulseMux);
            if (isrPulseGuardOn && pulseIsReal(widthUs, isrPulseMinUs)) {
                unsigned long elapsedUs = nowUs - isrLastPulseTimeUs;
                if (elapsedUs >= U_MIN_PULSE_DEBOUNCE_US) {
                    portENTER_CRITICAL_ISR(&pulseMux);
                    isrUniversalPulseCount++;
                    isrLastPulseTimeUs = nowUs;
                    isrLastPulseTimeMs = millis();
                    portEXIT_CRITICAL_ISR(&pulseMux);
                }
            }
        }
        return;
    }
    if (millis() - relayPowerOnMs < RELAY_POWER_ON_BLANKING_MS) return;
    if (isrPulseOpen) return;
    isrPulseOpen = true;
    isrPulseStartUs = nowUs;
    if (isrPulseGuardOn) return;
    unsigned long elapsedUs = nowUs - isrLastPulseTimeUs;
    if (elapsedUs >= U_MIN_PULSE_DEBOUNCE_US) {
        portENTER_CRITICAL_ISR(&pulseMux);
        isrUniversalPulseCount++;
        isrLastPulseTimeUs = nowUs;
        isrLastPulseTimeMs = millis();
        portEXIT_CRITICAL_ISR(&pulseMux);
    }
}

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

static int attachedCoinPin = -1;
static bool coinHwConfigPending = false;

void applyCoinSlotHardwareConfig() {
    if (attachedCoinPin >= 0) detachInterrupt(digitalPinToInterrupt(attachedCoinPin));
    attachedCoinPin = universalCoinPin;

    pinMode(universalCoinPin, INPUT_PULLUP);
    isrPulseGuardOn = coinPulseGuard;
    isrPulseMinUs = (uint32_t)pulseMinMsClamp(coinPulseMinMs) * 1000UL;
    isrPulseOpen = false;
    coinPulseStatsReset();
    attachInterrupt(digitalPinToInterrupt(universalCoinPin), universalCoinIsr, CHANGE);
    Serial.printf("[+] Active Coin Mode: UNIVERSAL MULTI-COIN (GPIO %d, Interrupt Active).\n", universalCoinPin);

    resetCoinDetectorStates();
}

void requestCoinSlotHardwareConfig(bool pinChanged, bool guardChanged, bool minMsChanged) {
    CoinHwApply action = coinHwApplyDecision(pinChanged, guardChanged, minMsChanged, coinHwConfigPending,
                                             getCoinSlotState() == CoinSlotState::IDLE);
    if (action == CoinHwApply::NOW) {
        applyCoinSlotHardwareConfig();
        coinHwConfigPending = false;
    } else if (action == CoinHwApply::LATER) {
        coinHwConfigPending = true;
        Serial.println("[+] Coin settings saved; they apply when the coin slot is idle.");
    }
}

void serviceCoinSlotHardwareConfig() {
    if (coinHwConfigPending) requestCoinSlotHardwareConfig(false, false, false);
}

static unsigned long resetPinLowStart = 0;
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
            Serial.printf("[RESET] GPIO %d connected to GND. Hold for 5 seconds to factory reset...\n",
                          HARDWARE_RESET_PIN);
        } else if (millis() - resetPinLowStart >= 5000 &&
                   resetPinMayRestart(canPerformRebootOrOta(), millis() - resetPinLowStart)) {
            Serial.printf("\n[RESET] GPIO %d held to GND for > 5 seconds! Triggering Factory Reset...\n",
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
